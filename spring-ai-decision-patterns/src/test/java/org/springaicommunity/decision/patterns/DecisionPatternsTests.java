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

package org.springaicommunity.decision.patterns;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.springaicommunity.decision.test.StubDecisionModel;

import org.springframework.ai.decision.DecisionOptions;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.answer.DecisionAnswer;
import org.springframework.ai.decision.input.FieldsInput;
import org.springframework.ai.decision.question.ChoiceQuestion;
import org.springframework.ai.decision.question.DecisionQuestion;
import org.springframework.ai.decision.question.ScoreQuestion;
import org.springframework.ai.decision.question.YesNoQuestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.springaicommunity.decision.test.StubDecisionModel.choice;
import static org.springaicommunity.decision.test.StubDecisionModel.probabilities;
import static org.springaicommunity.decision.test.StubDecisionModel.refusal;
import static org.springaicommunity.decision.test.StubDecisionModel.score;
import static org.springaicommunity.decision.test.StubDecisionModel.yesNo;

/**
 * The patterns for acting on a decision model's answers: the confidence gate, the
 * composite score and the consistency sampler.
 *
 * @author Christian Tzolov
 */
class DecisionPatternsTests {

	private static final Map<String, ScoreQuestion> TWO_SCORES = Map.of("depth",
			ScoreQuestion.of("How deep?", "none", "some", "good", "strong", "expert"), "leadership",
			ScoreQuestion.of("How much leadership?", "none", "some", "good", "strong", "expert"));

	private final StubDecisionModel model = new StubDecisionModel();

	// --- DecisionConfidenceGate
	// ----------------------------------------------------------

	@Test
	void escalatesAnythingBelowTheFloorWhateverTheAction() {
		DecisionConfidenceGate gate = DecisionConfidenceGate.builder().floor(0.6d).require("transfer", 0.85d).build();

		assertThat(gate.decide("check_balance", 0.59d)).isEqualTo(DecisionConfidenceGate.Decision.ESCALATE);
		assertThat(gate.decide("transfer", 0.59d)).isEqualTo(DecisionConfidenceGate.Decision.ESCALATE);
	}

	@Test
	void asksForConfirmationOnlyWhereTheActionDemandsMoreThanTheFloor() {
		DecisionConfidenceGate gate = DecisionConfidenceGate.builder().floor(0.6d).require("transfer", 0.85d).build();

		// Same confidence, different stakes, different answer.
		assertThat(gate.decide("check_balance", 0.7d)).isEqualTo(DecisionConfidenceGate.Decision.EXECUTE);
		assertThat(gate.decide("transfer", 0.7d)).isEqualTo(DecisionConfidenceGate.Decision.CONFIRM);
		assertThat(gate.decide("transfer", 0.86d)).isEqualTo(DecisionConfidenceGate.Decision.EXECUTE);
	}

	@Test
	void gatesDirectlyOnAChoiceAnswer() {
		this.model
			.respondWith(Map.of("intent", choice("transfer", 0.7, probabilities("transfer", 0.7, "balance", 0.3))));

		DecisionResponse response = this.model.call("move 500 to savings",
				Map.of("intent", ChoiceQuestion.of("What is the intent?", "transfer", "balance")));

		DecisionConfidenceGate gate = DecisionConfidenceGate.builder().floor(0.6d).require("transfer", 0.85d).build();
		assertThat(gate.decide(response.getChoice("intent"))).isEqualTo(DecisionConfidenceGate.Decision.CONFIRM);
	}

	@Test
	void escalatesAChoiceWhoseProviderReportsNoConfidence() {
		// Without a confidence there is nothing to grant autonomy on.
		DecisionConfidenceGate gate = DecisionConfidenceGate.withDefaultFloor();

		assertThat(gate.decide(choice("balance", null))).isEqualTo(DecisionConfidenceGate.Decision.ESCALATE);
	}

	@Test
	void rejectsAnActionThatDemandsLessThanTheFloor() {
		// Such an action could never be reached, so it is a configuration mistake.
		assertThatIllegalArgumentException()
			.isThrownBy(() -> DecisionConfidenceGate.builder().floor(0.7d).require("trivial", 0.4d).build())
			.withMessageContaining("can never reach");
	}

	// --- DecisionCompositeScore
	// ----------------------------------------------------------

	@Test
	void weighsNormalisedDimensionsIntoOneNumber() {
		this.model.respondWith(scores(4.0d, 2.0d));

		DecisionResponse response = this.model.call("anything", TWO_SCORES);

		// depth 4/4 = 1.0 weighted .75, leadership 2/4 = 0.5 weighted .25 -> 0.875
		DecisionCompositeScore composite = DecisionCompositeScore.builder()
			.weight("depth", 0.75d)
			.weight("leadership", 0.25d)
			.build();

		assertThat(composite.of(response)).isEqualTo(0.875d, Offset.offset(1e-9d));
	}

