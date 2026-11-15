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

import java.util.function.Consumer;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import org.hamcrest.Matcher;
import org.hamcrest.MatcherAssert;

import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.input.FieldsInput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * An assertion on one value of the fields a {@link DecisionPrompt} carries, addressed by
 * a JSON path into them: {@code $.assistant_answer}, {@code $.tool_calls[0].name},
 * {@code $.tool_calls.length()}. For {@link StubDecisionModel.Expectation#andExpect}.
 *
 * @author Christian Tzolov
 */
public final class PromptField {

	private final String path;

	private PromptField(String path) {
		this.path = path;
	}

	/**
	 * @param path a JSON path into the fields of the prompt's {@link FieldsInput}
	 */
	public static PromptField field(String path) {
		return new PromptField(path);
	}

	/**
	 * The value at the path equals {@code expected}.
	 */
	public Consumer<DecisionPrompt> value(Object expected) {
		return prompt -> assertThat(read(prompt)).as(this.path).isEqualTo(expected);
	}

	/**
	 * The value at the path satisfies {@code matcher}.
	 */
	public Consumer<DecisionPrompt> value(Matcher<?> matcher) {
		return prompt -> MatcherAssert.assertThat(this.path, read(prompt), cast(matcher));
	}

	/**
	 * The fields hold nothing at the path.
	 */
	public Consumer<DecisionPrompt> doesNotExist() {
		return prompt -> {
			try {
				Object value = read(prompt);
				fail("expected no value at %s but found %s", this.path, value);
			}
			catch (PathNotFoundException ex) {
				// As expected.
			}
		};
	}

	private Object read(DecisionPrompt prompt) {
		assertThat(prompt.getInstructions()).isInstanceOf(FieldsInput.class);
		return JsonPath.read(((FieldsInput) prompt.getInstructions()).getFields(), this.path);
	}

	@SuppressWarnings("unchecked")
	private static Matcher<Object> cast(Matcher<?> matcher) {
		return (Matcher<Object>) matcher;
	}

}
