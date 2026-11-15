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

package org.springaicommunity.decision.guardrail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.decision.common.AssistantAnswers;
import org.springaicommunity.decision.common.DecisionContextKeys;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.decision.DecisionOptions;
import org.springframework.core.Ordered;
import org.springframework.util.Assert;

/**
 * Screens what the user sends and what the model answers, refusing the turn when either
 * crosses a line.
 *
 * <p>
 * Both directions are checked because they fail differently. An input battery catches the
 * request that should never have been made; an output battery catches the reply that
 * should never have been given, which is the only one of the two that notices a jailbreak
 * that actually worked. Each battery is a single call carrying all its hazards.
 *
 * <p>
 * A blocked input never reaches the model at all — the refusal is returned in place of
 * the call, so nothing is spent and nothing is generated. A blocked output replaces the
 * generated text after the fact.
 *
 * <p>
 * <h3>Where it sits, and why the two directions belong in different places</h3>
 *
 * The request is best screened at the outer edge of the chain and the reply at the inner
 * one, so the recommended setup is two advisors, one per direction:
 *
 * <pre>{@code
 * ChatClient.builder(chatModel)
 *     .defaultAdvisors(
 *             DecisionGuardrailAdvisor.input(decisionModel),
 *             MessageChatMemoryAdvisor.builder(chatMemory).build(),
 *             DecisionGuardrailAdvisor.output(decisionModel))
 *     .build();
 * }</pre>
 *
 * {@link #input(DecisionModel)} runs at {@link #BEFORE_MEMORY_ORDER}, ahead of chat
 * memory, retrieval and the self-refine advisor. There it screens what the person
 * actually wrote, once per turn, before a retrieval advisor inlines documents into the
 * message or the self-refine advisor appends its feedback; and a blocked message is never
 * stored, so it cannot come back as history on the next turn.
 * {@link #output(DecisionModel)} runs at {@link #DEFAULT_ORDER}, nearer the model than
 * chat memory, the tool loop and the self-refine advisor. There it screens every reply
 * the model gives, each retry attempt before it is judged, and what chat memory stores is
 * the refusal rather than the reply it replaced.
 *
 * <p>
 * A single advisor from {@link #builder(DecisionModel)} screens both directions at
 * {@link #DEFAULT_ORDER}. That is simpler, and enough for a chain with no chat memory and
 * no advisor that rewrites the user message. With them, its input battery sees the
 * rewritten message rather than the person's, runs again on every model call, and a
 * blocked message is already in memory by the time it is refused.
 *
 * <p>
 * This is a different job from {@code DecisionSelfRefineAdvisor}, which judges quality
 * and retries to improve it. Retrying does not help here: an unsafe answer is not a
 * draft. The two compose: the self-refine advisor does not judge or retry a turn this
 * advisor refused.
 *
 * Streaming is unsupported, for the same reason as the self-refine advisor: an output
 * battery needs the whole reply before it can judge it, by which point it has already
 * been emitted.
 *
 * @author Christian Tzolov
 */
public class DecisionGuardrailAdvisor implements CallAdvisor, StreamAdvisor {

	/**
	 * The default order: nearer the model than chat memory, the tool loop and the
	 * self-refine advisor, so every reply is screened before anything else sees it.
	 */
	public static final int DEFAULT_ORDER = BaseAdvisor.LOWEST_PRECEDENCE - 1000;

	/**
	 * An order ahead of chat memory ({@code HIGHEST_PRECEDENCE + 200}) and everything
	 * after it, where the user message is still what the person wrote and a blocked one
	 * is never remembered. The place for screening the input.
	 */
	public static final int BEFORE_MEMORY_ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

	/** What a blocked turn says when the caller has not supplied wording. */
	public static final String DEFAULT_REFUSAL = "I can't help with that.";

	/**
	 * The response-context key a refused turn carries, holding the name of the
	 * {@link DecisionGuardrail.Outcome} that refused it. It marks the response as the
	 * guardrail's own words rather than the model's answer, so an advisor further out,
	 * such as {@code DecisionSelfRefineAdvisor}, does not judge a refusal or retry it.
	 */
	public static final String OUTCOME_CONTEXT_KEY = DecisionContextKeys.GUARDRAIL_OUTCOME;

