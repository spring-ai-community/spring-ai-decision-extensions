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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.decision.DecisionOptions;
import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.answer.DecisionAnswer;
import org.springframework.ai.decision.answer.ScoreAnswer;
import org.springframework.ai.decision.answer.YesNoAnswer;
import org.springframework.ai.decision.input.DecisionInput;
import org.springframework.ai.decision.question.DecisionQuestion;
import org.springframework.util.Assert;

/**
 * Asks the same questions repeatedly and reports how much the answers move.
 *
 * <p>
 * A single number tells you where an answer sits; it does not tell you whether it would
 * sit there again. That matters when a value lands near a threshold, because there the
 * difference between acting and not acting can be noise rather than judgement. Sampling a
 * question a few times and looking at the spread is how you find out which of your
 * thresholds are resting on solid ground.
 *
 * <p>
 * Each sample carries a fresh throwaway {@code uid} among its fields, which is what makes
 * the draws independent rather than a repeat of one cached computation. The fields are
 * otherwise untouched, and the questions are identical across samples — this measures the
 * model's own stability, not the effect of rewording.
 *
 * <pre>{@code
 * DecisionConsistency.Report report = DecisionConsistency.sample(decisionModel, fields, questions, 15);
 * report.statistics().forEach((name, stats) ->
 *         System.out.printf("%-20s mean %.3f  sd %.3f  range %.3f%n",
 *                 name, stats.mean(), stats.standardDeviation(), stats.range()));
 * }</pre>
 *
 * @author Christian Tzolov
 */
public final class DecisionConsistency {

	/** The default number of samples. */
	public static final int DEFAULT_SAMPLES = 15;

	/** The field carrying the throwaway id that keeps the draws independent. */
	public static final String UID_FIELD = "uid";

	private DecisionConsistency() {
	}

	/**
	 * How the samples are drawn.
	 *
	 * @param concurrency how many calls may be in flight at once
	 * @param executor the executor to run on, or {@code null} to create and dispose a
	 * pool per run. A supplied executor is never shut down, so it can be shared across
	 * runs or be a virtual-thread executor. A sample it rejects is reported as a
	 * {@link Failure}
	 * @param options the options sent with every call to the model, such as the model
	 * name; {@code null} for the model's defaults
	 */
	public record Sampling(int concurrency, @Nullable Executor executor, @Nullable DecisionOptions options) {

		/** The default number of calls in flight at once. */
		public static final int DEFAULT_CONCURRENCY = 4;

		public Sampling {
			Assert.isTrue(concurrency >= 1, "concurrency must be at least 1");
		}

		/**
		 * @return four calls at a time, on a pool of the run's own, with the model's
		 * default options
		 */
		public static Sampling defaults() {
			return new Sampling(DEFAULT_CONCURRENCY, null, null);
		}

		/**
		 * @param concurrency how many calls may be in flight at once
		 * @return a copy with that width
		 */
		public Sampling withConcurrency(int concurrency) {
			return new Sampling(concurrency, this.executor, this.options);
		}

		/**
		 * @param executor the executor to run on
		 * @return a copy using that executor
		 */
		public Sampling withExecutor(@Nullable Executor executor) {
			return new Sampling(this.concurrency, executor, this.options);
		}

		/**
		 * @param options the options sent with every call
		 * @return a copy using those options
		 */
		public Sampling withOptions(@Nullable DecisionOptions options) {
			return new Sampling(this.concurrency, this.executor, options);
		}

	}

	/**
	 * A sample that did not come back.
	 *
	 * @param index the position of the sample in the run
	 * @param cause what the model call failed with, or the
	 * {@link RejectedExecutionException} of an executor that turned the sample away
	 */
	public record Failure(int index, RuntimeException cause) {

		public Failure {
			Assert.notNull(cause, "cause must not be null");
		}

	}

