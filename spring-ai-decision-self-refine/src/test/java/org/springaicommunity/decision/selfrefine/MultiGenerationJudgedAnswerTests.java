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

package org.springaicommunity.decision.selfrefine;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.decision.judge.DecisionJudge;
import org.springaicommunity.decision.test.StubDecisionModel;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.decision.question.YesNoQuestion;

import static org.springaicommunity.decision.test.PromptField.field;
import static org.springaicommunity.decision.test.StubDecisionModel.yesNo;

/**
 * Responses that carry more than one generation, shaped as Spring AI's providers build
 * them: Anthropic puts each thinking block ahead of the answer, Google GenAI emits one
 * generation per part (flagged {@code isThought}) and flattens all candidates into the
 * same list. {@code ChatClient.content()} returns the first generation.
 * <p>
 * What the self-refine advisor judges of such a response.
 *
 * @author Christian Tzolov
 */
class MultiGenerationJudgedAnswerTests {

	private final StubDecisionModel model = new StubDecisionModel();

	@Test
	void aGeminiAnswerSplitAcrossPartsIsJudgedWhole() {
		// [thought, text, text]: the answer is the two text parts, not the last fragment.
		ChatResponse response = new ChatResponse(
				List.of(generation("The tool said 15.", Map.of("candidateIndex", 0, "isThought", true)),
						generation("It is 15 degrees", Map.of("candidateIndex", 0, "isThought", false)),
						generation(" Celsius in Paris.", Map.of("candidateIndex", 0, "isThought", false))));

		assertJudged(response, "It is 15 degrees Celsius in Paris.");
	}

	@Test
	void onlyTheFirstOpenAiChoiceIsJudged() {
		ChatResponse response = new ChatResponse(
				List.of(generation("It is 15 degrees Celsius in Paris.", Map.of("index", 0)),
						generation("It is 99 degrees Celsius in Paris.", Map.of("index", 1))));

		assertJudged(response, "It is 15 degrees Celsius in Paris.");
	}

	@Test
	void redactedThinkingAheadOfAnIndexedAnswerIsLeftOut() {
		ChatResponse response = new ChatResponse(List.of(generation("", Map.of("data", "redacted")),
				generation("It is 15 degrees Celsius in Paris.", Map.of("index", 0))));

		assertJudged(response, "It is 15 degrees Celsius in Paris.");
	}

	@Test
	void onlyTheFirstOfChoicesThatNameNoIndexIsJudged() {
		ChatResponse response = new ChatResponse(List.of(generation("It is 15 degrees Celsius in Paris.", Map.of()),
				generation("It is 99 degrees Celsius in Paris.", Map.of())));

		assertJudged(response, "It is 15 degrees Celsius in Paris.");
	}

	@Test
	void aFirstCandidateOfOnlyThoughtsIsNotJudgedByTheNextOnesAnswer() {
		// The caller reads candidate 0, which never got to an answer.
		ChatResponse response = new ChatResponse(List.of(
				generation("The tool said", Map.of("candidateIndex", 0, "isThought", true)),
				generation("It is 15 degrees Celsius in Paris.", Map.of("candidateIndex", 1, "isThought", false))));

		assertJudged(response, "");
	}

	private void assertJudged(ChatResponse response, String answer) {
		this.model.expect()
			.andExpect(field("$.assistant_answer").value(answer))
			.respondWith(Map.of("is_helpful", yesNo(0.97)));

		ChatClient.builder(fixed(response))
			.defaultAdvisors(DecisionSelfRefineAdvisor.builder()
				.judge(DecisionJudge.builder(this.model)
					.yesNo("is_helpful", YesNoQuestion.of("Does `assistant_answer` help with `user_question`?"), 0.7d)
					.build())
				.build())
			.build()
			.prompt("What is the weather in Paris?")
			.call()
			.content();

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

}
