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

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.input.TextInput;
import org.springframework.ai.decision.question.ChoiceQuestion;
import org.springframework.ai.decision.question.DecisionQuestion;
import org.springframework.ai.decision.question.ScoreQuestion;
import org.springframework.ai.decision.question.YesNoQuestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link StubDecisionModel}.
 *
 * @author Christian Tzolov
 */
class StubDecisionModelTests {

	private static final Map<String, DecisionQuestion> QUESTIONS = Map.of("urgent",
			YesNoQuestion.of("Does this convey urgency?"), "team",
			ChoiceQuestion.of("Which team should handle this?", "billing", "technical"), "frustration",
			ScoreQuestion.of("How frustrated is the customer?", "Calm", "Frustrated", "Very angry"));

	@Test
	void answersTheQuestionsOfThePromptFromTheScript() {
		StubDecisionModel model = StubDecisionModel.answering(
				Map.of("urgent", StubDecisionModel.yesNo(0.95), "team", StubDecisionModel.choice("billing", 0.82),
						"frustration", StubDecisionModel.score(1.1), "unasked", StubDecisionModel.yesNo(0.1)));

		DecisionResponse response = model.call("My payouts have been failing for 3 days.", QUESTIONS);

		assertThat(response.getYesNo("urgent").isYes()).isTrue();
		assertThat(response.getChoice("team").getValue()).isEqualTo("billing");
		assertThat(response.getChoice("team").getConfidence()).isEqualTo(0.82);
		assertThat(response.getScore("frustration").getNearestLevel()).isEqualTo(1);
		assertThat(response.hasAnswer("unasked")).isFalse();
	}

	@Test
	void recordsThePromptsItWasCalledWith() {
		StubDecisionModel model = StubDecisionModel.answering(Map.of("urgent", StubDecisionModel.yesNo(0.2)));

		model.call("first", Map.of("urgent", YesNoQuestion.of("Does this convey urgency?")));
		model.call("second", Map.of("urgent", YesNoQuestion.of("Does this convey urgency?")));

		assertThat(model.prompts()).hasSize(2);
		assertThat(model.lastPrompt().getInstructions()).isInstanceOfSatisfying(TextInput.class,
				input -> assertThat(input.getText()).isEqualTo("second"));
	}

	@Test
	void reportsARefusalForAnyQuestion() {
		StubDecisionModel model = StubDecisionModel.answering(Map.of("urgent", StubDecisionModel.refusal("policy")));

		DecisionResponse response = model.call("text", Map.of("urgent", YesNoQuestion.of("Does this convey urgency?")));

		assertThat(response.isRefused("urgent")).isTrue();
	}

	@Test
	void failsWhenTheScriptLeavesAQuestionOut() {
		StubDecisionModel model = StubDecisionModel.answering(Map.of("urgent", StubDecisionModel.yesNo(0.9)));

		assertThatIllegalStateException().isThrownBy(() -> model.call("text", QUESTIONS))
			.withMessageContaining("missing answer");
	}

	@Test
	void failsEveryCallWhenToldTo() {
		StubDecisionModel model = StubDecisionModel.failing(new IllegalStateException("provider down"));

		assertThatThrownBy(() -> model.call("text", QUESTIONS)).hasMessage("provider down");
		assertThat(model.prompts()).hasSize(1);
	}

}
