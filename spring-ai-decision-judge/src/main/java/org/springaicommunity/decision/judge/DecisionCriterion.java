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

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.decision.question.ChoiceQuestion;
import org.springframework.ai.decision.question.DecisionQuestion;
import org.springframework.ai.decision.question.ScoreQuestion;
import org.springframework.ai.decision.question.YesNoQuestion;
import org.springframework.util.Assert;

/**
 * One atomic thing a {@link DecisionJudge} checks, and what counts as passing it.
 *
 * <p>
 * A criterion is either a {@link QuestionCriterion question} put to the decision model or
 * a {@link CodeCriterion check} answered by plain Java. Both land as a
 * {@link DecisionFinding} in the same verdict, so a deterministic check fails the
 * verdict, reaches the feedback and shows up in the summary exactly like a question does.
 *
 * <p>
 * Prefer a check whenever the answer is already in the input: whether a tool was called,
 * whether the answer parses, whether it matches the expected output. Asking a model a
 * question code can settle only adds variance to something that has none.
 *
 * @author Christian Tzolov
 */
public sealed interface DecisionCriterion permits DecisionCriterion.QuestionCriterion, DecisionCriterion.CodeCriterion {

	/**
	 * @return the name the finding will carry
	 */
	String name();

	/**
	 * A yes/no question that passes when the probability of yes reaches {@code minimum}.
	 * @param name the name the answer will carry
	 * @param question the question
	 * @param minimum the inclusive lower bound, between {@code 0} and {@code 1}
	 * @return the criterion
	 */
	static QuestionCriterion yesNo(String name, YesNoQuestion question, double minimum) {
		return new QuestionCriterion(name, question, minimum, Set.of());
	}

	/**
	 * A score that passes when it reaches {@code minimum}: when at least half of its
	 * probability is on the levels at or above it. The levels are whole numbers, so a
	 * fractional minimum counts from the next whole level; {@code 1.2} asks for level 2
	 * or better. With a provider that reports no probabilities, the score's value must be
	 * at least the minimum instead.
	 * @param name the name the answer will carry
	 * @param score the question
	 * @param minimum the level to reach, from {@code 0} to the top level of the rubric
	 * @return the criterion
	 */
	static QuestionCriterion score(String name, ScoreQuestion score, double minimum) {
		return new QuestionCriterion(name, score, minimum, Set.of());
	}

	/**
	 * A choice that passes when the selected label is one of {@code acceptedOptions}.
	 * @param name the name the answer will carry
	 * @param choice the question
	 * @param acceptedOptions the labels that count as passing
	 * @return the criterion
	 */
	static QuestionCriterion choice(String name, ChoiceQuestion choice, String... acceptedOptions) {
		Assert.notEmpty(acceptedOptions, "acceptedOptions must name at least one option");
		Assert.noNullElements(acceptedOptions, "acceptedOptions must not contain a null option");
		// Arrays.asList, not Set.of: Set.of rejects a repeated label outright and its
		// iteration order is salted per JVM run, which would make the feedback text
		// differ between runs. A duplicate here is harmless and simply collapses.
		return new QuestionCriterion(name, choice, 0.0d, new LinkedHashSet<>(Arrays.asList(acceptedOptions)));
	}

	/**
	 * A check answered in code rather than by the decision model.
	 * @param name the name the finding will carry
	 * @param check passes when it returns {@code true}
	 * @param defect what went wrong when it returns {@code false}, handed back as
	 * feedback
	 * @return the criterion
	 */
	static CodeCriterion check(String name, Predicate<DecisionJudgeInput> check, String defect) {
		return new CodeCriterion(name, check, defect);
	}

