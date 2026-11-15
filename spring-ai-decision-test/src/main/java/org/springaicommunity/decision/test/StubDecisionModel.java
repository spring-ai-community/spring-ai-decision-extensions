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

package org.springaicommunity.decision.test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.DecisionResponseMetadata;
import org.springframework.ai.decision.answer.ChoiceAnswer;
import org.springframework.ai.decision.answer.DecisionAnswer;
import org.springframework.ai.decision.answer.RefusalAnswer;
import org.springframework.ai.decision.answer.ScoreAnswer;
import org.springframework.ai.decision.answer.YesNoAnswer;
import org.springframework.util.Assert;

/**
 * A {@link DecisionModel} that answers from a script instead of calling a provider, and
 * records every prompt it was given, so code built on the DecisionModel API can be tested
 * offline, without a provider or a key.
 *
 * <p>
 * It answers in one of two ways. {@link #respondWith} and {@link #failWith} queue the
 * outcome of one call each, consumed in order, and a call with nothing queued fails the
 * test, so an unexpected call cannot pass unnoticed. {@link #answering} gives the same
 * answers to every call instead.
 *
 * <p>
 * Only the questions a prompt asks are answered, so one script can serve prompts that ask
 * for a subset of it. A question the script has no answer for fails the call, as a
 * provider that left a question out would.
 *
 * @author Christian Tzolov
 */
public final class StubDecisionModel implements DecisionModel {

	private final Queue<Function<DecisionPrompt, DecisionResponse>> queued = new ConcurrentLinkedQueue<>();

	private final @Nullable Function<DecisionPrompt, DecisionResponse> always;

	private final List<DecisionPrompt> prompts = new CopyOnWriteArrayList<>();

	/**
	 * A model with nothing scripted yet; queue its outcomes with {@link #respondWith} and
	 * {@link #failWith}.
	 */
	public StubDecisionModel() {
		this.always = null;
	}

	private StubDecisionModel(Function<DecisionPrompt, DecisionResponse> always) {
		this.always = always;
	}

	/**
	 * A model that gives the same answers to every prompt.
	 */
	public static StubDecisionModel answering(Map<String, ? extends DecisionAnswer> answers) {
		Assert.notNull(answers, "answers cannot be null");
		return new StubDecisionModel(prompt -> response(prompt, answers));
	}

	/**
	 * A model whose answers depend on the prompt.
	 */
	public static StubDecisionModel answering(Function<DecisionPrompt, Map<String, ? extends DecisionAnswer>> script) {
		Assert.notNull(script, "script cannot be null");
		return new StubDecisionModel(prompt -> response(prompt, script.apply(prompt)));
	}

	/**
	 * A model whose every call fails.
	 */
	public static StubDecisionModel failing(RuntimeException failure) {
		Assert.notNull(failure, "failure cannot be null");
		return new StubDecisionModel(prompt -> {
			throw failure;
		});
	}

	/**
	 * Queues the answers of the next call.
	 */
	public StubDecisionModel respondWith(Map<String, ? extends DecisionAnswer> answers) {
		Assert.notNull(answers, "answers cannot be null");
		this.queued.add(prompt -> response(prompt, answers));
		return this;
	}

	/**
	 * Queues the answers and the response metadata of the next call.
	 */
	public StubDecisionModel respondWith(Map<String, ? extends DecisionAnswer> answers,
			DecisionResponseMetadata metadata) {
		Assert.notNull(answers, "answers cannot be null");
		Assert.notNull(metadata, "metadata cannot be null");
		this.queued.add(prompt -> response(prompt, answers, metadata));
		return this;
	}

	/**
	 * Queues a failure for the next call.
	 */
	public StubDecisionModel failWith(RuntimeException failure) {
		Assert.notNull(failure, "failure cannot be null");
		this.queued.add(prompt -> {
			throw failure;
		});
		return this;
	}

	/**
	 * Starts the script of the next call with what its prompt must look like. The
	 * expectations are checked when the call is made, and a broken one fails the test
	 * with an {@link AssertionError}.
	 */
	public Expectation expect() {
		return new Expectation();
	}

	/**
	 * What the prompt of one call must look like, and how the call then ends.
	 */
	public final class Expectation {

		private final List<Consumer<DecisionPrompt>> expectations = new CopyOnWriteArrayList<>();

		private Expectation() {
		}

		/**
		 * Adds an assertion on the prompt; see {@link PromptField#field(String)}.
		 */
		public Expectation andExpect(Consumer<DecisionPrompt> expectation) {
			Assert.notNull(expectation, "expectation cannot be null");
			this.expectations.add(expectation);
			return this;
		}

