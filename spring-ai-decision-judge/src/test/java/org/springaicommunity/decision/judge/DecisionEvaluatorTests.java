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
import org.springaicommunity.decision.test.StubDecisionModel;

import org.springframework.ai.decision.input.FieldsInput;
import org.springframework.ai.decision.question.YesNoQuestion;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springaicommunity.decision.test.StubDecisionModel.yesNo;

/**
 * The judge behind Spring AI's {@code Evaluator} interface.
 *
 * @author Christian Tzolov
 */
class DecisionEvaluatorTests {

	private final StubDecisionModel model = new StubDecisionModel();

	@Test
	void adaptsAVerdictOntoSpringAiEvaluationResponse() {
		this.model.respondWith(Map.of("is_plausible", yesNo(0.02), "is_relevant", yesNo(0.95)));

		EvaluationResponse result = new DecisionEvaluator(twoQuestionJudge())
			.evaluate(new EvaluationRequest("What is the weather in Paris?", "It is -400 degrees."));

		assertThat(result.isPass()).isFalse();
		// One of two criteria passed.
		assertThat(result.getScore()).isEqualTo(0.5f);
		assertThat(result.getFeedback()).contains("is_plausible");
		assertThat(result.getMetadata()).containsKey(DecisionEvaluator.VERDICT_METADATA_KEY);
		assertThat(result.getMetadata().get(DecisionEvaluator.FINDINGS_METADATA_KEY))
			.isEqualTo(Map.of("is_plausible", "FAILED", "is_relevant", "PASSED"));
		assertThat(((FieldsInput) this.model.lastPrompt().getInstructions()).getFields()).containsExactly(
				Map.entry("user_question", "What is the weather in Paris?"),
				Map.entry("assistant_answer", "It is -400 degrees."));
	}

	@Test
	void passesRetrievedDocumentsIntoTheJudgedInput() {
		// The context is the evidence a groundedness criterion needs, so it must reach
		// the
		// input rather than being dropped on the floor.
		this.model.respondWith(Map.of("is_plausible", yesNo(0.99), "is_relevant", yesNo(0.99)));

		EvaluationResponse result = new DecisionEvaluator(twoQuestionJudge()).evaluate(
				new EvaluationRequest("Where is Paris?", List.of(new Document("Paris is in France.")), "In France."));

		assertThat(result.isPass()).isTrue();
		assertThat(result.getScore()).isEqualTo(1.0f);
		assertThat(((FieldsInput) this.model.lastPrompt().getInstructions()).getFields())
			.containsEntry(DecisionEvaluator.CONTEXT_FIELD, List.of("Paris is in France."));
	}

	@Test
	void leavesCriteriaThatDidNotApplyOutOfThePassRate() {
		this.model.respondWith(Map.of("is_relevant", yesNo(0.99)));

		DecisionJudge judge = DecisionJudge.builder(this.model)
			.criterion(DecisionCriterion
				.yesNo("is_grounded", YesNoQuestion.of("Is `assistant_answer` supported by `supporting_context`?"),
						0.7d)
				.appliesWhen(input -> !input.context().isEmpty()))
			.yesNo("is_relevant", YesNoQuestion.of("Does `assistant_answer` address `user_question`?"), 0.7d)
			.build();

		EvaluationResponse result = new DecisionEvaluator(judge)
			.evaluate(new EvaluationRequest("Where is Paris?", List.of(), "In France."));

		assertThat(result.isPass()).isTrue();
		assertThat(result.getScore()).isEqualTo(1.0f);
	}

	private DecisionJudge twoQuestionJudge() {
		return DecisionJudge.builder(this.model)
			.yesNo("is_plausible",
					YesNoQuestion.builder()
						.text("Are the values plausible?")
						.whenNo("Contains an impossible value")
						.build(),
					0.7d)
			.yesNo("is_relevant", YesNoQuestion.of("Is it relevant?"), 0.5d)
			.build();
	}

}