	/** What a turn routed to support says when the caller has not supplied wording. */
	public static final String DEFAULT_SUPPORT_MESSAGE = "It sounds like you may be going through something "
			+ "difficult. I can't help with this here, but people who can are available — please consider "
			+ "reaching out to a local support line.";

	private static final Logger logger = LoggerFactory.getLogger(DecisionGuardrailAdvisor.class);

	private final DecisionModel decisionModel;

	private final @Nullable DecisionOptions options;

	private final @Nullable DecisionGuardrail inputBattery;

	private final @Nullable DecisionGuardrail outputBattery;

	private final String refusal;

	private final String supportMessage;

	private final boolean blockOnReview;

	private final int advisorOrder;

	private DecisionGuardrailAdvisor(DecisionModel decisionModel, @Nullable DecisionOptions options,
			@Nullable DecisionGuardrail inputBattery, @Nullable DecisionGuardrail outputBattery, String refusal,
			String supportMessage, boolean blockOnReview, int advisorOrder) {
		this.decisionModel = decisionModel;
		this.options = options;
		this.inputBattery = inputBattery;
		this.outputBattery = outputBattery;
		this.refusal = refusal;
		this.supportMessage = supportMessage;
		this.blockOnReview = blockOnReview;
		this.advisorOrder = advisorOrder;
	}

	@Override
	public String getName() {
		return "Decision Guardrail Advisor";
	}

	@Override
	public int getOrder() {
		return this.advisorOrder;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
		Assert.notNull(chatClientRequest, "chatClientRequest must not be null");
		Assert.notNull(callAdvisorChain, "callAdvisorChain must not be null");

		if (this.inputBattery != null) {
			UserMessage userMessage = chatClientRequest.prompt().getUserMessage();
			String text = userMessage == null ? "" : userMessage.getText();
			if (text != null && !text.isBlank()) {
				DecisionGuardrail.Verdict verdict = this.inputBattery.screen(this.decisionModel, text, this.options);
				if (shouldBlock(verdict)) {
					logger.warn("The decision guardrail blocked the request: {}", verdict.summary());
					// Refused before the chain runs, so the model is never called.
					return refuse(chatClientRequest, this.inputBattery, verdict);
				}
				logInconclusive(verdict);
			}
		}

		ChatClientResponse response = callAdvisorChain.nextCall(chatClientRequest);

		if (this.outputBattery != null) {
			for (String text : AssistantAnswers.visibleTextsOf(response)) {
				DecisionGuardrail.Verdict verdict = this.outputBattery.screen(this.decisionModel, text, this.options);
				if (shouldBlock(verdict)) {
					logger.warn("The decision guardrail blocked the response: {}", verdict.summary());
					return refuse(chatClientRequest, this.outputBattery, verdict);
				}
				logInconclusive(verdict);
			}
		}

		return response;
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
			StreamAdvisorChain streamAdvisorChain) {
		return Flux
			.error(new UnsupportedOperationException("The Decision Guardrail Advisor does not support streaming."));
	}

	private boolean shouldBlock(DecisionGuardrail.Verdict verdict) {
		return verdict.blocked() || (this.blockOnReview && verdict.outcome() == DecisionGuardrail.Outcome.REVIEW);
	}

	private void logInconclusive(DecisionGuardrail.Verdict verdict) {
		if (verdict.outcome() == DecisionGuardrail.Outcome.REVIEW) {
			logger.info("The decision guardrail flagged a turn for review but let it through: {}", verdict.summary());
		}
	}

	private ChatClientResponse refuse(ChatClientRequest request, DecisionGuardrail battery,
			DecisionGuardrail.Verdict verdict) {
		// A review refused by blockOnReview still gets the wording of what was flagged.
		String text = battery.refusalOutcome(verdict) == DecisionGuardrail.Outcome.SUPPORT ? this.supportMessage
				: this.refusal;
		ChatResponse chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
		Map<String, Object> context = new HashMap<>(request.context());
		context.put(OUTCOME_CONTEXT_KEY, verdict.outcome().name());
		return ChatClientResponse.builder().chatResponse(chatResponse).context(context).build();
	}

	/**
	 * @param decisionModel the model the hazards are put to
	 * @return a new builder, screening both directions with the default batteries at
	 * {@link #DEFAULT_ORDER}
	 */
	public static Builder builder(DecisionModel decisionModel) {
		return new Builder(decisionModel);
	}

