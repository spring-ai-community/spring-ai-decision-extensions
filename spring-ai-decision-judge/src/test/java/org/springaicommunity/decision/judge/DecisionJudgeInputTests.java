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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.ai.decision.input.DecisionInput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The typed view and the judged input are one map, so they cannot drift apart.
 *
 * @author Christian Tzolov
 */
class DecisionJudgeInputTests {

	@Test
	void buildsTheInputUnderTheDocumentedFieldNames() {
		DecisionJudgeInput input = DecisionJudgeInput.builder()
			.question("q")
			.answer("a")
			.expected("e")
			.context(List.of("d1", "d2"))
			.toolCall(new DecisionJudgeInput.ToolCall("search", "{}", "r"))
			.field("locale", "en")
			.build();

		assertThat(input.toInput().getFields()).containsOnlyKeys(DecisionJudgeInput.QUESTION_FIELD,
				DecisionJudgeInput.ANSWER_FIELD, DecisionJudgeInput.EXPECTED_FIELD, DecisionJudgeInput.CONTEXT_FIELD,
				DecisionJudgeInput.TOOL_CALLS_FIELD, "locale");
		assertThat(input.question()).isEqualTo("q");
		assertThat(input.answer()).isEqualTo("a");
		assertThat(input.expected()).isEqualTo("e");
		assertThat(input.context()).containsExactly("d1", "d2");
		assertThat(input.toolCalls()).containsExactly(new DecisionJudgeInput.ToolCall("search", "{}", "r"));
		assertThat(input.field("locale", String.class)).isEqualTo("en");
	}

	@Test
	void sendsAToolCallAsAnObjectThatKeepsWhatIsNotKnownYet() {
		DecisionJudgeInput input = DecisionJudgeInput.builder()
			.toolCall(new DecisionJudgeInput.ToolCall("search", "{}", null))
			.build();

		Map<String, Object> call = new LinkedHashMap<>();
		call.put("name", "search");
		call.put("arguments", "{}");
		call.put("result", null);
		assertThat(input.toInput().getFields())
			.containsExactly(Map.entry(DecisionJudgeInput.TOOL_CALLS_FIELD, List.of(call)));
	}

	@Test
	void leavesEmptyEvidenceOutOfTheInput() {
		// "No evidence" and "evidence that says nothing" must stay distinguishable.
		DecisionJudgeInput input = DecisionJudgeInput.builder().question("q").answer("a").context(List.of()).build();

		assertThat(input.toInput().getFields()).containsOnlyKeys(DecisionJudgeInput.QUESTION_FIELD,
				DecisionJudgeInput.ANSWER_FIELD);
		assertThat(input.context()).isEmpty();
		assertThat(input.toolCalls()).isEmpty();
	}

	@Test
	void readsToolCallsFromFieldsBuiltElsewhere() {
		DecisionJudgeInput input = DecisionJudgeInput
			.of(DecisionInput.fields(Map.of(DecisionJudgeInput.TOOL_CALLS_FIELD,
					List.of("web_search", Map.of("name", "weather", "result", "15")))));

		assertThat(input.toolCalls()).containsExactly(new DecisionJudgeInput.ToolCall("web_search", null, null),
				new DecisionJudgeInput.ToolCall("weather", null, "15"));
	}

	@Test
	void namesTheFieldWhenItsValueHasTheWrongType() {
		DecisionJudgeInput input = DecisionJudgeInput.builder().field("search_required", "yes").build();

		assertThatIllegalStateException().isThrownBy(() -> input.field("search_required", Boolean.class))
			.withMessageContaining("search_required");
	}

	@Test
	void rejectsAValueTheModelInputCannotCarryWhereItIsGiven() {
		record Reference(String text) {
		}

		assertThatIllegalArgumentException()
			.isThrownBy(() -> DecisionJudgeInput.builder().expected(new Reference("Rain is likely")).build())
			.withMessageContaining("expected_output");
	}

	@Test
	void hasNoInputToSendWhenItHasNoFields() {
		DecisionJudgeInput input = DecisionJudgeInput.builder().build();

		assertThat(input.fields()).isEmpty();
		assertThatIllegalArgumentException().isThrownBy(input::toInput);
	}

	@Test
	void keepsTheListFieldsBehindTheirOwnBuilderMethods() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> DecisionJudgeInput.builder().field(DecisionJudgeInput.TOOL_CALLS_FIELD, List.of()));
	}

}