	/**
	 * A question put to the decision model, plus its pass condition.
	 *
	 * <p>
	 * The pass condition depends on the kind of question, because the kinds answer
	 * differently: a yes/no question is thresholded on the probability of yes, a score on
	 * how far up the rubric it lands, and a choice on whether the selected option is one
	 * the caller accepts.
	 *
	 * @param name the name the answer will carry
	 * @param question the question to ask
	 * @param minimum the inclusive lower bound for the probability of yes or for a score;
	 * unused for a choice
	 * @param acceptedOptions the options that count as passing a choice; empty for the
	 * other kinds
	 * @param appliesWhen when the criterion applies, judged on the input before the call;
	 * {@code null} for always. When it returns {@code false} the question is not sent and
	 * the finding is {@link DecisionFinding.Outcome#NOT_APPLICABLE}
	 * @param dependsOn another criterion this one only applies after, judged on that
	 * criterion's finding once the call has returned; {@code null} for none
	 */
	record QuestionCriterion(String name, DecisionQuestion question, double minimum, Set<String> acceptedOptions,
			@Nullable Predicate<DecisionJudgeInput> appliesWhen,
			@Nullable Dependency dependsOn) implements DecisionCriterion {

		public QuestionCriterion {
			Assert.hasText(name, "name must not be empty");
			Assert.notNull(question, "question must not be null");
			// An ordered, unmodifiable copy: Set.copyOf would discard declaration order,
			// and the option list is quoted back to the model in the failure feedback.
			acceptedOptions = acceptedOptions == null ? Set.of()
					: Collections.unmodifiableSet(new LinkedHashSet<>(acceptedOptions));
			// Validated here rather than in the factories, so a criterion built directly
			// cannot be one that never passes.
			if (question instanceof YesNoQuestion) {
				Assert.isTrue(minimum >= 0.0d && minimum <= 1.0d,
						"minimum must be between 0 and 1 for a yes/no question");
				Assert.isTrue(acceptedOptions.isEmpty(), "acceptedOptions only apply to a choice");
			}
			else if (question instanceof ScoreQuestion score) {
				int maxLevel = score.getLevels().size() - 1;
				Assert.isTrue(minimum >= 0.0d && minimum <= maxLevel,
						"minimum must be between 0 and the rubric's highest level (" + maxLevel + ")");
				Assert.isTrue(acceptedOptions.isEmpty(), "acceptedOptions only apply to a choice");
			}
			else if (question instanceof ChoiceQuestion choice) {
				Assert.notEmpty(acceptedOptions, "acceptedOptions must name at least one option");
				Assert.noNullElements(acceptedOptions.toArray(), "acceptedOptions must not contain a null option");
				acceptedOptions
					.forEach(option -> Assert.isTrue(choice.getOptions().containsKey(option), "accepted option '"
							+ option + "' is not one of the choice's options " + choice.getOptionNames()));
			}
		}

		/**
		 * A criterion that always applies.
		 */
		public QuestionCriterion(String name, DecisionQuestion question, double minimum, Set<String> acceptedOptions) {
			this(name, question, minimum, acceptedOptions, null, null);
		}

		/**
		 * Returns a copy that is only asked when {@code appliesWhen} holds, for example a
		 * groundedness question only when there is context to be grounded in:
		 *
		 * <pre>{@code
		 * DecisionCriterion.yesNo("is_grounded", grounded, 0.7d)
		 *     .appliesWhen(input -> !input.context().isEmpty())
		 * }</pre>
		 * @param appliesWhen when the criterion applies
		 * @return the copy
		 */
		public QuestionCriterion appliesWhen(Predicate<DecisionJudgeInput> appliesWhen) {
			Assert.notNull(appliesWhen, "appliesWhen must not be null");
			return new QuestionCriterion(this.name, this.question, this.minimum, this.acceptedOptions, appliesWhen,
					this.dependsOn);
		}

		/**
		 * Returns a copy that only applies when another criterion passed. The question is
		 * still asked — it rides in the same call — but when {@code criterion} did not
		 * pass, this finding is {@link DecisionFinding.Outcome#NOT_APPLICABLE} rather
		 * than judged.
		 * @param criterion the name of a criterion declared earlier in the judge
		 * @return the copy
		 */
		public QuestionCriterion whenPassed(String criterion) {
			Assert.state(this.dependsOn == null, () -> "criterion '" + this.name + "' already depends on '"
					+ this.dependsOn.criterion() + "'; a criterion can depend on one other criterion only");
			return new QuestionCriterion(this.name, this.question, this.minimum, this.acceptedOptions, this.appliesWhen,
					new Dependency(criterion, Set.of()));
		}

		/**
		 * Returns a copy that only applies when a choice criterion selected one of
		 * {@code labels}. This is how a rubric branches:
		 *
		 * <pre>{@code
		 * .choice("mode", modeChoice, "answered", "clarification_needed")
		 * .criterion(DecisionCriterion.yesNo("has_details", detailsQuestion, 0.7d)
		 *     .whenChosen("mode", "answered"))
		 * }</pre>
		 *
		 * Every question is still answered in the one call, so the branch costs nothing.
		 * The branch is taken when at least half of the choice's probability, and at
		 * least the judge's {@code minConfidence}, is on the named labels; when the
		 * choice lands elsewhere, does not settle, or is not decided, this finding is
		 * {@link DecisionFinding.Outcome#NOT_APPLICABLE}.
		 * @param choiceCriterion the name of a choice criterion declared earlier in the
		 * judge
		 * @param labels the options that make this criterion apply
		 * @return the copy
		 */
		public QuestionCriterion whenChosen(String choiceCriterion, String... labels) {
			Assert.state(this.dependsOn == null, () -> "criterion '" + this.name + "' already depends on '"
					+ this.dependsOn.criterion() + "'; a criterion can depend on one other criterion only");
			Assert.notEmpty(labels, "labels must name at least one option");
			Assert.noNullElements(labels, "labels must not contain null");
			return new QuestionCriterion(this.name, this.question, this.minimum, this.acceptedOptions, this.appliesWhen,
					new Dependency(choiceCriterion, new LinkedHashSet<>(Arrays.asList(labels))));
		}

	}

	/**
	 * What one criterion needs from another before it applies.
	 *
	 * @param criterion the name of the criterion depended on
	 * @param chosen the labels that criterion's choice must have selected; empty to
	 * require only that it passed
	 */
	record Dependency(String criterion, Set<String> chosen) {

		public Dependency {
			Assert.hasText(criterion, "criterion must not be empty");
			chosen = chosen == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(chosen));
		}

	}

	/**
	 * A check answered by plain Java against the judged input. It never reaches the
	 * model, costs nothing and answers the same way every time.
	 *
	 * <p>
	 * An exception thrown by the check is a bug in the check, not a verdict on the
	 * answer, so it propagates out of {@link DecisionJudge#judge} rather than being
	 * reported as a failure.
	 *
	 * @param name the name the finding will carry
	 * @param check passes when it returns {@code true}
	 * @param defect what went wrong when it returns {@code false}, handed back as
	 * feedback
	 */
	record CodeCriterion(String name, Predicate<DecisionJudgeInput> check, String defect) implements DecisionCriterion {

		public CodeCriterion {
			Assert.hasText(name, "name must not be empty");
			Assert.notNull(check, "check must not be null");
			Assert.hasText(defect, "defect must not be empty");
		}

	}

}
