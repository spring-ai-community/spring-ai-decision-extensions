/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springaicommunity.decision.judge;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.decision.judge.DecisionCriterion.QuestionCriterion;
import org.springaicommunity.decision.test.StubDecisionModel;

import org.springframework.ai.decision.DecisionOptions;
import org.springframework.ai.decision.DecisionResponseMetadata;
import org.springframework.ai.decision.answer.ChoiceAnswer;
import org.springframework.ai.decision.answer.DecisionAnswer;
import org.springframework.ai.decision.answer.ScoreAnswer;
import org.springframework.ai.decision.input.DecisionInput;
import org.springframework.ai.decision.input.FieldsInput;
import org.springframework.ai.decision.question.ChoiceQuestion;
import org.springframework.ai.decision.question.ScoreQuestion;
import org.springframework.ai.decision.question.YesNoQuestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;
import static org.springaicommunity.decision.test.StubDecisionModel.choice;
import static org.springaicommunity.decision.test.StubDecisionModel.probabilities;
import static org.springaicommunity.decision.test.StubDecisionModel.refusal;
import static org.springaicommunity.decision.test.StubDecisionModel.score;
import static org.springaicommunity.decision.test.StubDecisionModel.yesNo;

/**
 * Threshold and feedback-synthesis behaviour of the judge. A decision model returns
 * numbers, never prose, so the feedback these tests assert on is built by the judge out
 * of the rubric the caller supplied.
 *
 * @author Christian Tzolov
 */
class DecisionJudgeTests {

	private static final ScoreQuestion HELPFULNESS = ScoreQuestion.builder()
		.text("How well does `assistant_answer` address `user_question`?")
		.level("Terrible: irrelevant or off-topic")
		.level("Mostly unhelpful: misses the main point")
		.level("Mostly helpful: minor gaps remain")
		.level("Excellent: fully and correctly addressed")
		.build();

	private static final YesNoQuestion PLAUSIBLE = YesNoQuestion.builder()
		.text("Are the values in `assistant_answer` physically plausible?")
		.whenYes("Every value is physically possible")
		.whenNo("Contains an impossible or absurd value")
		.build();

	private static final ChoiceQuestion MODE = ChoiceQuestion.builder()
		.text("How does `assistant_answer` respond to `user_question`?")
		.option("answered", "Gives the weather")
		.option("clarification_needed", "Asks which place is meant")
		.build();

	private static final YesNoQuestion DETAILS = YesNoQuestion.builder()
		.text("Does `assistant_answer` give timing and a source?")
		.whenNo("Gives no timing or source")
		.build();

	private static final Map<String, DecisionAnswer> PLAUSIBLE_ONLY = Map.of("is_plausible", yesNo(0.95));

	private final StubDecisionModel model = new StubDecisionModel();

	@Test
	void passesWhenEveryCriterionIsMet() {
		this.model.respondWith(
				Map.of("helpfulness", score(2.74, 0.86, 0.01, 0.04, 0.15, 0.8), "is_plausible", yesNo(0.95)),
				DecisionResponseMetadata.builder().id("req_0123456789").build());

		DecisionVerdict verdict = judge().judge("What is the weather in Paris?", "It is 15 degrees Celsius in Paris.");

		assertThat(verdict.passed()).isTrue();
		assertThat(verdict.feedback()).isEmpty();
		assertThat(verdict.failures()).isEmpty();
		assertThat(verdict.summary()).isEqualTo("passed=true [helpfulness=PASSED, is_plausible=PASSED]");
		assertThat(verdict.response().getMetadata().getId()).isEqualTo("req_0123456789");
	}