	/**
	 * How one question's value moved across the samples.
	 *
	 * @param name the question name
	 * @param values every value observed, in sample order
	 * @param mean the arithmetic mean
	 * @param standardDeviation the population standard deviation
	 * @param min the smallest value observed
	 * @param max the largest value observed
	 */
	public record Statistics(String name, List<Double> values, double mean, double standardDeviation, double min,
			double max) {

		public Statistics {
			values = values == null ? List.of() : List.copyOf(values);
		}

		/**
		 * @return the spread between the extremes, the quickest read on whether a
		 * threshold sitting inside it would flip between runs
		 */
		public double range() {
			return this.max - this.min;
		}

		/**
		 * @param threshold a threshold applied to this question
		 * @return {@code true} when the samples fall on both sides of it, so the decision
		 * this question drives is not reproducible
		 */
		public boolean straddles(double threshold) {
			return this.min < threshold && this.max >= threshold;
		}
	}

	/**
	 * The outcome of a sampling run.
	 *
	 * @param samples the successful responses, in sample order
	 * @param failures the samples that did not come back
	 * @param statistics per-question statistics, keyed by question name
	 */
	public record Report(List<DecisionResponse> samples, List<Failure> failures, Map<String, Statistics> statistics) {

		public Report {
			samples = samples == null ? List.of() : List.copyOf(samples);
			failures = failures == null ? List.of() : List.copyOf(failures);
			statistics = statistics == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(statistics));
		}