	@Test
	void theSameDimensionsRankDifferentlyUnderDifferentWeights() {
		this.model.respondWith(scores(4.0d, 0.0d));
		DecisionResponse response = this.model.call("anything", TWO_SCORES);

		DecisionCompositeScore engineering = DecisionCompositeScore.builder()
			.weight("depth", 0.9d)
			.weight("leadership", 0.1d)
			.build();
		DecisionCompositeScore management = DecisionCompositeScore.builder()
			.weight("depth", 0.1d)
			.weight("leadership", 0.9d)
			.build();

		assertThat(engineering.of(response)).isGreaterThan(management.of(response));
	}

	@Test
	void rejectsWeightsThatDoNotSumToOneUnlessToldTo() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> DecisionCompositeScore.builder().weight("a", 0.5d).weight("b", 0.2d).build())
			.withMessageContaining("must sum to 1");

		assertThat(DecisionCompositeScore.builder()
			.weight("a", 0.5d)
			.weight("b", 0.2d)
			.allowUnnormalisedWeights(true)
			.build()).isNotNull();
	}

	@Test
	void refusesToNormaliseAScoreWhoseRubricHeightIsUnknown() {
		// An answer without probabilities has no scale to divide by. Returning 0 would
		// have been a plausible-looking number that scores every candidate identically,
		// silently collapsing the ranking this class exists to produce.
		assertThatIllegalStateException().isThrownBy(() -> DecisionCompositeScore.normalise(score(3.0d)))
			.withMessageContaining("rubric height is unknown");
	}

	@Test
	void readsTheRubricHeightFromTheQuestionsWhenTheProviderReportsNoProbabilities() {
		this.model.respondWith(Map.of("depth", score(4.0d), "leadership", score(2.0d)));
		DecisionResponse response = this.model.call("anything", TWO_SCORES);

		DecisionCompositeScore composite = DecisionCompositeScore.builder()
			.weight("depth", 0.75d)
			.weight("leadership", 0.25d)
			.build();

		assertThat(composite.of(response, TWO_SCORES)).isEqualTo(0.875d, Offset.offset(1e-9d));
		assertThatIllegalStateException().isThrownBy(() -> composite.of(response));
		assertThatIllegalArgumentException().isThrownBy(() -> composite.of(response, Map.of()))
			.withMessageContaining("'depth' has no score question");
	}

	// --- DecisionConsistency
	// -------------------------------------------------------------

	@Test
	void summarisesHowTheAnswersMoveAcrossSamples() {
		AtomicInteger calls = new AtomicInteger();
		StubDecisionModel wavering = StubDecisionModel.answering(prompt -> {
			boolean even = calls.getAndIncrement() % 2 == 0;
			return Map.of("urgent", yesNo(even ? 0.4d : 0.8d), "severity", score(even ? 1.0d : 2.0d), "team",
					choice("billing", 0.9d));
		});
		Map<String, DecisionQuestion> questions = Map.of("urgent", YesNoQuestion.of("Is it urgent?"), "severity",
				ScoreQuestion.of("How severe?", "low", "medium", "high"), "team",
				ChoiceQuestion.of("Which team?", "billing", "technical"));

		// One call at a time, so the two answers alternate exactly.
		DecisionConsistency.Report report = DecisionConsistency.sample(wavering, Map.of("ticket", "Payouts fail"),
				questions, 4, DecisionConsistency.Sampling.defaults().withConcurrency(1));

		assertThat(report.samples()).hasSize(4);
		assertThat(report.failures()).isEmpty();
		// A choice has no single number to track.
		assertThat(report.statistics()).containsOnlyKeys("urgent", "severity");
		DecisionConsistency.Statistics urgent = report.statistics().get("urgent");
		assertThat(urgent.values()).containsExactly(0.4d, 0.8d, 0.4d, 0.8d);
		assertThat(urgent.mean()).isEqualTo(0.6d, Offset.offset(1e-9d));
		assertThat(urgent.standardDeviation()).isEqualTo(0.2d, Offset.offset(1e-9d));
		assertThat(urgent.range()).isEqualTo(0.4d, Offset.offset(1e-9d));
		assertThat(urgent.straddles(0.5d)).isTrue();
		assertThat(report.unstableAt(0.5d)).containsExactly("urgent");
	}

	@Test
	void givesEverySampleItsOwnUidAndOtherwiseTheSamePrompt() {
		StubDecisionModel steady = StubDecisionModel.answering(Map.of("urgent", yesNo(0.9d)));
		DecisionOptions options = DecisionOptions.builder().model("sampled-model").build();

		DecisionConsistency.sample(steady, Map.of("ticket", "Payouts fail"),
				Map.of("urgent", YesNoQuestion.of("Is it urgent?")), 3,
				DecisionConsistency.Sampling.defaults().withOptions(options));

		assertThat(steady.prompts()).hasSize(3).allSatisfy(prompt -> {
			assertThat(prompt.getOptions()).isSameAs(options);
			assertThat(prompt.getQuestions()).containsOnlyKeys("urgent");
			assertThat(((FieldsInput) prompt.getInstructions()).getFields())
				.containsOnlyKeys("ticket", DecisionConsistency.UID_FIELD)
				.containsEntry("ticket", "Payouts fail");
		});
		assertThat(steady.prompts())
			.extracting(
					prompt -> ((FieldsInput) prompt.getInstructions()).getFields().get(DecisionConsistency.UID_FIELD))
			.doesNotHaveDuplicates();
	}

	@Test
	void reportsASampleThatFailedWithoutLosingTheOthers() {
		IllegalStateException down = new IllegalStateException("provider down");
		this.model.respondWith(Map.of("urgent", yesNo(0.9d)))
			.failWith(down)
			.respondWith(Map.of("urgent", refusal("policy")));

		DecisionConsistency.Report report = DecisionConsistency.sample(this.model, Map.of("ticket", "Payouts fail"),
				Map.of("urgent", YesNoQuestion.of("Is it urgent?")), 3,
				DecisionConsistency.Sampling.defaults().withConcurrency(1).withExecutor(Runnable::run));

		assertThat(report.samples()).hasSize(2);
		assertThat(report.failures()).singleElement().satisfies(failure -> {
			assertThat(failure.index()).isEqualTo(1);
			assertThat(failure.cause()).isSameAs(down);
		});
		// The refusal carries no value, so one value is all there is to describe.
		assertThat(report.statistics().get("urgent").values()).containsExactly(0.9d);
	}

	@Test
	void reportsASampleTheExecutorRejectedWithoutLosingTheOthers() {
		this.model.respondWith(Map.of("urgent", yesNo(0.9d))).respondWith(Map.of("urgent", yesNo(0.7d)));
		RejectedExecutionException full = new RejectedExecutionException("queue full");
		AtomicInteger submissions = new AtomicInteger();
		// Runs the first and third sample in place, and turns the second one away.
		Executor rejectingTheSecond = task -> {
			if (submissions.getAndIncrement() == 1) {
				throw full;
			}
			task.run();
		};

		DecisionConsistency.Report report = DecisionConsistency.sample(this.model, Map.of("ticket", "Payouts fail"),
				Map.of("urgent", YesNoQuestion.of("Is it urgent?")), 3,
				DecisionConsistency.Sampling.defaults().withExecutor(rejectingTheSecond));

		assertThat(report.samples()).hasSize(2);
		assertThat(report.failures()).singleElement().satisfies(failure -> {
			assertThat(failure.index()).isEqualTo(1);
			assertThat(failure.cause()).isSameAs(full);
		});
		assertThat(report.statistics().get("urgent").values()).containsExactly(0.9d, 0.7d);
	}

	@Test
	void reportsEverySampleASaturatedExecutorTurnsAway() throws InterruptedException {
		CountDownLatch occupied = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		// One worker, no queue: while the worker is busy, every submission is rejected.
		ThreadPoolExecutor saturated = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
				new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
		saturated.execute(() -> {
			occupied.countDown();
			try {
				release.await();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});

		try {
			assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();

			DecisionConsistency.Report report = DecisionConsistency.sample(this.model,
					Map.of("ticket", "Payouts fail"), Map.of("urgent", YesNoQuestion.of("Is it urgent?")), 3,
					DecisionConsistency.Sampling.defaults().withExecutor(saturated));

			assertThat(report.samples()).isEmpty();
			assertThat(report.statistics()).isEmpty();
			assertThat(report.failures()).extracting(DecisionConsistency.Failure::index).containsExactly(0, 1, 2);
			assertThat(report.failures())
				.allSatisfy(failure -> assertThat(failure.cause()).isInstanceOf(RejectedExecutionException.class));
			assertThat(this.model.prompts()).isEmpty();
			// The caller's executor is theirs to shut down.
			assertThat(saturated.isShutdown()).isFalse();
		}
		finally {
			release.countDown();
			saturated.shutdownNow();
		}
	}

	@Test
	void rejectsFieldsThatAlreadyCarryAUid() {
		// Overwriting it would silently change what the caller asked about.
		assertThatIllegalArgumentException().isThrownBy(() -> DecisionConsistency.sample(this.model,
				Map.of("ticket", "x", DecisionConsistency.UID_FIELD, "order-42"),
				Map.of("urgent", YesNoQuestion.of("Is it urgent?")), 2))
			.withMessageContaining("'uid' is reserved");
		assertThat(this.model.prompts()).isEmpty();
	}

	@Test
	void needsAtLeastTwoSamples() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> DecisionConsistency.sample(this.model, Map.of("ticket", "x"),
					Map.of("urgent", YesNoQuestion.of("Is it urgent?")), 1))
			.withMessageContaining("at least 2 samples");
	}

	private static Map<String, DecisionAnswer> scores(double depth, double leadership) {
		return Map.of("depth", score(depth, 0.9d, 0.0d, 0.0d, 0.0d, 0.0d, 1.0d), "leadership",
				score(leadership, 0.9d, 0.0d, 0.0d, 1.0d, 0.0d, 0.0d));
	}

}
