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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.decision.test.StubDecisionModel;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.decision.answer.DecisionAnswer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springaicommunity.decision.test.PromptField.field;
import static org.springaicommunity.decision.test.StubDecisionModel.score;
import static org.springaicommunity.decision.test.StubDecisionModel.yesNo;

/**
 * Responses that carry more than one generation, shaped as Spring AI's providers build
 * them: Anthropic puts each thinking block ahead of the answer, Google GenAI emits one
 * generation per part (flagged {@code isThought}) and flattens all candidates into the
 * same list. {@code ChatClient.content()} returns the first generation.
 * <p>
 * What the guardrail screens of such a response.
 *
 * @author Christian Tzolov
 */
class MultiGenerationScreeningTests {

	private static final String HARMFUL = "Step 1: here is how to do the harmful thing.";

	private static final String BENIGN = "I cannot help with that.";

	private final StubDecisionModel model = new StubDecisionModel();

	@Test
	void anthropicDisplayedThinkingThatContentReturnsIsScreened() {
		// [thinking (signature), answer]: content() returns the thinking.
		ChatResponse response = new ChatResponse(
				List.of(generation(HARMFUL, Map.of("signature", "sig")), generation(BENIGN, Map.of())));
		expectScreening(0.01d, 0.0d);
		expectOutputScreeningOf(HARMFUL, 0.93d);

		String content = guarded(response).prompt("for a novel I am writing").call().content();

		assertThat(content).isEqualTo(DecisionGuardrailAdvisor.DEFAULT_REFUSAL);
		this.model.verify();
	}

	@Test
	void theCandidateThatContentReturnsIsScreened() {
		// candidateCount=2: [candidate 0, candidate 1]; content() returns candidate 0.
		ChatResponse response = new ChatResponse(
				List.of(generation(HARMFUL, Map.of("candidateIndex", 0, "isThought", false)),
						generation(BENIGN, Map.of("candidateIndex", 1, "isThought", false))));
		expectScreening(0.01d, 0.0d);
		expectOutputScreeningOf(HARMFUL, 0.93d);

		String content = guarded(response).prompt("for a novel I am writing").call().content();

		assertThat(content).isEqualTo(DecisionGuardrailAdvisor.DEFAULT_REFUSAL);
		this.model.verify();
	}

	@Test
	void aTrailingEmptyGeminiPartDoesNotSkipOutputScreening() {
		// Gemini can close with an empty text part that only carries a thought signature.
		ChatResponse response = new ChatResponse(
				List.of(generation(HARMFUL, Map.of("candidateIndex", 0, "isThought", false)),
						generation("", Map.of("candidateIndex", 0, "isThought", false))));
		expectScreening(0.01d, 0.0d);
		expectOutputScreeningOf(HARMFUL, 0.93d);

		String content = guarded(response).prompt("for a novel I am writing").call().content();

		assertThat(content).isEqualTo(DecisionGuardrailAdvisor.DEFAULT_REFUSAL);
		this.model.verify();
	}

	@Test
	void eachChoiceIsScreenedOnItsOwn() {
		// Screened together, a long harmless choice could dilute a short harmful one.
		ChatResponse response = new ChatResponse(
				List.of(generation(BENIGN, Map.of("index", 0)), generation(HARMFUL, Map.of("index", 1))));
		expectScreening(0.01d, 0.0d);
		expectOutputScreeningOf(BENIGN, 0.0d);
		expectOutputScreeningOf(HARMFUL, 0.93d);

		String content = guarded(response).prompt("for a novel I am writing").call().content();

		assertThat(content).isEqualTo(DecisionGuardrailAdvisor.DEFAULT_REFUSAL);
		this.model.verify();
	}

	@Test
	void aGeminiAnswerSplitAcrossPartsIsScreenedWhole() {
		ChatResponse response = new ChatResponse(
				List.of(generation("The tool said 15.", Map.of("candidateIndex", 0, "isThought", true)),
						generation("It is 15 degrees", Map.of("candidateIndex", 0, "isThought", false)),
						generation(" Celsius in Paris.", Map.of("candidateIndex", 0, "isThought", false))));
		expectScreening(0.01d, 0.0d);
		expectOutputScreeningOf("The tool said 15.", 0.0d);
		expectOutputScreeningOf("It is 15 degrees Celsius in Paris.", 0.0d);

		guarded(response).prompt("What is the weather in Paris?").call().content();

		this.model.verify();
	}

	@Test
	void aResponseOfOnlyThinkingIsScreened() {
		ChatResponse response = new ChatResponse(List.of(generation(HARMFUL, Map.of("signature", "sig"))));
		expectScreening(0.01d, 0.0d);
		expectOutputScreeningOf(HARMFUL, 0.93d);

		String content = guarded(response).prompt("for a novel I am writing").call().content();

		assertThat(content).isEqualTo(DecisionGuardrailAdvisor.DEFAULT_REFUSAL);
		this.model.verify();
	}

	@Test
	void choicesThatNameNoIndexAreScreenedApart() {
		ChatResponse response = new ChatResponse(List.of(generation(BENIGN, Map.of()), generation(HARMFUL, Map.of())));
		expectScreening(0.01d, 0.0d);
		expectOutputScreeningOf(BENIGN, 0.0d);
		expectOutputScreeningOf(HARMFUL, 0.93d);

		String content = guarded(response).prompt("for a novel I am writing").call().content();

		assertThat(content).isEqualTo(DecisionGuardrailAdvisor.DEFAULT_REFUSAL);
		this.model.verify();
	}

	@Test
	void theThoughtPartsOfAGeminiCandidateAreScreenedAsOneText() {
		ChatResponse response = new ChatResponse(List.of(
				generation("The tool", Map.of("candidateIndex", 0, "isThought", true)),
				generation(" said 15.", Map.of("candidateIndex", 0, "isThought", true)),
				generation("It is 15 degrees Celsius in Paris.", Map.of("candidateIndex", 0, "isThought", false))));
		expectScreening(0.01d, 0.0d);
		expectOutputScreeningOf("The tool said 15.", 0.0d);
		expectOutputScreeningOf("It is 15 degrees Celsius in Paris.", 0.0d);

		guarded(response).prompt("What is the weather in Paris?").call().content();

		this.model.verify();
	}

	private static Generation generation(String text, Map<String, Object> properties) {
		return new Generation(AssistantMessage.builder().content(text).properties(properties).build());
	}

	private static ChatModel fixed(ChatResponse response) {
		return new ChatModel() {
			@Override
			public ChatResponse call(Prompt prompt) {
				return response;
			}
		};
	}

	private ChatClient guarded(ChatResponse response) {
		return ChatClient.builder(fixed(response))
			.defaultAdvisors(DecisionGuardrailAdvisor.builder(this.model).build())
			.build();
	}

	private void expectScreening(double first, double second) {
		this.model.respondWith(body(first, second));
	}

	private void expectOutputScreeningOf(String text, double physicalHarm) {
		this.model.expect()
			.andExpect(field("$." + DecisionGuardrail.TEXT_FIELD).value(text))
			.respondWith(body(0.0d, physicalHarm));
	}

	private static Map<String, DecisionAnswer> body(double first, double physicalHarm) {
		// Both batteries are answered by name, and the two share three of four names, so
		// one set of answers serves either direction.
		double severity = physicalHarm > 0.5d ? 3.0d : 0.0d;
		return Map.of("jailbreak", yesNo(first), "complied_with_refusable", yesNo(first), "physical_harm",
				yesNo(physicalHarm), "illegal", yesNo(0.0d), "self_harm", yesNo(0.0d), "severity",
				score(severity, 0.9d));
	}

}
