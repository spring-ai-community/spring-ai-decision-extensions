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

package org.springaicommunity.decision.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.decision.question.ChoiceQuestion;
import org.springframework.ai.decision.question.DecisionQuestion;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.util.StringUtils;

/**
 * The JSON schema of the type {@code ChatClient.entity(...)} asked for, when it arrived
 * natively in the options, checked against a {@link DecisionChatModel}'s questions.
 *
 * <p>
 * Every field of the schema needs a question of the same name whose answer fits its type:
 * a choice's label into a string (and into every value of an enum), a yes/no probability
 * or a score's value into a floating-point number. Local {@code $ref}s and
 * {@code anyOf}/{@code oneOf} alternatives are followed, {@code null} alternatives are
 * ignored, and a field whose schema declares no type is accepted unchecked. The schema's
 * top level must be a single object, since the reply is one; it may be reached through a
 * {@code $ref}. Questions the type does not ask for are fine; they are left out of the
 * reply.
 *
 * @author Christian Tzolov
 */
final class RequestedSchema {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** Guards against a {@code $ref} cycle; real schemas nest far less deeply. */
	private static final int MAX_DEPTH = 16;

	private final Set<String> fields;

	private RequestedSchema(Set<String> fields) {
		this.fields = Collections.unmodifiableSet(fields);
	}

	/**
	 * Reads and checks the schema carried by the options.
	 * @param options the prompt's options
	 * @param questions the questions the model answers, by name
	 * @return the schema, or {@code null} when the options carry none
	 * @throws IllegalArgumentException when the schema asks for something the questions
	 * cannot supply
	 */
	static @Nullable RequestedSchema of(@Nullable ChatOptions options, Map<String, DecisionQuestion> questions) {
		if (!(options instanceof StructuredOutputChatOptions structured)
				|| !StringUtils.hasText(structured.getOutputSchema())) {
			return null;
		}
		return parse(structured.getOutputSchema(), questions);
	}

	/**
	 * Reads and checks a schema.
	 * @param schema the JSON schema text
	 * @param questions the questions the model answers, by name
	 * @return the schema
	 * @throws IllegalArgumentException when the schema asks for something the questions
	 * cannot supply
	 */
	static RequestedSchema parse(String schema, Map<String, DecisionQuestion> questions) {
		JsonNode root = JSON.readTree(schema);
		// The top level may itself be a $ref, or be wrapped in a nullable anyOf.
		List<JsonNode> shapes = alternatives(root, root, 0).stream()
			.filter(shape -> !"null".equals(shape.path("type").asString("")))
			.toList();
		Set<String> rootTypes = typesOf(shapes);
		if (!rootTypes.isEmpty() && !rootTypes.contains("object")) {
			throw new IllegalArgumentException("DecisionChatModel replies with a JSON object, one field per question, "
					+ "so it cannot produce a " + rootTypes + "; request a record or a class instead");
		}
		if (shapes.size() > 1) {
			throw new IllegalArgumentException("The requested type's schema offers " + shapes.size()
					+ " alternatives at its top level; DecisionChatModel replies with one JSON object, "
					+ "so it cannot choose between them");
		}
		JsonNode object = shapes.isEmpty() ? root : shapes.get(0);
		Set<String> fields = new LinkedHashSet<>();
		for (Map.Entry<String, JsonNode> property : object.path("properties").properties()) {
			String field = property.getKey();
			DecisionQuestion question = questions.get(field);
			if (question == null) {
				throw new IllegalArgumentException("The requested type has a field '" + field
						+ "' that no question answers; the questions are " + questions.keySet());
			}
			List<JsonNode> alternatives = alternatives(property.getValue(), root, 0);
			if (question instanceof ChoiceQuestion choice) {
				requireType(field, alternatives, "string", "a choice's label");
				List<String> values = enumValuesOf(alternatives);
				if (values != null) {
					choice.getOptionNames().forEach(option -> {
						if (!values.contains(option)) {
							throw new IllegalArgumentException("Field '" + field + "' cannot hold the choice option '"
									+ option + "'; its values are " + values);
						}
					});
				}
			}
			else {
				requireType(field, alternatives, "number", "a floating-point number");
			}
			fields.add(field);
		}
		return new RequestedSchema(fields);
	}

	/**
	 * @return the fields the reply must contain, in the schema's order
	 */
	Set<String> fields() {
		return this.fields;
	}

	/**
	 * Flattens a schema into the alternatives a value may match: follows a local
	 * {@code $ref} and expands {@code anyOf} and {@code oneOf}.
	 */
	private static List<JsonNode> alternatives(JsonNode schema, JsonNode root, int depth) {
		if (depth > MAX_DEPTH) {
			return List.of(schema);
		}
		JsonNode ref = schema.path("$ref");
		if (ref.isString() && ref.asString().startsWith("#")) {
			JsonNode target = root.at(ref.asString().substring(1));
			return target.isMissingNode() ? List.of(schema) : alternatives(target, root, depth + 1);
		}
		List<JsonNode> flattened = new ArrayList<>();
		for (String keyword : List.of("anyOf", "oneOf")) {
			JsonNode options = schema.path(keyword);
			if (options.isArray()) {
				options.forEach(option -> flattened.addAll(alternatives(option, root, depth + 1)));
			}
		}
		if (flattened.isEmpty()) {
			flattened.add(schema);
		}
		return flattened;
	}

	/** The declared types across the alternatives, without {@code null}. */
	private static Set<String> typesOf(List<JsonNode> alternatives) {
		Set<String> types = new LinkedHashSet<>();
		for (JsonNode alternative : alternatives) {
			JsonNode type = alternative.path("type");
			if (type.isArray()) {
				type.forEach(value -> types.add(value.asString()));
			}
			else if (type.isString()) {
				types.add(type.asString());
			}
		}
		types.remove("null");
		return types;
	}

	/**
	 * The enum values across the alternatives, or {@code null} when any non-null
	 * alternative accepts any value.
	 */
	private static @Nullable List<String> enumValuesOf(List<JsonNode> alternatives) {
		List<String> values = new ArrayList<>();
		for (JsonNode alternative : alternatives) {
			JsonNode allowed = alternative.path("enum");
			if (allowed.isArray()) {
				allowed.forEach(value -> values.add(value.asString()));
			}
			else if (!"null".equals(alternative.path("type").asString(""))) {
				return null;
			}
		}
		return values;
	}

	/**
	 * Requires the field's declared types to include {@code expected}. An {@code integer}
	 * or {@code boolean} field is rejected for a yes/no question or a score: a value such
	 * as 0.97 or 1.8 would not fit.
	 */
	private static void requireType(String field, List<JsonNode> alternatives, String expected, String answer) {
		Set<String> types = typesOf(alternatives);
		if (!types.isEmpty() && !types.contains(expected)) {
			throw new IllegalArgumentException("Field '" + field + "' is of type " + types + " but receives " + answer
					+ ", which needs " + expected);
		}
	}

}