	/**
	 * An advisor that screens only the user message, with the default input battery, at
	 * {@link #BEFORE_MEMORY_ORDER}. Pair it with {@link #output(DecisionModel)}. For
	 * other settings, the same is
	 * {@code builder(decisionModel).outputBattery(null).order(BEFORE_MEMORY_ORDER)}.
	 * @param decisionModel the model the hazards are put to
	 * @return the advisor
	 */
	public static DecisionGuardrailAdvisor input(DecisionModel decisionModel) {
		return builder(decisionModel).outputBattery(null).order(BEFORE_MEMORY_ORDER).build();
	}

	/**
	 * An advisor that screens only the reply, with the default output battery, at
	 * {@link #DEFAULT_ORDER}. Pair it with {@link #input(DecisionModel)}. For other
	 * settings, the same is {@code builder(decisionModel).inputBattery(null)}.
	 * @param decisionModel the model the hazards are put to
	 * @return the advisor
	 */
	public static DecisionGuardrailAdvisor output(DecisionModel decisionModel) {
		return builder(decisionModel).inputBattery(null).build();
	}

	/**
	 * Builder for {@link DecisionGuardrailAdvisor}.
	 */
	public static final class Builder {

		private final DecisionModel decisionModel;

		private @Nullable DecisionOptions options;

		private @Nullable DecisionGuardrail inputBattery = DecisionGuardrail.defaultInputBattery();

		private @Nullable DecisionGuardrail outputBattery = DecisionGuardrail.defaultOutputBattery();

		private String refusal = DEFAULT_REFUSAL;

		private String supportMessage = DEFAULT_SUPPORT_MESSAGE;

		private boolean blockOnReview;

		private int advisorOrder = DEFAULT_ORDER;

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
		 * @param inputBattery what to ask of the user message, or {@code null} to skip
		 * screening the input
		 * @return this builder
		 */
		public Builder inputBattery(@Nullable DecisionGuardrail inputBattery) {
			this.inputBattery = inputBattery;
			return this;
		}

		/**
		 * @param outputBattery what to ask of the reply, or {@code null} to skip
		 * screening the output
		 * @return this builder
		 */
		public Builder outputBattery(@Nullable DecisionGuardrail outputBattery) {
			this.outputBattery = outputBattery;
			return this;
		}

		/**
		 * @param refusal what to say when a turn is blocked
		 * @return this builder
		 */
		public Builder refusal(String refusal) {
			Assert.hasText(refusal, "refusal must not be empty");
			this.refusal = refusal;
			return this;
		}

		/**
		 * @param supportMessage what to say when a turn is routed to support
		 * @return this builder
		 */
		public Builder supportMessage(String supportMessage) {
			Assert.hasText(supportMessage, "supportMessage must not be empty");
			this.supportMessage = supportMessage;
			return this;
		}

		/**
		 * Refuses a flagged-for-review turn, with the support message when a flagged
		 * hazard routes to support and the refusal otherwise. Off by default: review
		 * exists so a borderline case reaches a human instead of being refused by a
		 * threshold.
		 * @param blockOnReview whether to refuse on review
		 * @return this builder
		 */
		public Builder blockOnReview(boolean blockOnReview) {
			this.blockOnReview = blockOnReview;
			return this;
		}

		/**
		 * @param advisorOrder where in the chain this runs; {@link #DEFAULT_ORDER} unless
		 * set, and {@link #BEFORE_MEMORY_ORDER} is the place for an input-only advisor
		 * @return this builder
		 */
		public Builder order(int advisorOrder) {
			Assert.isTrue(advisorOrder > BaseAdvisor.HIGHEST_PRECEDENCE && advisorOrder < BaseAdvisor.LOWEST_PRECEDENCE,
					"advisorOrder must be between HIGHEST_PRECEDENCE and LOWEST_PRECEDENCE");
			this.advisorOrder = advisorOrder;
			return this;
		}

		public DecisionGuardrailAdvisor build() {
			Assert.isTrue(this.inputBattery != null || this.outputBattery != null,
					"a guardrail that screens neither direction does nothing");
			return new DecisionGuardrailAdvisor(this.decisionModel, this.options, this.inputBattery, this.outputBattery,
					this.refusal, this.supportMessage, this.blockOnReview, this.advisorOrder);
		}

	}

}
