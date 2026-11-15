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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.decision.judge.DecisionCriterion.CodeCriterion;
import org.springaicommunity.decision.judge.DecisionCriterion.QuestionCriterion;

import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.decision.DecisionOptions;
import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.answer.ChoiceAnswer;
import org.springframework.ai.decision.answer.DecisionAnswer;
import org.springframework.ai.decision.answer.RefusalAnswer;
import org.springframework.ai.decision.answer.ScoreAnswer;
import org.springframework.ai.decision.answer.YesNoAnswer;
import org.springframework.ai.decision.input.DecisionInput;
import org.springframework.ai.decision.input.FieldsInput;
import org.springframework.ai.decision.question.ChoiceQuestion;
import org.springframework.ai.decision.question.DecisionQuestion;
import org.springframework.ai.decision.question.ScoreLevel;
import org.springframework.ai.decision.question.ScoreQuestion;
import org.springframework.ai.decision.question.YesNoQuestion;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * A Model-as-a-judge built out of atomic questions rather than one rubric prompt.
 *
 * <p>
 * Every criterion is answered against the same input in a single call to a
 * {@link DecisionModel}, so relevance, groundedness and plausibility can each be asked
 * and thresholded separately instead of being collapsed into one number by a judge model.
 * Composing the pass condition stays in Java, where it can be read and tested.
 *
 * <p>
 * A criterion whose answer is already in the input — was a tool called, does the answer
 * parse — is better declared as a {@link Builder#check code check} than asked of the
 * model. Checks run locally before the call and land in the same verdict as the
 * questions.
 *
 * <pre>{@code
 * DecisionJudge judge = DecisionJudge.builder(decisionModel)
 *     .score("helpfulness", ScoreQuestion.builder()
 *             .text("How well does `assistant_answer` address `user_question`?")
 *             .level("Terrible: irrelevant or off-topic")
 *             .level("Mostly unhelpful: misses the main point")
 *             .level("Mostly helpful: minor gaps")
 *             .level("Excellent: fully and correctly addressed")
 *             .build(), 2.0)
 *     .yesNo("is_plausible",
 *             YesNoQuestion.builder()
 *                 .text("Are the values in `assistant_answer` physically plausible?")
 *                 .whenNo("Contains an impossible or absurd value")
 *                 .build(),
 *             0.7)
 *     .build();
 *
 * DecisionVerdict verdict = judge.judge(question, answer);
 * }</pre>
 *
 * @author Christian Tzolov
 */
public class DecisionJudge {

	/**
	 * The default {@code minConfidence}: a clear majority, 60%, of a score's or choice's
	 * probability has to support the verdict before it is acted on. A coin flip between
	 * pass and fail supports it at 50%.
	 */
	public static final double DEFAULT_MIN_CONFIDENCE = 0.6d;

	/**
	 * The input field carrying what was asked; see
	 * {@link DecisionJudgeInput#QUESTION_FIELD}.
	 */
	public static final String QUESTION_FIELD = DecisionJudgeInput.QUESTION_FIELD;

	/**
	 * The input field carrying what the model replied; see
	 * {@link DecisionJudgeInput#ANSWER_FIELD}.
	 */
	public static final String ANSWER_FIELD = DecisionJudgeInput.ANSWER_FIELD;

	private final DecisionModel decisionModel;

	private final @Nullable DecisionOptions options;

	private final List<DecisionCriterion> criteria;

	private final double minConfidence;

	private final boolean failOnInconclusive;

	private final boolean failOnError;

	private final boolean failFast;

	private final Function<List<DecisionFinding>, String> feedbackRenderer;

	/** Whether any criterion reads the input, so a raw input must be wrapped for it. */
	private final boolean needsInput;

	private DecisionJudge(DecisionModel decisionModel, @Nullable DecisionOptions options,
			List<DecisionCriterion> criteria, double minConfidence, boolean failOnInconclusive, boolean failOnError,
			boolean failFast, Function<List<DecisionFinding>, String> feedbackRenderer) {
		this.decisionModel = decisionModel;
		this.options = options;
		this.criteria = List.copyOf(criteria);
		this.minConfidence = minConfidence;
		this.failOnInconclusive = failOnInconclusive;
		this.failOnError = failOnError;
		this.failFast = failFast;
		this.feedbackRenderer = feedbackRenderer;
		this.needsInput = this.criteria.stream()
			.anyMatch(criterion -> criterion instanceof CodeCriterion
					|| (criterion instanceof QuestionCriterion question && question.appliesWhen() != null));
	}

	/**
	 * Judges an answer against the question that produced it.
	 * @param question what was asked, including any system instructions and history
	 * @param answer what the model replied
	 * @return the verdict
	 */
	public DecisionVerdict judge(String question, String answer) {
		return judge(DecisionJudgeInput.builder().question(question).answer(answer).build());
	}

	/**
	 * Judges a typed input. Its fields become the judged input under the names
	 * {@link DecisionJudgeInput} documents, and code checks read it directly.
	 * @param input the input to judge
	 * @return the verdict
	 */
	public DecisionVerdict judge(DecisionJudgeInput input) {
		Assert.notNull(input, "input must not be null");
		return judge(input.toInput(), input);
	}

	/**
	 * Judges an arbitrary input. Use this when the thing under judgement is not a
	 * question and answer pair, for example a retrieved passage, a tool result or a
	 * conversation. The input is sent as given; code checks and {@code appliesWhen}
	 * predicates, if the judge has any, read it through
	 * {@link DecisionJudgeInput#of(FieldsInput)}, which requires a {@link FieldsInput}.
	 * @param input the content to evaluate
	 * @return the verdict
	 * @throws IllegalArgumentException when a criterion reads the input and it is not a
	 * {@link FieldsInput}
	 */
	public DecisionVerdict judge(DecisionInput input) {
		Assert.notNull(input, "input must not be null");
		if (!this.needsInput) {
			return judge(input, null);
		}
		Assert.isTrue(input instanceof FieldsInput,
				"the judged input must be a FieldsInput for code checks and appliesWhen predicates to read it");
		return judge(input, DecisionJudgeInput.of((FieldsInput) input));
	}

	private DecisionVerdict judge(DecisionInput input, @Nullable DecisionJudgeInput judgeInput) {
		// Checks run before the call: they are free, and a check that throws is a bug
		// that should surface without first spending a request.
		Map<String, DecisionFinding> decided = new LinkedHashMap<>();
		for (DecisionCriterion criterion : this.criteria) {
			if (criterion instanceof CodeCriterion code) {
				Assert.state(judgeInput != null, "code criteria need an input to read");
				decided.put(code.name(), evaluateCheck(code, judgeInput));
			}
		}
		boolean checkFailed = decided.values().stream().anyMatch(DecisionFinding::isFailure);

		// Questions that need no asking are settled here too, so they never reach the
		// model.
		Map<String, DecisionQuestion> questions = new LinkedHashMap<>();
		for (DecisionCriterion criterion : this.criteria) {
			if (criterion instanceof QuestionCriterion question) {
				if (this.failFast && checkFailed) {
					decided.put(question.name(), notApplicable(question, "skipped, a code check already failed"));
				}
				else if (question.appliesWhen() != null && !question.appliesWhen().test(requireInput(judgeInput))) {
					decided.put(question.name(), notApplicable(question, "does not apply to this input"));
				}
				else {
					questions.put(question.name(), question.question());
				}
			}
		}

		DecisionResponse response = questions.isEmpty() ? null : this.decisionModel
			.call(DecisionPrompt.builder().input(input).questions(questions).options(this.options).build());

		// Declaration order: a dependency is always declared before its dependents, so
		// its finding is final by the time a dependent reads it.
		// Criteria whose answer did not settle the question, recorded before
		// failOnInconclusive or failOnError turn them into FAILED: a dependent must not
		// follow an undecided choice.
		Set<String> undecided = new HashSet<>();
		Map<String, DecisionFinding> byName = new LinkedHashMap<>();
		for (DecisionCriterion criterion : this.criteria) {
			DecisionFinding finding = decided.get(criterion.name());
			if (finding == null) {
				Assert.state(response != null, "a question was asked, so there is a response");
				// Read through the map rather than getAnswer(), which throws. A partial
				// response should degrade that one criterion, not abort the caller's
				// whole chat call.
				finding = evaluate((QuestionCriterion) criterion, response.getAnswers().get(criterion.name()),
						undecided);
			}
			if (criterion instanceof QuestionCriterion question && question.dependsOn() != null
					&& finding.outcome() != DecisionFinding.Outcome.NOT_APPLICABLE) {
				DecisionFinding target = byName.get(question.dependsOn().criterion());
				Assert.state(target != null, "a dependency is declared before its dependents");
				String unmet = unmetDependency(question.dependsOn(), target,
						undecided.contains(question.dependsOn().criterion()));
				if (unmet != null) {
					finding = notApplicable(question, "does not apply, " + unmet);
				}
			}
			byName.put(criterion.name(), finding);
		}
		List<DecisionFinding> findings = List.copyOf(byName.values());

		boolean passed = findings.stream().noneMatch(DecisionFinding::isFailure);
		String feedback = passed ? "" : this.feedbackRenderer.apply(findings);

		return new DecisionVerdict(passed, findings, response, feedback);
	}

	/**
	 * @return why the dependency is not met, or {@code null} when it is
	 */
	private @Nullable String unmetDependency(DecisionCriterion.Dependency dependency, DecisionFinding target,
			boolean targetUndecided) {
		if (dependency.chosen().isEmpty()) {
			return target.outcome() == DecisionFinding.Outcome.PASSED ? null
					: format("%s was %s", dependency.criterion(), target.outcome());
		}
		// A choice that was not decided — inconclusive, errored, not asked — selected
		// nothing a dependent could rely on.
		boolean decided = !targetUndecided && (target.outcome() == DecisionFinding.Outcome.PASSED
				|| target.outcome() == DecisionFinding.Outcome.FAILED);
		if (!decided || !(target.answer() instanceof ChoiceAnswer choice)) {
			return format("%s was %s", dependency.criterion(), target.outcome());
		}
		// The branch follows the probability on the labels it names, by the same rule
		// that
		// decides a choice: at least half, and clearly enough. The top label alone is not
		// enough; at 0.51 against 0.49 a dependent would be judged on a coin flip.
		double share = chosenShare(dependency, choice);
		if (share >= 0.5d && share >= this.minConfidence) {
			return null;
		}
		return dependency.chosen().contains(choice.getValue()) || 1.0d - share < this.minConfidence
				? format("%s did not settle on %s (%.2f of the probability)", dependency.criterion(),
						dependency.chosen(), share)
				: format("%s was \"%s\"", dependency.criterion(), choice.getValue());
	}

	/**
	 * @return the share of the choice's probability on the labels the dependency names;
	 * without probabilities, all or nothing by the selected label
	 */
	private static double chosenShare(DecisionCriterion.Dependency dependency, ChoiceAnswer choice) {
		double total = total(choice.getProbabilities().values());
		if (total <= 0.0d) {
			return dependency.chosen().contains(choice.getValue()) ? 1.0d : 0.0d;
		}
		double chosen = 0.0d;
		for (Map.Entry<String, Double> entry : choice.getProbabilities().entrySet()) {
			if (dependency.chosen().contains(entry.getKey())) {
				chosen += entry.getValue();
			}
		}
		return chosen / total;
	}

	private static DecisionJudgeInput requireInput(@Nullable DecisionJudgeInput input) {
		Assert.state(input != null, "appliesWhen needs an input to read");
		return input;
	}

	private static DecisionFinding notApplicable(QuestionCriterion criterion, String reason) {
		return new DecisionFinding(criterion, null, DecisionFinding.Outcome.NOT_APPLICABLE,
				format("%s: %s", criterion.name(), reason));
	}

	/**
	 * @return the criteria this judge checks, in declaration order
	 */
	public List<DecisionCriterion> criteria() {
		return this.criteria;
	}

	/**
	 * Formats with {@link Locale#ROOT} rather than the ambient default. This text is fed
	 * back to the model as the defect to correct, so a comma-decimal JVM must not turn
	 * {@code "needs at least 0.70"} into {@code "needs at least 0,70"}.
	 */
	private static String format(String template, Object... args) {
		return String.format(Locale.ROOT, template, args);
	}

	private static DecisionFinding evaluateCheck(CodeCriterion criterion, DecisionJudgeInput input) {
		if (criterion.check().test(input)) {
			return new DecisionFinding(criterion, null, DecisionFinding.Outcome.PASSED, "");
		}
		return new DecisionFinding(criterion, null, DecisionFinding.Outcome.FAILED,
				format("%s: %s", criterion.name(), criterion.defect()));
	}

	private DecisionFinding evaluate(QuestionCriterion criterion, @Nullable DecisionAnswer answer,
			Set<String> undecided) {
		if (answer == null) {
			undecided.add(criterion.name());
			return error(criterion, null,
					format("%s: the model returned no answer for this criterion", criterion.name()));
		}
		if (answer instanceof RefusalAnswer refusal) {
			undecided.add(criterion.name());
			String reason = StringUtils.hasText(refusal.getReason()) ? format(" (%s)", refusal.getReason()) : "";
			return error(criterion, refusal,
					format("%s: the model refused to answer this criterion%s", criterion.name(), reason));
		}
		if (answer instanceof YesNoAnswer yesNo) {
			return evaluateYesNo(criterion, yesNo);
		}
		if (answer instanceof ScoreAnswer score) {
			return evaluateScore(criterion, score, undecided);
		}
		return evaluateChoice(criterion, (ChoiceAnswer) answer, undecided);
	}

	/**
	 * The instrument failed for this criterion, which says nothing about the answer. It
	 * blocks only when the caller chose {@code failOnError}.
	 */
	private DecisionFinding error(QuestionCriterion criterion, @Nullable DecisionAnswer answer, String detail) {
		return new DecisionFinding(criterion, answer,
				this.failOnError ? DecisionFinding.Outcome.FAILED : DecisionFinding.Outcome.ERROR, detail);
	}

	private DecisionFinding evaluateYesNo(QuestionCriterion criterion, YesNoAnswer answer) {
		if (answer.isYes(criterion.minimum())) {
			return new DecisionFinding(criterion, answer, DecisionFinding.Outcome.PASSED, "");
		}
		// Falling below the threshold means the "yes" condition did not hold, so the
		// defect is whatever the question said the "no" side looks like.
		String defect = describeNoSide(criterion);
		return new DecisionFinding(criterion, answer, DecisionFinding.Outcome.FAILED,
				format("%s: %s (scored %.2f, needs at least %.2f)", criterion.name(), defect, answer.getProbability(),
						criterion.minimum()));
	}

	private DecisionFinding evaluateScore(QuestionCriterion criterion, ScoreAnswer answer, Set<String> undecided) {
		boolean passes = scorePasses(criterion, answer);
		Double support = scoreSupport(criterion, answer, passes);
		DecisionFinding.Outcome inconclusive = checkConfidence(support);
		if (inconclusive != null) {
			undecided.add(criterion.name());
			return new DecisionFinding(criterion, answer, inconclusive, format(
					"%s: the rubric did not settle whether this reaches %.2f (%.2f of the probability supports the verdict, needs at least %.2f)",
					criterion.name(), criterion.minimum(), support, this.minConfidence));
		}
		if (passes) {
			return new DecisionFinding(criterion, answer, DecisionFinding.Outcome.PASSED, "");
		}

		int requiredLevel = (int) Math.ceil(criterion.minimum());
		String required = describeLevel(criterion, requiredLevel);
		// With probabilities the bar is the first passing level, so a fractional minimum
		// is quoted as the level it counts from. Without them it is the minimum itself.
		double bar = (total(answer.getProbabilities()) > 0.0d) ? requiredLevel : criterion.minimum();

		if (answer.getValue() >= bar) {
			// Only with probabilities: the expected value reaches the bar while most of
			// the probability sits below it. "Rated 1.05, needs to reach 1.00" would
			// contradict itself, so say what actually failed.
			String level = required.isEmpty() ? format("level %d", requiredLevel) : format("\"%s\"", required);
			return new DecisionFinding(criterion, answer, DecisionFinding.Outcome.FAILED,
					format("%s: %.2f of the probability is below %s, the level it needs to reach", criterion.name(),
							support, level));
		}

		// Describe the level the score itself lands on, not the most probable level: the
		// two can disagree on a spread distribution, and quoting a label that contradicts
		// the number would be worse than useless as feedback.
		// Clamped below the first passing level: a failing 1.8 rounds to 2, and telling
		// the model it reached the level it is asked to reach would contradict itself.
		int reachedLevel = Math.min(roundToLevel(criterion, answer.getValue()), Math.max(0, requiredLevel - 1));
		String reached = describeLevel(criterion, reachedLevel);
		String detail = format("%s: rated \"%s\" (%.2f), needs to reach %.2f", criterion.name(), reached,
				answer.getValue(), bar);
		if (!required.isEmpty()) {
			detail += format(" which is \"%s\"", required);
		}
		return new DecisionFinding(criterion, answer, DecisionFinding.Outcome.FAILED, detail);
	}

	private DecisionFinding evaluateChoice(QuestionCriterion criterion, ChoiceAnswer answer, Set<String> undecided) {
		boolean passes = choicePasses(criterion, answer);
		Double support = choiceSupport(criterion, answer, passes);
		DecisionFinding.Outcome inconclusive = checkConfidence(support);
		if (inconclusive != null) {
			undecided.add(criterion.name());
			return new DecisionFinding(criterion, answer, inconclusive, format(
					"%s: the options did not settle whether this is one of %s (%.2f of the probability supports the verdict, needs at least %.2f)",
					criterion.name(), criterion.acceptedOptions(), support, this.minConfidence));
		}
		if (passes) {
			return new DecisionFinding(criterion, answer, DecisionFinding.Outcome.PASSED, "");
		}
		if (support == null) {
			return new DecisionFinding(criterion, answer, DecisionFinding.Outcome.FAILED,
					format("%s: selected \"%s\", which is outside %s", criterion.name(), answer.getValue(),
							criterion.acceptedOptions()));
		}
		return new DecisionFinding(criterion, answer, DecisionFinding.Outcome.FAILED,
				format("%s: %.2f of the probability is on options outside %s (most likely \"%s\")", criterion.name(),
						support, criterion.acceptedOptions(), answer.getValue()));
	}

	/**
	 * Whether a score passes: when at least half of its probability sits on the levels at
	 * or above the minimum. Deciding from the same split that {@link #scoreSupport}
	 * measures keeps the verdict and its support in agreement: an answer rated at the
	 * required level passes even when a small share of the probability on the level below
	 * pulls its expected value under the minimum.
	 *
	 * <p>
	 * The levels are whole numbers, so a fractional minimum counts from the next whole
	 * level: {@code 1.2} passes on the probability of levels 2 and up.
	 *
	 * <p>
	 * Without probabilities there is no split to read, and the value decides: it passes
	 * when it is at least the minimum.
	 */
	static boolean scorePasses(QuestionCriterion criterion, ScoreAnswer answer) {
		if (total(answer.getProbabilities()) <= 0.0d) {
			return answer.getValue() >= criterion.minimum();
		}
		Double support = scoreSupport(criterion, answer, true);
		return support != null && support >= 0.5d;
	}

	/**
	 * Whether a choice passes: when at least half of its probability sits on the accepted
	 * options, even if the single most probable label is not one of them. Without
	 * probabilities, the selected label decides.
	 */
	static boolean choicePasses(QuestionCriterion criterion, ChoiceAnswer answer) {
		if (total(answer.getProbabilities().values()) <= 0.0d) {
			return criterion.acceptedOptions().contains(answer.getValue());
		}
		Double support = choiceSupport(criterion, answer, true);
		return support != null && support >= 0.5d;
	}

	private static double total(Collection<Double> probabilities) {
		return probabilities.stream().mapToDouble(Double::doubleValue).sum();
	}

	/**
	 * How much of a score's probability supports the pass or fail decision: the mass on
	 * the levels at or above the first passing level when it passes, the mass below it
	 * when it fails. That, not {@link ScoreAnswer#getConfidence()}, is what decides
	 * whether a verdict can be acted on — a distribution split between two passing levels
	 * is not peaked, yet it leaves no doubt the criterion passed.
	 * @return the support; the answer's own confidence when the provider reports no
	 * probabilities; {@code null} when it reports neither
	 */
	static @Nullable Double scoreSupport(QuestionCriterion criterion, ScoreAnswer answer, boolean passes) {
		List<Double> probabilities = answer.getProbabilities();
		double total = total(probabilities);
		if (total <= 0.0d) {
			return answer.getConfidence();
		}
		int firstPassingLevel = (int) Math.ceil(criterion.minimum());
		double passing = 0.0d;
		for (int level = firstPassingLevel; level < probabilities.size(); level++) {
			passing += probabilities.get(level);
		}
		return (passes ? passing : total - passing) / total;
	}

	/**
	 * How much of a choice's probability supports the decision: the summed mass on the
	 * accepted options when it passes, on the rest when it fails.
	 * @return the support; the answer's own confidence when the provider reports no
	 * probabilities; {@code null} when it reports neither
	 */
	static @Nullable Double choiceSupport(QuestionCriterion criterion, ChoiceAnswer answer, boolean passes) {
		double total = total(answer.getProbabilities().values());
		if (total <= 0.0d) {
			return answer.getConfidence();
		}
		double accepted = 0.0d;
		for (Map.Entry<String, Double> entry : answer.getProbabilities().entrySet()) {
			if (criterion.acceptedOptions().contains(entry.getKey())) {
				accepted += entry.getValue();
			}
		}
		return (passes ? accepted : total - accepted) / total;
	}

	/**
	 * Yes/no answers carry no confidence statistic by design, so only choices and scores
	 * can land here.
	 * @param support the probability that supports the verdict, or {@code null} when the
	 * provider reported nothing to measure it by
	 * @return the outcome to report, or {@code null} when the verdict is decisive enough
	 */
	private DecisionFinding.@Nullable Outcome checkConfidence(@Nullable Double support) {
		// Nothing to gate on: the provider reported neither probabilities nor a
		// confidence, so the answer is acted on as given.
		if (support == null || support >= this.minConfidence) {
			return null;
		}
		return this.failOnInconclusive ? DecisionFinding.Outcome.FAILED : DecisionFinding.Outcome.INCONCLUSIVE;
	}

	private String describeNoSide(QuestionCriterion criterion) {
		if (criterion.question() instanceof YesNoQuestion yesNo) {
			if (StringUtils.hasText(yesNo.getWhenNo())) {
				return yesNo.getWhenNo();
			}
			return format("the answer to \"%s\" was no", yesNo.getText());
		}
		return "the criterion was not met";
	}

	private int roundToLevel(QuestionCriterion criterion, double value) {
		int level = (int) Math.round(value);
		if (criterion.question() instanceof ScoreQuestion score) {
			return Math.max(0, Math.min(level, score.getLevels().size() - 1));
		}
		return Math.max(0, level);
	}

	/**
	 * Names a rubric level in the caller's own wording, so the feedback speaks the
	 * vocabulary the rubric was written in.
	 */
	private String describeLevel(QuestionCriterion criterion, int level) {
		if (criterion.question() instanceof ScoreQuestion score && level >= 0 && level < score.getLevels().size()) {
			ScoreLevel scoreLevel = score.getLevels().get(level);
			return StringUtils.hasText(scoreLevel.getDescription())
					? scoreLevel.getLabel() + ": " + scoreLevel.getDescription() : scoreLevel.getLabel();
		}
		return "";
	}

	/**
	 * Renders the failing and inconclusive findings as the feedback handed back to the
	 * model.
	 * @param findings every finding of the round
	 * @return the feedback text
	 */
	public static String defaultFeedback(List<DecisionFinding> findings) {
		StringBuilder feedback = new StringBuilder();
		findings.stream()
			.filter(finding -> finding.outcome() == DecisionFinding.Outcome.FAILED)
			.forEach(finding -> feedback.append("- ").append(finding.detail()).append(System.lineSeparator()));

		List<DecisionFinding> undecided = findings.stream()
			.filter(finding -> finding.outcome() == DecisionFinding.Outcome.INCONCLUSIVE)
			.toList();
		if (!undecided.isEmpty()) {
			feedback.append("The following checks were inconclusive and may need a clearer answer:")
				.append(System.lineSeparator());
			undecided.forEach(finding -> feedback.append("- ").append(finding.detail()).append(System.lineSeparator()));
		}
		return feedback.toString().stripTrailing();
	}

	/**
	 * @param decisionModel the model the questions are put to
	 * @return a new builder
	 */
	public static Builder builder(DecisionModel decisionModel) {
		return new Builder(decisionModel);
	}

	/**
	 * Builder for {@link DecisionJudge}.
	 */
	public static final class Builder {

		private final DecisionModel decisionModel;

		private @Nullable DecisionOptions options;

		private final List<DecisionCriterion> criteria = new ArrayList<>();

		private double minConfidence = DEFAULT_MIN_CONFIDENCE;

		private boolean failOnInconclusive = false;

		private boolean failOnError = false;

		private boolean failFast = false;

		private Function<List<DecisionFinding>, String> feedbackRenderer = DecisionJudge::defaultFeedback;

		private Builder(DecisionModel decisionModel) {
			Assert.notNull(decisionModel, "decisionModel must not be null");
			this.decisionModel = decisionModel;
		}

		/**
		 * Sets the options sent with every call to the model, such as the model name.
		 * @param options the options; {@code null} for the model's defaults
		 * @return this builder
		 */
		public Builder options(@Nullable DecisionOptions options) {
			this.options = options;
			return this;
		}

		/**
		 * Adds a yes/no question that passes when the probability of yes reaches
		 * {@code minimum}.
		 * @param name the name the answer will carry
		 * @param question the question
		 * @param minimum the inclusive lower bound
		 * @return this builder
		 */
		public Builder yesNo(String name, YesNoQuestion question, double minimum) {
			return criterion(DecisionCriterion.yesNo(name, question, minimum));
		}

		/**
		 * Adds a score that passes when it reaches {@code minimum}; see
		 * {@link DecisionCriterion#score} for what that means.
		 * @param name the name the answer will carry
		 * @param score the question
		 * @param minimum the level to reach
		 * @return this builder
		 */
		public Builder score(String name, ScoreQuestion score, double minimum) {
			return criterion(DecisionCriterion.score(name, score, minimum));
		}

		/**
		 * Adds a choice that passes when the selected label is accepted.
		 * @param name the name the answer will carry
		 * @param choice the question
		 * @param acceptedOptions the labels that count as passing
		 * @return this builder
		 */
		public Builder choice(String name, ChoiceQuestion choice, String... acceptedOptions) {
			return criterion(DecisionCriterion.choice(name, choice, acceptedOptions));
		}

		/**
		 * Adds a check answered in code rather than by the model. Use it for anything the
		 * input already settles — whether a tool was called, whether the answer parses —
		 * so the model is only asked what genuinely needs judgement.
		 * @param name the name the finding will carry
		 * @param check passes when it returns {@code true}
		 * @param defect what went wrong when it returns {@code false}, handed back as
		 * feedback
		 * @return this builder
		 */
		public Builder check(String name, Predicate<DecisionJudgeInput> check, String defect) {
			return criterion(DecisionCriterion.check(name, check, defect));
		}

		/**
		 * Adds a pre-built criterion of either kind.
		 * @param criterion the criterion
		 * @return this builder
		 */
		public Builder criterion(DecisionCriterion criterion) {
			Assert.notNull(criterion, "criterion must not be null");
			if (criterion instanceof QuestionCriterion question && question.dependsOn() != null) {
				validateDependency(question.name(), question.dependsOn());
			}
			Assert.isTrue(this.criteria.stream().noneMatch(existing -> existing.name().equals(criterion.name())),
					"a criterion named '" + criterion.name() + "' is already declared");
			this.criteria.add(criterion);
			return this;
		}

		private void validateDependency(String name, DecisionCriterion.Dependency dependency) {
			// Only an earlier criterion can be depended on: that rules out cycles, and it
			// is the order findings are resolved in.
			DecisionCriterion target = this.criteria.stream()
				.filter(existing -> existing.name().equals(dependency.criterion()))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("criterion '" + name + "' depends on '"
						+ dependency.criterion() + "', which must be declared before it"));
			if (!dependency.chosen().isEmpty()) {
				Assert.isTrue(
						target instanceof QuestionCriterion question && question.question() instanceof ChoiceQuestion,
						"criterion '" + name + "' uses whenChosen on '" + dependency.criterion()
								+ "', which is not a choice");
				ChoiceQuestion choice = (ChoiceQuestion) ((QuestionCriterion) target).question();
				dependency.chosen()
					.forEach(label -> Assert.isTrue(choice.getOptions().containsKey(label),
							"whenChosen label '" + label + "' is not one of the options of '" + dependency.criterion()
									+ "' " + choice.getOptionNames()));
			}
		}

		/**
		 * Sets how much of a choice's or score's probability must support its verdict
		 * before the verdict is acted on; below it the criterion is
		 * {@link DecisionFinding.Outcome#INCONCLUSIVE}. For a score that is the mass on
		 * the verdict's side of {@code minimum}, for a choice the mass on the accepted
		 * (or the rejected) options — not the answer's own {@code confidence}, which is
		 * low whenever the mass is split, even between two passing levels. Raise it for
		 * consequential decisions and lower it for reversible ones; yes/no questions are
		 * unaffected.
		 *
		 * <p>
		 * When a provider reports no probabilities, the answer's own confidence stands in
		 * for them. When it reports neither, there is nothing to gate on and the answer
		 * is acted on as given.
		 * @param minConfidence the inclusive lower bound, between {@code 0} and
		 * {@code 1}; {@link #DEFAULT_MIN_CONFIDENCE} by default
		 * @return this builder
		 */
		public Builder minConfidence(double minConfidence) {
			Assert.isTrue(minConfidence >= 0.0d && minConfidence <= 1.0d, "minConfidence must be between 0 and 1");
			this.minConfidence = minConfidence;
			return this;
		}

		/**
		 * Whether an undecided criterion blocks the response. Off by default: a flat
		 * distribution says the question did not separate well for this input, which is
		 * not the same as the answer being wrong.
		 * @param failOnInconclusive whether to treat undecided as failed
		 * @return this builder
		 */
		public Builder failOnInconclusive(boolean failOnInconclusive) {
			this.failOnInconclusive = failOnInconclusive;
			return this;
		}

		/**
		 * Whether a criterion the model did not answer blocks the response. Off by
		 * default: a missing answer or a refusal is a failure of the instrument, not of
		 * the answer, and is reported as {@link DecisionFinding.Outcome#ERROR}. Turn it
		 * on where an unjudged criterion must never pass.
		 * @param failOnError whether to treat an error as failed
		 * @return this builder
		 */
		public Builder failOnError(boolean failOnError) {
			this.failOnError = failOnError;
			return this;
		}

		/**
		 * Whether a failed code check skips the call to the model. When on and any
		 * {@link #check check} fails, the verdict already fails, so the question criteria
		 * are reported {@link DecisionFinding.Outcome#NOT_APPLICABLE} and no request is
		 * made. Off by default, so every criterion is still answered and the feedback
		 * names every defect at once.
		 * @param failFast whether to skip the call after a failed check
		 * @return this builder
		 */
		public Builder failFast(boolean failFast) {
			this.failFast = failFast;
			return this;
		}

		/**
		 * Overrides how findings are rendered into the feedback text.
		 * @param feedbackRenderer the renderer
		 * @return this builder
		 */
		public Builder feedbackRenderer(Function<List<DecisionFinding>, String> feedbackRenderer) {
			Assert.notNull(feedbackRenderer, "feedbackRenderer must not be null");
			this.feedbackRenderer = feedbackRenderer;
			return this;
		}

		public DecisionJudge build() {
			Assert.notEmpty(this.criteria, "a judge must declare at least one criterion");
			// A judge of checks alone never needs a model; it is a predicate, and would
			// leave the verdict without a response to carry.
			Assert.isTrue(this.criteria.stream().anyMatch(QuestionCriterion.class::isInstance),
					"a judge must declare at least one question criterion; plain code checks need no judge");
			return new DecisionJudge(this.decisionModel, this.options, this.criteria, this.minConfidence,
					this.failOnInconclusive, this.failOnError, this.failFast, this.feedbackRenderer);
		}

	}

}