		/**
		 * @param threshold a threshold
		 * @return the questions whose samples fall on both sides of it
		 */
		public List<String> unstableAt(double threshold) {
			return this.statistics.values()
				.stream()
				.filter(statistics -> statistics.straddles(threshold))
				.map(Statistics::name)
				.toList();
		}
	}

	/**
	 * Draws {@link #DEFAULT_SAMPLES} samples.
	 * @param decisionModel the model to sample
	 * @param fields the fields to ask about
	 * @param questions the questions
	 * @return the report
	 */
	public static Report sample(DecisionModel decisionModel, Map<String, ?> fields,
			Map<String, ? extends DecisionQuestion> questions) {
		return sample(decisionModel, fields, questions, DEFAULT_SAMPLES, Sampling.defaults());
	}

	/**
	 * Draws a given number of samples.
	 * @param decisionModel the model to sample
	 * @param fields the fields to ask about
	 * @param questions the questions
	 * @param samples how many times to ask
	 * @return the report
	 */
	public static Report sample(DecisionModel decisionModel, Map<String, ?> fields,
			Map<String, ? extends DecisionQuestion> questions, int samples) {
		return sample(decisionModel, fields, questions, samples, Sampling.defaults());
	}

	/**
	 * Draws a given number of samples with control over how they are drawn.
	 *
	 * <p>
	 * A sample that fails does not abort the run: it is reported among the
	 * {@link Report#failures() failures} and the rest still run. The same holds for a
	 * sample the {@link Sampling#executor() executor} rejects.
	 * @param decisionModel the model to sample
	 * @param fields the fields to ask about, each a value a
	 * {@link org.springframework.ai.decision.input.FieldsInput} accepts; a {@code uid} is
	 * added to each sample's copy, so the fields must not carry one of their own
	 * @param questions the questions, identical for every sample
	 * @param samples how many times to ask
	 * @param sampling how widely to fan the samples out, and with which options
	 * @return the report
	 */
	public static Report sample(DecisionModel decisionModel, Map<String, ?> fields,
			Map<String, ? extends DecisionQuestion> questions, int samples, Sampling sampling) {
		Assert.notNull(decisionModel, "decisionModel must not be null");
		Assert.notNull(fields, "fields must not be null");
		Assert.isTrue(!fields.containsKey(UID_FIELD), "'" + UID_FIELD
				+ "' is reserved for the throwaway id each sample carries; pass that field under another name");
		Assert.notEmpty(questions, "questions must declare at least one question");
		Assert.isTrue(samples >= 2, "sampling needs at least 2 samples to say anything about spread");
		Assert.notNull(sampling, "sampling must not be null");

		List<DecisionPrompt> prompts = new ArrayList<>(samples);
		for (int i = 0; i < samples; i++) {
			Map<String, @Nullable Object> sampleFields = new LinkedHashMap<>(fields);
			sampleFields.put(UID_FIELD, i + ":" + UUID.randomUUID());
			prompts.add(DecisionPrompt.builder()
				.input(DecisionInput.fields(sampleFields))
				.questions(questions)
				.options(sampling.options())
				.build());
		}

		List<DecisionResponse> responses = new ArrayList<>();
		List<Failure> failures = new ArrayList<>();
		List<Object> outcomes = callAll(decisionModel, prompts, sampling);
		for (int i = 0; i < outcomes.size(); i++) {
			if (outcomes.get(i) instanceof DecisionResponse response) {
				responses.add(response);
			}
			else {
				failures.add(new Failure(i, (RuntimeException) outcomes.get(i)));
			}
		}

		return new Report(responses, failures, summarise(questions.keySet(), responses));
	}

	/**
	 * @return one outcome per prompt, in order: the response, or the exception the call
	 * failed with or was rejected with
	 */
	private static List<Object> callAll(DecisionModel decisionModel, List<DecisionPrompt> prompts, Sampling sampling) {
		ExecutorService owned = (sampling.executor() == null)
				? Executors.newFixedThreadPool(Math.min(sampling.concurrency(), prompts.size()), threadFactory())
				: null;
		Executor executor = (owned != null) ? owned : sampling.executor();
		// Bounds in-flight work even when the caller supplied an unbounded executor.
		Semaphore permits = new Semaphore(sampling.concurrency());
		try {
			List<CompletableFuture<Object>> futures = new ArrayList<>(prompts.size());
			for (DecisionPrompt prompt : prompts) {
				try {
					futures.add(CompletableFuture.supplyAsync(() -> callOne(decisionModel, prompt, permits), executor));
				}
				catch (RejectedExecutionException ex) {
					// A bounded executor of the caller's can reject a submission before
					// callOne starts, where its own handling cannot see the failure. Keep
					// the rejection as this sample's outcome, so the samples the executor
					// did accept are still joined and reported.
					futures.add(CompletableFuture.completedFuture(ex));
				}
			}
			return futures.stream().map(CompletableFuture::join).toList();
		}
		finally {
			if (owned != null) {
				owned.shutdown();
			}
		}
	}

	private static Object callOne(DecisionModel decisionModel, DecisionPrompt prompt, Semaphore permits) {
		permits.acquireUninterruptibly();
		try {
			return decisionModel.call(prompt);
		}
		catch (RuntimeException ex) {
			// Letting it escape would make join() throw out of the run, costing the
			// caller every sample that had already come back.
			return ex;
		}
		finally {
			permits.release();
		}
	}

	private static java.util.concurrent.ThreadFactory threadFactory() {
		AtomicInteger counter = new AtomicInteger();
		return runnable -> {
			Thread thread = new Thread(runnable, "decision-consistency-" + counter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}

	private static Map<String, Statistics> summarise(Iterable<String> names, List<DecisionResponse> responses) {
		Map<String, Statistics> statistics = new LinkedHashMap<>();
		for (String name : names) {
			List<Double> values = new ArrayList<>();
			for (DecisionResponse response : responses) {
				DecisionAnswer answer = response.getAnswers().get(name);
				// A choice has no single number to track, so only yes/no answers and
				// scores are summarised; a choice's stability is a question about its
				// distribution. A refusal has no value either.
				if (answer instanceof YesNoAnswer yesNo) {
					values.add(yesNo.getProbability());
				}
				else if (answer instanceof ScoreAnswer score) {
					values.add(score.getValue());
				}
			}
			if (!values.isEmpty()) {
				statistics.put(name, describe(name, values));
			}
		}
		return statistics;
	}

	private static Statistics describe(String name, List<Double> values) {
		double sum = 0.0d;
		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;
		for (double value : values) {
			sum += value;
			min = Math.min(min, value);
			max = Math.max(max, value);
		}
		double mean = sum / values.size();
		double variance = 0.0d;
		for (double value : values) {
			variance += (value - mean) * (value - mean);
		}
		return new Statistics(name, values, mean, Math.sqrt(variance / values.size()), min, max);
	}

}