		public StubDecisionModel respondWith(Map<String, ? extends DecisionAnswer> answers) {
			return respondWith(answers, DecisionResponseMetadata.builder().build());
		}

		public StubDecisionModel respondWith(Map<String, ? extends DecisionAnswer> answers,
				DecisionResponseMetadata metadata) {
			Assert.notNull(answers, "answers cannot be null");
			Assert.notNull(metadata, "metadata cannot be null");
			StubDecisionModel.this.queued.add(prompt -> {
				this.expectations.forEach(expectation -> expectation.accept(prompt));
				return response(prompt, answers, metadata);
			});
			return StubDecisionModel.this;
		}

		public StubDecisionModel failWith(RuntimeException failure) {
			Assert.notNull(failure, "failure cannot be null");
			StubDecisionModel.this.queued.add(prompt -> {
				this.expectations.forEach(expectation -> expectation.accept(prompt));
				throw failure;
			});
			return StubDecisionModel.this;
		}

	}

	@Override
	public DecisionResponse call(DecisionPrompt prompt) {
		this.prompts.add(prompt);
		Function<DecisionPrompt, DecisionResponse> next = this.queued.poll();
		if (next == null) {
			next = this.always;
		}
		if (next == null) {
			throw new AssertionError("No further call to the decision model was expected, but it was asked "
					+ prompt.getQuestions().keySet());
		}
		return next.apply(prompt);
	}

	private static DecisionResponse response(DecisionPrompt prompt, Map<String, ? extends DecisionAnswer> answers) {
		return response(prompt, answers, DecisionResponseMetadata.builder().build());
	}

	private static DecisionResponse response(DecisionPrompt prompt, Map<String, ? extends DecisionAnswer> answers,
			DecisionResponseMetadata metadata) {
		DecisionResponse.Builder response = DecisionResponse.builder(prompt).metadata(metadata);
		for (String name : prompt.getQuestions().keySet()) {
			DecisionAnswer answer = answers.get(name);
			if (answer != null) {
				response.answer(name, answer);
			}
		}
		return response.build();
	}

	/**
	 * Fails when a queued outcome was never asked for.
	 */
	public void verify() {
		if (!this.queued.isEmpty()) {
			throw new AssertionError(this.queued.size() + " scripted call(s) to the decision model were never made");
		}
	}

	/**
	 * Every prompt the model was called with, in order.
	 */
	public List<DecisionPrompt> prompts() {
		return List.copyOf(this.prompts);
	}

	/**
	 * The most recent prompt.
	 */
	public DecisionPrompt lastPrompt() {
		Assert.state(!this.prompts.isEmpty(), "the model was never called");
		return this.prompts.get(this.prompts.size() - 1);
	}

	public static YesNoAnswer yesNo(double probability) {
		return YesNoAnswer.builder().probability(probability).build();
	}

	/**
	 * A choice with a confidence and no probabilities.
	 */
	public static ChoiceAnswer choice(String value, @Nullable Double confidence) {
		return ChoiceAnswer.builder().value(value).confidence(confidence).build();
	}

	public static ChoiceAnswer choice(String value, @Nullable Double confidence, Map<String, Double> probabilities) {
		return ChoiceAnswer.builder().value(value).confidence(confidence).probabilities(probabilities).build();
	}

	/**
	 * The probabilities of a choice, in the order given: an option, its probability, the
	 * next option, and so on.
	 */
	public static Map<String, Double> probabilities(Object... optionsAndProbabilities) {
		Assert.isTrue(optionsAndProbabilities.length % 2 == 0, "give each option its probability");
		Map<String, Double> probabilities = new LinkedHashMap<>();
		for (int i = 0; i < optionsAndProbabilities.length; i += 2) {
			probabilities.put((String) optionsAndProbabilities[i],
					((Number) optionsAndProbabilities[i + 1]).doubleValue());
		}
		return probabilities;
	}

	/**
	 * A score with neither a confidence nor probabilities.
	 */
	public static ScoreAnswer score(double value) {
		return ScoreAnswer.builder().value(value).build();
	}

	/**
	 * A score with a confidence and the probability of each level, lowest level first.
	 */
	public static ScoreAnswer score(double value, @Nullable Double confidence, double... probabilities) {
		return ScoreAnswer.builder()
			.value(value)
			.confidence(confidence)
			.probabilities(Arrays.stream(probabilities).boxed().toList())
			.build();
	}

	public static RefusalAnswer refusal(@Nullable String reason) {
		return RefusalAnswer.builder().reason(reason).build();
	}

}