	@Test
	void failsAYesNoQuestionAndQuotesItsNoSideAsTheDefect() {
		respondWith(Map.of("helpfulness", score(3.0, 0.9, 0.0, 0.0, 0.0, 1.0), "is_plausible", yesNo(0.04)));

		DecisionVerdict verdict = judge().judge("What is the weather in Paris?",
				"It is -255 degrees Celsius in Paris.");

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.failures()).singleElement()
			.satisfies(finding -> assertThat(finding.name()).isEqualTo("is_plausible"));
		assertThat(verdict.feedback())
			.isEqualTo("- is_plausible: Contains an impossible or absurd value (scored 0.04, needs at least 0.70)");
	}

	@Test
	void failsAScoreAndNamesBothTheLevelReachedAndTheLevelRequired() {
		respondWith(Map.of("helpfulness", score(1.2, 0.71, 0.1, 0.65, 0.2, 0.05), "is_plausible", yesNo(0.99)));

		DecisionVerdict verdict = judge().judge("What is the weather in Paris?", "Weather is hard to predict.");

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.feedback())
			.isEqualTo("- helpfulness: rated \"Mostly unhelpful: misses the main point\" (1.20), "
					+ "needs to reach 2.00 which is \"Mostly helpful: minor gaps remain\"");
	}

	@Test
	void reportsBothDefectsWhenBothCriteriaFail() {
		respondWith(Map.of("helpfulness", score(0.4, 0.68, 0.7, 0.2, 0.07, 0.03), "is_plausible", yesNo(0.1)));

		DecisionVerdict verdict = judge().judge("What is the weather in Paris?", "It is -255 degrees in Narnia.");

		assertThat(verdict.failures()).hasSize(2);
		assertThat(verdict.feedback()).contains("- helpfulness: rated \"Terrible: irrelevant or off-topic\"")
			.contains("- is_plausible: Contains an impossible or absurd value");
	}

	@Test
	void treatsAFlatDistributionAsUndecidedRatherThanFailed() {
		respondWith(Map.of("helpfulness", score(1.5, 0.2, 0.25, 0.25, 0.25, 0.25), "is_plausible", yesNo(0.99)));

		DecisionVerdict verdict = judge().judge("What is the weather in Paris?", "Possibly mild.");

		assertThat(verdict.passed()).isTrue();
		assertThat(verdict.failures()).isEmpty();
		assertThat(verdict.inconclusive()).singleElement()
			.satisfies(finding -> assertThat(finding.name()).isEqualTo("helpfulness"));
	}

	@Test
	void canBeConfiguredToRejectAnUndecidedCriterion() {
		respondWith(Map.of("helpfulness", score(1.5, 0.2, 0.25, 0.25, 0.25, 0.25)));

		DecisionJudge strict = DecisionJudge.builder(this.model)
			.score("helpfulness", HELPFULNESS, 2.0d)
			.failOnInconclusive(true)
			.build();

		DecisionVerdict verdict = strict.judge("What is the weather in Paris?", "Possibly mild.");

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.feedback()).contains("the rubric did not settle whether this reaches 2.00")
			.contains("0.50 of the probability supports the verdict, needs at least 0.60");
	}

	@Test
	void aYesNoAnswerIsNotGatedOnConfidenceBecauseItCarriesNone() {
		respondWith(Map.of("is_plausible", yesNo(0.99)));

		DecisionJudge strict = DecisionJudge.builder(this.model)
			.yesNo("is_plausible", PLAUSIBLE, 0.7d)
			.minConfidence(0.99d)
			.failOnInconclusive(true)
			.build();

		assertThat(strict.judge("q", "a").passed()).isTrue();
	}

	@Test
	void judgesAChoiceAgainstTheAcceptedOptions() {
		respondWith(Map.of("tone",
				choice("dismissive", 0.74, probabilities("helpful", 0.12, "neutral", 0.2, "dismissive", 0.68))));

		DecisionJudge toneJudge = DecisionJudge.builder(this.model)
			.choice("tone",
					ChoiceQuestion.builder()
						.text("What tone does `assistant_answer` take?")
						.option("helpful", "Answers and offers next steps")
						.option("neutral", "Answers plainly")
						.option("dismissive", "Brushes the question off")
						.build(),
					"helpful", "neutral")
			.build();

		DecisionVerdict verdict = toneJudge.judge("q", "Figure it out yourself.");

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.feedback()).contains(
				"tone: 0.68 of the probability is on options outside [helpful, neutral] (most likely \"dismissive\")");
	}

	@Test
	void rejectsAnAcceptedOptionThatIsNotOneOfTheChoiceOptions() {
		ChoiceQuestion tone = ChoiceQuestion.of("What tone?", "helpful", "neutral");

		assertThatIllegalArgumentException().isThrownBy(() -> DecisionCriterion.choice("tone", tone, "enthusiastic"))
			.withMessageContaining("is not one of the choice's options");
	}

	@Test
	void rejectsAScoreThresholdAboveTheRubric() {
		assertThatIllegalArgumentException().isThrownBy(() -> DecisionCriterion.score("helpfulness", HELPFULNESS, 9.0d))
			.withMessageContaining("highest level (3)");
	}

	@Test
	void rejectsDuplicateCriterionNames() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> DecisionJudge.builder(this.model)
				.yesNo("is_plausible", PLAUSIBLE, 0.7d)
				.yesNo("is_plausible", PLAUSIBLE, 0.8d))
			.withMessageContaining("already declared");
	}

	@Test
	void fallsBackToTheQuestionTextWhenTheQuestionNamesNoNoSide() {
		respondWith(Map.of("is_plausible", yesNo(0.04)));

		DecisionJudge judge = DecisionJudge.builder(this.model)
			.yesNo("is_plausible", YesNoQuestion.of("Are the values plausible?"), 0.7d)
			.build();

		assertThat(judge.judge("q", "a").feedback()).isEqualTo(
				"- is_plausible: the answer to \"Are the values plausible?\" was no (scored 0.04, needs at least 0.70)");
	}

	@Test
	void rejectsAJudgeWithoutCriteria() {
		assertThatIllegalArgumentException().isThrownBy(() -> DecisionJudge.builder(this.model).build())
			.withMessageContaining("at least one criterion");
	}

	@Test
	void acceptsARepeatedOptionAndKeepsTheOptionsInDeclarationOrder() {
		// Set.of would throw on the duplicate, and its iteration order is salted per JVM
		// run, which would make the feedback text differ between runs.
		ChoiceQuestion tone = ChoiceQuestion.of("What tone?", "helpful", "neutral", "dismissive");

		DecisionCriterion.QuestionCriterion criterion = DecisionCriterion.choice("tone", tone, "helpful", "neutral",
				"helpful");

		assertThat(criterion.acceptedOptions()).containsExactly("helpful", "neutral");
	}

	@Test
	void rejectsANullAcceptedOption() {
		ChoiceQuestion tone = ChoiceQuestion.of("What tone?", "helpful", "neutral");

		assertThatIllegalArgumentException().isThrownBy(() -> DecisionCriterion.choice("tone", tone, "helpful", null))
			.withMessageContaining("must not contain a null option");
	}

	@Test
	void reportsARefusalAsAnErrorRatherThanFailingTheWholeCall() {
		// A refusal should degrade that one criterion, not abort the caller's chat call.
		// It is an instrument failure, not an ambiguous question, so it is ERROR, not
		// INCONCLUSIVE.
		respondWith(Map.of("helpfulness", score(3.0, 0.9, 0.0, 0.0, 0.0, 1.0), "is_plausible", refusal("policy")));

		DecisionVerdict verdict = judge().judge("q", "a");

		assertThat(verdict.passed()).isTrue();
		assertThat(verdict.errors()).singleElement().satisfies(finding -> {
			assertThat(finding.name()).isEqualTo("is_plausible");
			assertThat(finding.outcome()).isEqualTo(DecisionFinding.Outcome.ERROR);
			assertThat(finding.detail()).isEqualTo("is_plausible: the model refused to answer this criterion (policy)");
		});
		assertThat(verdict.inconclusive()).isEmpty();
	}

	@Test
	void doesNotFollowARefusedChoice() {
		respondWith(Map.of("mode", refusal(null), "has_details", yesNo(0.05)));

		DecisionVerdict verdict = branchingJudge().judge("q", "a");

		assertThat(verdict.summary()).isEqualTo("passed=true [mode=ERROR, has_details=NOT_APPLICABLE]");
		assertThat(verdict.errors().get(0).detail()).isEqualTo("mode: the model refused to answer this criterion");
	}

	@Test
	void failsACodeCheckAndQuotesItsDefectAlongsideTheQuestions() {
		// The check is answered in code, but it must land in the same verdict: it fails
		// the verdict, reaches the feedback and shows in the summary like a question
		// does.
		respondWith(PLAUSIBLE_ONLY);

		DecisionVerdict verdict = searchJudge().judge(DecisionJudgeInput.builder()
			.question("Weather in Dublin?")
			.answer("No rain expected.")
			.field("search_required", true)
			.build());

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.summary()).isEqualTo("passed=false [searched=FAILED, is_plausible=PASSED]");
		assertThat(verdict.failures()).singleElement().satisfies(finding -> {
			assertThat(finding.criterion()).isInstanceOf(DecisionCriterion.CodeCriterion.class);
			assertThat(finding.answer()).isNull();
		});
		assertThat(verdict.feedback()).isEqualTo("- searched: a required search was skipped");
		assertThat(this.model.lastPrompt().getQuestions()).containsOnlyKeys("is_plausible");
	}

	@Test
	void passesACodeCheckThatHolds() {
		respondWith(PLAUSIBLE_ONLY);

		DecisionVerdict verdict = searchJudge().judge(DecisionJudgeInput.builder()
			.question("Weather in Dublin?")
			.answer("Rain, 12C.")
			.field("search_required", true)
			.toolCall(new DecisionJudgeInput.ToolCall("web_search", "{\"q\":\"Dublin\"}", "Rain, 12C"))
			.build());

		assertThat(verdict.passed()).isTrue();
		assertThat(verdict.findings()).extracting(DecisionFinding::outcome)
			.containsExactly(DecisionFinding.Outcome.PASSED, DecisionFinding.Outcome.PASSED);
	}

	@Test
	void letsACheckThatThrowsPropagateWithoutSpendingACall() {
		// A throwing check is a bug in the check, not a verdict on the answer.
		DecisionJudge judge = DecisionJudge.builder(this.model).check("broken", input -> {
			throw new IllegalStateException("boom");
		}, "never").yesNo("is_plausible", PLAUSIBLE, 0.7d).build();

		assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> judge.judge("q", "a"))
			.withMessage("boom");
		assertThat(this.model.prompts()).isEmpty();
	}

	@Test
	void readsArbitraryFieldsThroughTheInputForCodeChecks() {
		respondWith(PLAUSIBLE_ONLY);

		DecisionVerdict verdict = searchJudge().judge(DecisionInput.fields(Map.of("search_required", false)));

		assertThat(verdict.passed()).isTrue();
	}

	@Test
	void rejectsAnInputWithoutFieldsWhenTheJudgeHasCodeChecks() {
		assertThatIllegalArgumentException().isThrownBy(() -> searchJudge().judge(DecisionInput.text("plain text")))
			.withMessageContaining("must be a FieldsInput");
	}

	@Test
	void judgesAnInputWithoutFieldsWhenNoCriterionReadsIt() {
		respondWith(PLAUSIBLE_ONLY);

		DecisionJudge judge = DecisionJudge.builder(this.model).yesNo("is_plausible", PLAUSIBLE, 0.7d).build();
		DecisionInput passage = DecisionInput.text("It is 15 degrees Celsius in Paris.");

		assertThat(judge.judge(passage).passed()).isTrue();
		assertThat(this.model.lastPrompt().getInstructions()).isSameAs(passage);
	}

	@Test
	void sendsItsOptionsWithEveryCall() {
		respondWith(PLAUSIBLE_ONLY);
		respondWith(PLAUSIBLE_ONLY);
		DecisionOptions options = DecisionOptions.builder().model("judge-model").build();

		DecisionJudge.builder(this.model)
			.yesNo("is_plausible", PLAUSIBLE, 0.7d)
			.options(options)
			.build()
			.judge("q", "a");
		DecisionJudge.builder(this.model).yesNo("is_plausible", PLAUSIBLE, 0.7d).build().judge("q", "a");

		assertThat(this.model.prompts().get(0).getOptions()).isSameAs(options);
		assertThat(this.model.prompts().get(1).getOptions()).isNull();
	}

	@Test
	void rejectsAJudgeOfCodeChecksAlone() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> DecisionJudge.builder(this.model).check("always", input -> true, "never").build())
			.withMessageContaining("at least one question criterion");
	}

	@Test
	void rejectsACheckNamedLikeAQuestion() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> DecisionJudge.builder(this.model)
				.yesNo("is_plausible", PLAUSIBLE, 0.7d)
				.check("is_plausible", input -> true, "never"))
			.withMessageContaining("already declared");
	}

	@Test
	void sendsEveryTypedInputFieldUnderItsDocumentedName() {
		respondWith(PLAUSIBLE_ONLY);

		DecisionJudge.builder(this.model)
			.yesNo("is_plausible", PLAUSIBLE, 0.7d)
			.build()
			.judge(DecisionJudgeInput.builder()
				.question("Weather in Paris?")
				.answer("15C")
				.expected(Map.of("search_required", true))
				.context("Paris is mild in spring.")
				.toolCall(new DecisionJudgeInput.ToolCall("currentWeather", "{}", "15"))
				.build());

		assertThat(this.model.lastPrompt().getInstructions()).isInstanceOfSatisfying(FieldsInput.class,
				input -> assertThat(input.getFields()).containsExactly(Map.entry("user_question", "Weather in Paris?"),
						Map.entry("assistant_answer", "15C"),
						Map.entry("expected_output", Map.of("search_required", true)),
						Map.entry("supporting_context", List.of("Paris is mild in spring.")), Map.entry("tool_calls",
								List.of(Map.of("name", "currentWeather", "arguments", "{}", "result", "15")))));
	}

	@Test
	void passesAScoreSplitBetweenTwoPassingLevelsDespiteLowConfidence() {
		// Recorded live: 93% of the mass sits on passing levels, but it is split between
		// "Mostly helpful" and "Excellent", so the distribution's own confidence is ~0.5.
		// The pass/fail decision is not in doubt, and must not be reported as undecided.
		respondWith(Map.of("helpfulness", score(2.34, 0.51, 0.0, 0.07, 0.52, 0.41), "is_plausible", yesNo(0.97)));

		DecisionVerdict verdict = judge().judge("What is the weather in Paris?", "It is 15 degrees Celsius in Paris.");

		assertThat(verdict.summary()).isEqualTo("passed=true [helpfulness=PASSED, is_plausible=PASSED]");
	}

	@Test
	void countsOnlyWholeLevelsAtOrAboveAFractionalMinimumAsPassing() {
		// minimum 2.5: level 2 is below it, so only level 3 supports a pass.
		QuestionCriterion helpfulness = DecisionCriterion.score("helpfulness", HELPFULNESS, 2.5d);
		ScoreAnswer answer = score(2.5d, 0.5d, 0.0d, 0.0d, 0.5d, 0.5d);

		assertThat(DecisionJudge.scoreSupport(helpfulness, answer, true)).isEqualTo(0.5d);
		assertThat(DecisionJudge.scoreSupport(helpfulness, answer, false)).isEqualTo(0.5d);
	}

	@Test
	void fallsBackToTheAnswersConfidenceWithoutProbabilities() {
		QuestionCriterion helpfulness = DecisionCriterion.score("helpfulness", HELPFULNESS, 2.0d);

		assertThat(DecisionJudge.scoreSupport(helpfulness, score(2.8d, 0.42d), true)).isEqualTo(0.42d);
	}

	@Test
	void sumsTheAcceptedOptionsWhenJudgingAChoice() {
		// No single option is confident, but helpful + neutral together clearly pass.
		QuestionCriterion tone = DecisionCriterion.choice("tone",
				ChoiceQuestion.of("What tone?", "helpful", "neutral", "dismissive"), "helpful", "neutral");
		ChoiceAnswer answer = choice("neutral", 0.30d,
				probabilities("helpful", 0.35d, "neutral", 0.40d, "dismissive", 0.25d));

		assertThat(DecisionJudge.choiceSupport(tone, answer, true)).isCloseTo(0.75d, within(1e-9));
	}

	@Test
	void canBeConfiguredToRejectACriterionTheModelRefusedToAnswer() {
		respondWith(Map.of("helpfulness", score(3.0, 0.9, 0.0, 0.0, 0.0, 1.0), "is_plausible", refusal(null)));

		DecisionJudge strict = DecisionJudge.builder(this.model)
			.score("helpfulness", HELPFULNESS, 2.0d)
			.yesNo("is_plausible", PLAUSIBLE, 0.7d)
			.failOnError(true)
			.build();

		DecisionVerdict verdict = strict.judge("q", "a");

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.failures()).singleElement()
			.satisfies(finding -> assertThat(finding.name()).isEqualTo("is_plausible"));
	}

	@Test
	void doesNotAskACriterionThatDoesNotApply() {
		respondWith(PLAUSIBLE_ONLY);

		DecisionJudge judge = DecisionJudge.builder(this.model)
			.criterion(DecisionCriterion.yesNo("is_grounded", PLAUSIBLE, 0.7d)
				.appliesWhen(input -> !input.context().isEmpty()))
			.yesNo("is_plausible", PLAUSIBLE, 0.7d)
			.build();

		DecisionVerdict verdict = judge.judge("q", "a");

		assertThat(verdict.passed()).isTrue();
		assertThat(verdict.summary()).isEqualTo("passed=true [is_grounded=NOT_APPLICABLE, is_plausible=PASSED]");
		assertThat(verdict.notApplicable()).singleElement().satisfies(finding -> assertThat(finding.answer()).isNull());
		assertThat(this.model.lastPrompt().getQuestions()).containsOnlyKeys("is_plausible");
	}

	@Test
	void makesNoCallWhenNoQuestionApplies() {
		DecisionJudge judge = DecisionJudge.builder(this.model)
			.criterion(DecisionCriterion.yesNo("is_grounded", PLAUSIBLE, 0.7d).appliesWhen(input -> false))
			.build();

		DecisionVerdict verdict = judge.judge("q", "a");

		assertThat(verdict.passed()).isTrue();
		assertThat(verdict.response()).isNull();
		assertThat(this.model.prompts()).isEmpty();
	}

	@Test
	void skipsTheCallWhenACodeCheckFailsUnderFailFast() {
		DecisionJudge judge = DecisionJudge.builder(this.model)
			.check("searched", input -> !input.toolCalls().isEmpty(), "no search was made")
			.yesNo("is_plausible", PLAUSIBLE, 0.7d)
			.failFast(true)
			.build();

		DecisionVerdict verdict = judge.judge("q", "a");

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.response()).isNull();
		assertThat(verdict.summary()).isEqualTo("passed=false [searched=FAILED, is_plausible=NOT_APPLICABLE]");
		assertThat(verdict.feedback()).isEqualTo("- searched: no search was made");
		assertThat(this.model.prompts()).isEmpty();
	}

	@Test
	void stillAsksTheQuestionsUnderFailFastWhenEveryCheckPasses() {
		respondWith(PLAUSIBLE_ONLY);

		DecisionJudge judge = DecisionJudge.builder(this.model)
			.check("always", input -> true, "never")
			.yesNo("is_plausible", PLAUSIBLE, 0.7d)
			.failFast(true)
			.build();

		assertThat(judge.judge("q", "a").passed()).isTrue();
		this.model.verify();
	}

	@Test
	void skipsTheDetailsCheckWhenTheAnswerAskedForClarification() {
		// The branch costs nothing: has_details is still asked in the same call, but a
		// clarifying question is not judged on details it was never meant to have.
		respondWith(modeAnd("clarification_needed", 0.05d));

		DecisionVerdict verdict = branchingJudge().judge("Weather in Springfield?", "Which Springfield do you mean?");

		assertThat(verdict.passed()).isTrue();
		assertThat(verdict.summary()).isEqualTo("passed=true [mode=PASSED, has_details=NOT_APPLICABLE]");
		assertThat(verdict.notApplicable()).singleElement()
			.satisfies(finding -> assertThat(finding.detail())
				.isEqualTo("has_details: does not apply, mode was \"clarification_needed\""));
		assertThat(this.model.lastPrompt().getQuestions()).containsOnlyKeys("mode", "has_details");
	}

	@Test
	void judgesTheDetailsCheckWhenTheAnswerAnswered() {
		respondWith(modeAnd("answered", 0.05d));

		DecisionVerdict verdict = branchingJudge().judge("Weather in Paris?", "Nice.");

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.summary()).isEqualTo("passed=false [mode=PASSED, has_details=FAILED]");
	}

	@Test
	void treatsAnUndecidedChoiceAsNotSelectingTheBranch() {
		respondWith(Map.of("mode", choice("answered", 0.1, probabilities("answered", 0.5, "clarification_needed", 0.5)),
				"has_details", yesNo(0.05)));

		DecisionJudge judge = DecisionJudge.builder(this.model)
			.choice("mode", MODE, "answered")
			.criterion(DecisionCriterion.yesNo("has_details", DETAILS, 0.7d).whenChosen("mode", "answered"))
			.build();

		DecisionVerdict verdict = judge.judge("q", "a");

		assertThat(verdict.summary()).isEqualTo("passed=true [mode=INCONCLUSIVE, has_details=NOT_APPLICABLE]");
		assertThat(verdict.notApplicable().get(0).detail())
			.isEqualTo("has_details: does not apply, mode was INCONCLUSIVE");
	}

	@Test
	void appliesOnlyAfterACodeCheckPassed() {
		respondWith(PLAUSIBLE_ONLY);

		DecisionJudge judge = DecisionJudge.builder(this.model)
			.check("searched", input -> !input.toolCalls().isEmpty(), "no search was made")
			.criterion(DecisionCriterion.yesNo("is_plausible", PLAUSIBLE, 0.7d).whenPassed("searched"))
			.build();

		DecisionVerdict verdict = judge.judge("q", "a");

		assertThat(verdict.summary()).isEqualTo("passed=false [searched=FAILED, is_plausible=NOT_APPLICABLE]");
		assertThat(verdict.feedback()).isEqualTo("- searched: no search was made");
	}

	@Test
	void doesNotTakeABranchOnACoinFlipBetweenAcceptedOptions() {
		// Both options are accepted, so "mode" passes with full support and is never
		// undecided. The branch is another matter: 0.51 against 0.49 selects nothing.
		respondWith(
				Map.of("mode", choice("answered", 0.02, probabilities("answered", 0.51, "clarification_needed", 0.49)),
						"has_details", yesNo(0.05)));

		DecisionVerdict verdict = branchingJudge().judge("Weather in Springfield?", "Which Springfield? Or: 20 C.");

		assertThat(verdict.summary()).isEqualTo("passed=true [mode=PASSED, has_details=NOT_APPLICABLE]");
		assertThat(verdict.notApplicable().get(0).detail())
			.isEqualTo("has_details: does not apply, mode did not settle on [answered] (0.51 of the probability)");
	}

	@Test
	void takesABranchOnAClearMajorityForItsLabels() {
		respondWith(Map.of("mode", choice("answered", 0.6, probabilities("answered", 0.8, "clarification_needed", 0.2)),
				"has_details", yesNo(0.05)));

		DecisionVerdict verdict = branchingJudge().judge("Weather in Paris?", "Nice.");

		assertThat(verdict.summary()).isEqualTo("passed=false [mode=PASSED, has_details=FAILED]");
	}

	@Test
	void rejectsADependencyOnACriterionNotYetDeclared() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> DecisionJudge.builder(this.model)
				.criterion(DecisionCriterion.yesNo("has_details", DETAILS, 0.7d).whenChosen("mode", "answered"))
				.choice("mode", MODE, "answered"))
			.withMessageContaining("must be declared before it");
	}

	@Test
	void rejectsWhenChosenOnSomethingOtherThanAChoice() {
		assertThatIllegalArgumentException()
			.isThrownBy(
					() -> DecisionJudge.builder(this.model)
						.yesNo("is_plausible", PLAUSIBLE, 0.7d)
						.criterion(DecisionCriterion.yesNo("has_details", DETAILS, 0.7d)
							.whenChosen("is_plausible", "answered")))
			.withMessageContaining("not a choice");
	}

	@Test
	void rejectsAWhenChosenLabelTheChoiceDoesNotOffer() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> DecisionJudge.builder(this.model)
				.choice("mode", MODE, "answered")
				.criterion(DecisionCriterion.yesNo("has_details", DETAILS, 0.7d).whenChosen("mode", "refused")))
			.withMessageContaining("'refused' is not one of the options");
	}

	@Test
	void neverNamesThePassingLevelAsTheLevelAFailingScoreReached() {
		// 1.8 rounds to level 2, the level required; the feedback must not claim it was
		// reached.
		respondWith(Map.of("helpfulness", score(1.8, 0.7, 0.0, 0.7, 0.0, 0.3), "is_plausible", yesNo(0.97)));

		DecisionVerdict verdict = judge().judge("q", "a");

		assertThat(verdict.feedback()).contains(
				"helpfulness: rated \"Mostly unhelpful: misses the main point\" (1.80), needs to reach 2.00 which is \"Mostly helpful: minor gaps remain\"");
	}

	@Test
	void decidesAScoreFromWhereItsProbabilityLiesRatherThanItsExpectedValue() {
		// Expected value 1.8 is below 2, but 60% of the probability is on a passing
		// level: pass/fail and its support are decided from the same split, so this
		// passes.
		respondWith(Map.of("helpfulness", score(1.8, 0.6, 0.4, 0.0, 0.0, 0.6), "is_plausible", yesNo(0.97)));

		assertThat(judge().judge("q", "a").summary())
			.isEqualTo("passed=true [helpfulness=PASSED, is_plausible=PASSED]");
	}

	@Test
	void passesAScoreAtTheRequiredLevelDespiteASmallTailBelowIt() {
		// 90% on "Mostly helpful" and 10% on the level below. The expected value, 1.9, is
		// under the minimum of 2, yet the answer was rated at the required level.
		respondWith(Map.of("helpfulness", score(1.9, 0.9, 0.0, 0.1, 0.9, 0.0), "is_plausible", yesNo(0.97)));

		assertThat(judge().judge("q", "a").summary())
			.isEqualTo("passed=true [helpfulness=PASSED, is_plausible=PASSED]");
	}

	@Test
	void countsAFractionalMinimumFromTheNextWholeLevelAndSaysSoInTheFeedback() {
		// minimum 1.2: level 1 is below it, so levels 2 and up pass. 70% on level 1
		// fails, and the feedback quotes the level to reach, never "1.30, needs to reach
		// 1.20".
		this.model.respondWith(Map.of("helpfulness", score(1.30, 0.9, 0.0, 0.7, 0.3, 0.0)));

		DecisionVerdict verdict = DecisionJudge.builder(this.model)
			.score("helpfulness", HELPFULNESS, 1.2d)
			.build()
			.judge("q", "a");

		assertThat(verdict.failures()).singleElement()
			.satisfies(finding -> assertThat(finding.detail())
				.isEqualTo("helpfulness: rated \"Mostly unhelpful: misses the main point\" (1.30), "
						+ "needs to reach 2.00 which is \"Mostly helpful: minor gaps remain\""));
	}

	@Test
	void explainsAFailureByProbabilityWhenTheExpectedValueReachesTheLevel() {
		// 65% on the lowest level and 35% on the top one. The expected value, 1.05, is
		// over the minimum of 1, but most of the probability is below it. "Rated 1.05,
		// needs to reach 1.00" would contradict itself.
		this.model.respondWith(Map.of("helpfulness", score(1.05, 0.5, 0.65, 0.0, 0.0, 0.35)));

		DecisionVerdict verdict = DecisionJudge.builder(this.model)
			.score("helpfulness", HELPFULNESS, 1.0d)
			.build()
			.judge("q", "a");

		assertThat(verdict.failures()).singleElement()
			.satisfies(
					finding -> assertThat(finding.detail()).isEqualTo("helpfulness: 0.65 of the probability is below "
							+ "\"Mostly unhelpful: misses the main point\", the level it needs to reach"));
	}

	@Test
	void decidesAScoreByItsValueWhenTheProviderReportsNoProbabilities() {
		QuestionCriterion helpfulness = DecisionCriterion.score("helpfulness", HELPFULNESS, 1.2d);

		assertThat(DecisionJudge.scorePasses(helpfulness, score(1.30d, 0.9d))).isTrue();
		assertThat(DecisionJudge.scorePasses(helpfulness, score(1.10d, 0.9d))).isFalse();
	}

	@Test
	void passesAChoiceWhoseAcceptedOptionsTogetherOutweighTheTopLabel() {
		QuestionCriterion tone = DecisionCriterion.choice("tone",
				ChoiceQuestion.of("What tone?", "helpful", "neutral", "dismissive"), "helpful", "neutral");
		ChoiceAnswer answer = choice("dismissive", 0.2d,
				probabilities("helpful", 0.3d, "neutral", 0.3d, "dismissive", 0.4d));

		assertThat(DecisionJudge.choicePasses(tone, answer)).isTrue();
		assertThat(DecisionJudge.choiceSupport(tone, answer, true)).isCloseTo(0.6d, within(1e-9));
	}

	@Test
	void doesNotFollowAnUndecidedChoiceEvenWhenUndecidedCountsAsFailed() {
		respondWith(Map.of("mode", choice("answered", 0.1, probabilities("answered", 0.5, "clarification_needed", 0.5)),
				"has_details", yesNo(0.05)));

		DecisionJudge judge = DecisionJudge.builder(this.model)
			.choice("mode", MODE, "answered")
			.criterion(DecisionCriterion.yesNo("has_details", DETAILS, 0.7d).whenChosen("mode", "answered"))
			.failOnInconclusive(true)
			.build();

		DecisionVerdict verdict = judge.judge("q", "a");

		assertThat(verdict.summary()).isEqualTo("passed=false [mode=FAILED, has_details=NOT_APPLICABLE]");
		assertThat(verdict.feedback()).doesNotContain("has_details");
	}

	@Test
	void validatesACriterionBuiltDirectlyAsStrictlyAsTheFactories() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new QuestionCriterion("helpfulness", HELPFULNESS, 7.0d, java.util.Set.of()))
			.withMessageContaining("highest level");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new QuestionCriterion("tone", MODE, 0.0d, java.util.Set.of()))
			.withMessageContaining("at least one option");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new QuestionCriterion("tone", MODE, 0.0d, java.util.Set.of("refused")))
			.withMessageContaining("not one of the choice's options");
	}

	@Test
	void rejectsASecondDependencyRatherThanReplacingTheFirst() {
		assertThatIllegalStateException()
			.isThrownBy(() -> DecisionCriterion.yesNo("has_details", DETAILS, 0.7d)
				.whenChosen("mode", "answered")
				.whenPassed("searched"))
			.withMessageContaining("already depends on 'mode'");
	}

	@Test
	void actsOnAScoreThatReportsNeitherProbabilitiesNorAConfidence() {
		// Nothing to gate on, so the value decides and the verdict is not INCONCLUSIVE.
		respondWith(Map.of("helpfulness", score(3.0), "is_plausible", yesNo(0.97)));
		assertThat(judge().judge("q", "a").summary())
			.isEqualTo("passed=true [helpfulness=PASSED, is_plausible=PASSED]");

		respondWith(Map.of("helpfulness", score(1.0), "is_plausible", yesNo(0.97)));
		assertThat(judge().judge("q", "a").summary())
			.isEqualTo("passed=false [helpfulness=FAILED, is_plausible=PASSED]");
	}

	@Test
	void actsOnAChoiceThatReportsNeitherProbabilitiesNorAConfidence() {
		respondWith(Map.of("mode", choice("clarification_needed", null)));

		DecisionJudge judge = DecisionJudge.builder(this.model).choice("mode", MODE, "answered").build();

		assertThat(judge.judge("q", "a").feedback())
			.isEqualTo("- mode: selected \"clarification_needed\", which is outside [answered]");
	}

	@Test
	void fallsBackToAChoicesConfidenceWithoutProbabilities() {
		respondWith(Map.of("mode", choice("answered", 0.3)));

		DecisionJudge judge = DecisionJudge.builder(this.model).choice("mode", MODE, "answered").build();

		assertThat(judge.judge("q", "a").summary()).isEqualTo("passed=true [mode=INCONCLUSIVE]");
	}

	private DecisionJudge branchingJudge() {
		return DecisionJudge.builder(this.model)
			.choice("mode", MODE, "answered", "clarification_needed")
			.criterion(DecisionCriterion.yesNo("has_details", DETAILS, 0.7d).whenChosen("mode", "answered"))
			.build();
	}

	private static Map<String, DecisionAnswer> modeAnd(String mode, double details) {
		return Map.of("mode", choice(mode, 0.9, probabilities(mode, 0.9)), "has_details", yesNo(details));
	}

	private DecisionJudge searchJudge() {
		return DecisionJudge.builder(this.model)
			.check("searched",
					input -> input.toolCalls().isEmpty() != Boolean.TRUE
						.equals(input.field("search_required", Boolean.class)),
					"a required search was skipped")
			.yesNo("is_plausible", PLAUSIBLE, 0.7d)
			.build();
	}

	private DecisionJudge judge() {
		return DecisionJudge.builder(this.model)
			.score("helpfulness", HELPFULNESS, 2.0d)
			.yesNo("is_plausible", PLAUSIBLE, 0.7d)
			.build();
	}

	private void respondWith(Map<String, ? extends DecisionAnswer> answers) {
		this.model.respondWith(answers);
	}

}
