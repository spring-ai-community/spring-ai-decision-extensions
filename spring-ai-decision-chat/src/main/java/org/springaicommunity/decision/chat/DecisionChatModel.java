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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.ListOutputConverter;
import org.springframework.ai.converter.MapOutputConverter;
import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.decision.DecisionOptions;
import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.answer.ChoiceAnswer;
import org.springframework.ai.decision.answer.DecisionAnswer;
import org.springframework.ai.decision.answer.ScoreAnswer;
import org.springframework.ai.decision.answer.YesNoAnswer;
import org.springframework.ai.decision.input.DecisionInput;
import org.springframework.ai.decision.question.DecisionQuestion;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * <strong>Experimental.</strong> A {@link DecisionModel} behind Spring AI's
 * {@link ChatModel} interface, so {@code ChatClient} can return typed classifications:
 *
 * <pre>{@code
 * record Triage(String team, double urgent, double severity) {}
 *
 * ChatModel triage = DecisionChatModel.builder(decisionModel)
 *     .question("team", ChoiceQuestion.of("Which team should handle this?", "infra", "billing", "support"))
 *     .question("urgent", YesNoQuestion.of("Does this need attention right now?"))
 *     .question("severity", ScoreQuestion.of("How severe is the impact?", "Cosmetic", "Degraded", "Outage"))
 *     .build();
 *
 * Triage t = ChatClient.create(triage)
 *     .prompt()
 *     .user(ticketText)
 *     .call()
 *     .entity(Triage.class, spec -> spec.useProviderStructuredOutput());
 * }</pre>
 *
 * <p>
 * This is a classifier, not a generator. The questions are fixed when the model is built;
 * the prompt only supplies the input they are answered against. The reply is the answers
 * as a JSON object, one field per question: a yes/no question's probability of yes, a
 * choice's label, a score's value. That is the shape {@code entity(...)} maps onto a
 * record. The full {@link DecisionResponse}, with probabilities and confidence, rides in
 * the generation metadata under {@link #RESPONSE_METADATA_KEY}.
 *
 * <p>
 * Prefer {@code ChatClient}'s native structured output,
 * {@code .entity(Triage.class, spec -> spec.useProviderStructuredOutput())}. The record's
 * JSON schema then arrives in the options rather than as instructions appended to the
 * user message, so the input stays exactly what the caller wrote. The schema is also
 * checked against the questions before any call: a record field with no question, or with
 * a type the answer cannot fill, fails with a message naming the field, and the reply
 * then holds exactly the fields the record declares. Without it, the format instructions
 * Spring AI appends to the last user message are removed, but only when the message ends
 * with exactly the block {@code BeanOutputConverter} or {@code MapOutputConverter}
 * produces, so nothing the user wrote is ever cut. Only JSON-object output is supported,
 * so list output ({@code ListOutputConverter}) is rejected.
 *
 * <p>
 * What a chat model normally does and this one cannot is refused rather than faked:
 * streaming is unsupported; {@link #getOptions()} are not tool-calling options, so
 * {@code ChatClient} never runs its tool loop and drops any tools it was given; and a
 * prompt that carries tool callbacks directly is rejected. With the default input a
 * message carrying media is rejected too; supply an {@link Builder#inputConverter input
 * converter} to send media to a decision model that reads it.
 *
 * <p>
 * Do not declare a {@code DecisionChatModel} as a Spring bean next to the application's
 * real chat model: with two {@code ChatModel} beans, injecting Spring AI's
 * {@code ChatClient.Builder} or a bare {@code ChatModel} fails with
 * {@code NoUniqueBeanDefinitionException}. Build it where its {@code ChatClient} is
 * created, and expose that client instead. Memory or retrieval advisors do work, but only
 * as a way to put more text into the input.
 *
 * <p>
 * Each call is observed like any other Spring AI chat model: a
 * {@code gen_ai.client.operation} observation with the {@link Builder#provider provider}
 * name, the requested and returned model, the response id and the token usage, so the
 * standard {@code ChatModelMeterObservationHandler} records
 * {@code gen_ai.client.token.usage}. Pass an {@link ObservationRegistry} to the builder
 * to enable it. The decision model's own observation, if it has one, nests inside.
 *
 * @author Christian Tzolov
 */
public final class DecisionChatModel implements ChatModel {

	/**
	 * The provider name reported on observations unless the builder is given another. The
	 * DecisionModel API does not say which provider is behind a model.
	 */
	public static final String DEFAULT_PROVIDER = "decision";

	/** Generation metadata key holding the full {@link DecisionResponse}. */
	public static final String RESPONSE_METADATA_KEY = "decisionResponse";

	/** Input field holding the system message, when there is one. */
	public static final String SYSTEM_FIELD = "system";

	/** Input field holding the conversation, as {@code {role, content}} entries. */
	public static final String MESSAGES_FIELD = "messages";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * The format instructions {@code ChatClient.entity(...)} appends to the user message
	 * on the prompt-based path. They tell a generator how to reply; this model's reply
	 * shape is fixed, so the default input drops them. The wording is taken from Spring
	 * AI's own converters rather than copied, so it follows the version in use: the text
	 * {@code BeanOutputConverter} puts before and after the JSON schema, and the whole of
	 * what {@code MapOutputConverter} and {@code ListOutputConverter} append.
	 */
	private static final String BEAN_FORMAT_PREFIX;

	private static final String BEAN_FORMAT_SUFFIX;

	private static final String MAP_FORMAT = new MapOutputConverter().getFormat().stripTrailing();

	private static final String LIST_FORMAT = new ListOutputConverter().getFormat().stripTrailing();

	static {
		BeanOutputConverter<FormatProbe> converter = new BeanOutputConverter<>(FormatProbe.class);
		String format = converter.getFormat().stripTrailing();
		int schema = format.indexOf(converter.getJsonSchema());
		Assert.state(schema > 0, "BeanOutputConverter's format instructions no longer embed its JSON schema");
		BEAN_FORMAT_PREFIX = format.substring(0, schema);
		BEAN_FORMAT_SUFFIX = format.substring(schema + converter.getJsonSchema().length());
	}

	/** Any type will do: only the wording around its schema is read. */
	private record FormatProbe(String value) {
	}

	private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION = new DefaultChatModelObservationConvention();

	private final DecisionModel decisionModel;

	private final @Nullable DecisionOptions options;

	private final Map<String, DecisionQuestion> questions;

	private final Function<Prompt, DecisionInput> inputConverter;

	private final Function<DecisionResponse, String> answerRenderer;

	private final String provider;

	private final ObservationRegistry observationRegistry;

	private final @Nullable ChatModelObservationConvention observationConvention;

	/**
	 * Whether the reply is the default JSON object, whose fields a schema can be checked
	 * against.
	 */
	private final boolean defaultRenderer;

	private DecisionChatModel(Builder builder) {
		this.decisionModel = builder.decisionModel;
		this.options = builder.options;
		this.questions = Collections.unmodifiableMap(new LinkedHashMap<>(builder.questions));
		this.inputConverter = builder.inputConverter;
		this.answerRenderer = builder.answerRenderer;
		this.defaultRenderer = builder.defaultRenderer;
		this.provider = builder.provider;
		this.observationRegistry = builder.observationRegistry;
		this.observationConvention = builder.observationConvention;
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		Assert.notNull(prompt, "prompt must not be null");
		rejectTools(prompt.getOptions());
		rejectListOutput(prompt);
		// A custom renderer owns the reply's shape, so the requested schema is only read,
		// and
		// checked against the questions, for the default JSON reply.
		RequestedSchema schema = this.defaultRenderer ? RequestedSchema.of(prompt.getOptions(), this.questions) : null;
		Set<String> requestedFields = (schema != null) ? schema.fields() : null;
		// The model actually used goes into the observed prompt too, so a direct call
		// with
		// no options still reports it as the request model.
		Prompt requested = withModel(prompt);

		ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
			.prompt(requested)
			.provider(this.provider)
			.build();
		ChatResponse chatResponse = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry)
			.observe(() -> {
				ChatResponse response = classify(requested, requestedFields);
				observationContext.setResponse(response);
				return response;
			});
		Assert.state(chatResponse != null, "the observed call returned no response");
		return chatResponse;
	}

	private ChatResponse classify(Prompt prompt, @Nullable Set<String> requestedFields) {
		DecisionResponse response = this.decisionModel.call(DecisionPrompt.builder()
			.input(this.inputConverter.apply(prompt))
			.questions(this.questions)
			.options(optionsFor(prompt.getOptions()))
			.build());

		ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
			.finishReason("STOP")
			.metadata(RESPONSE_METADATA_KEY, response)
			.build();
		// With a schema, reply with exactly the fields it asks for: a schema that forbids
		// additional properties would otherwise reject the answers the record does not
		// use.
		String reply = (this.defaultRenderer && requestedFields != null) ? answersAsJson(response, requestedFields)
				: this.answerRenderer.apply(response);
		Generation generation = new Generation(new AssistantMessage(reply), generationMetadata);

		ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder().usage(response.getMetadata().getUsage());
		if (response.getMetadata().getModel() != null) {
			metadata.model(response.getMetadata().getModel());
		}
		if (response.getMetadata().getId() != null) {
			metadata.id(response.getMetadata().getId());
		}
		return new ChatResponse(List.of(generation), metadata.build());
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		return Flux.error(new UnsupportedOperationException("DecisionChatModel does not stream: "
				+ "a decision model answers every question at once, as numbers, not as generated text"));
	}

	/**
	 * Structured-output options, so {@code ChatClient} can hand over the record's schema
	 * natively; deliberately not {@code ToolCallingChatOptions}: a decision model cannot
	 * call tools, so {@code ChatClient} must not start its tool loop.
	 */
	@Override
	public ChatOptions getOptions() {
		return StructuredOutputChatOptions.builder().model(defaultModel()).build();
	}

	/**
	 * @return the questions every call answers, in declaration order
	 */
	public Map<String, DecisionQuestion> questions() {
		return this.questions;
	}

	private @Nullable String defaultModel() {
		return this.options != null ? this.options.getModel() : null;
	}

	/**
	 * The decision options of one call: the builder's, with the model the chat options
	 * name, when they name one.
	 */
	private @Nullable DecisionOptions optionsFor(@Nullable ChatOptions chatOptions) {
		if (chatOptions == null || !StringUtils.hasText(chatOptions.getModel())
				|| chatOptions.getModel().equals(defaultModel())) {
			return this.options;
		}
		DecisionOptions.Builder<?> builder = (this.options != null) ? this.options.mutate() : DecisionOptions.builder();
		return builder.model(chatOptions.getModel()).build();
	}

	/**
	 * {@code ListOutputConverter} asks for comma-separated values; the reply is a JSON
	 * object, which it would split into nonsense. Recognised by the last user message
	 * ending with exactly its instructions, and never on the native path, where nothing
	 * is appended: a user who merely writes that sentence is not asking for a list.
	 */
	private static void rejectListOutput(Prompt prompt) {
		String last = lastUserText(prompt);
		if (last != null && !carriesNativeSchema(prompt) && endsWithBlock(last.stripTrailing(), LIST_FORMAT)) {
			throw new IllegalArgumentException("DecisionChatModel replies with a JSON object, one field per question; "
					+ "list output (ListOutputConverter) is not supported, request a record or a class instead");
		}
	}

	private Prompt withModel(Prompt prompt) {
		ChatOptions chatOptions = prompt.getOptions();
		String defaultModel = defaultModel();
		if (!StringUtils.hasText(defaultModel)
				|| (chatOptions != null && StringUtils.hasText(chatOptions.getModel()))) {
			return prompt;
		}
		ChatOptions withModel = (chatOptions == null) ? getOptions() : chatOptions.mutate().model(defaultModel).build();
		return prompt.mutate().chatOptions(withModel).build();
	}

	/**
	 * Whether the prompt's options carry the requested type's schema, which is how
	 * {@code ChatClient} hands it over on the native path instead of appending format
	 * instructions to the user message.
	 */
	private static boolean carriesNativeSchema(Prompt prompt) {
		return prompt.getOptions() instanceof StructuredOutputChatOptions structured
				&& StringUtils.hasText(structured.getOutputSchema());
	}

	/**
	 * Whether {@code text} ends with {@code block}, the block starting on its own line.
	 */
	private static boolean endsWithBlock(String text, String block) {
		if (!text.endsWith(block)) {
			return false;
		}
		int start = text.length() - block.length();
		return start == 0 || text.charAt(start - 1) == '\n';
	}

	private static @Nullable String lastUserText(Prompt prompt) {
		List<Message> instructions = prompt.getInstructions();
		for (int i = instructions.size() - 1; i >= 0; i--) {
			if (instructions.get(i).getMessageType() == MessageType.USER) {
				return instructions.get(i).getText();
			}
		}
		return null;
	}

	private static void rejectTools(@Nullable ChatOptions options) {
		if (options instanceof ToolCallingChatOptions toolOptions && toolOptions.getToolCallbacks() != null
				&& !toolOptions.getToolCallbacks().isEmpty()) {
			throw new IllegalArgumentException(
					"DecisionChatModel cannot call tools; it classifies, it does not generate");
		}
	}

	/**
	 * The default input: the fields {@code {"system": ..., "messages": [{"role",
	 * "content"}, ...]}}, with the system message left out when there is none, and with
	 * the format instructions {@code ChatClient.entity(...)} appends to the user message
	 * {@linkplain #withoutFormatInstructions removed}. A prompt whose options carry the
	 * schema natively had nothing appended, so its messages are sent exactly as written.
	 * @param prompt the prompt
	 * @return the input
	 * @throws IllegalArgumentException when a message carries media
	 */
	public static DecisionInput defaultInput(Prompt prompt) {
		List<Message> instructions = prompt.getInstructions();
		int lastUser = -1;
		for (int i = 0; i < instructions.size(); i++) {
			if (instructions.get(i).getMessageType() == MessageType.USER) {
				lastUser = i;
			}
		}
		boolean formatAppended = !carriesNativeSchema(prompt);
		Map<String, @Nullable Object> fields = new LinkedHashMap<>();
		List<String> system = new ArrayList<>();
		List<Map<String, String>> messages = new ArrayList<>();
		for (int i = 0; i < instructions.size(); i++) {
			Message message = instructions.get(i);
			if (message instanceof MediaContent media && !media.getMedia().isEmpty()) {
				throw new IllegalArgumentException("The default input of a DecisionChatModel carries text only, but a "
						+ message.getMessageType().getValue()
						+ " message carries media; supply an inputConverter to send it");
			}
			String text = message.getText();
			if (!StringUtils.hasText(text)) {
				continue;
			}
			if (message.getMessageType() == MessageType.SYSTEM) {
				system.add(text);
			}
			else if (message.getMessageType() == MessageType.USER
					|| message.getMessageType() == MessageType.ASSISTANT) {
				// Format instructions are only ever appended to the latest user message.
				String content = (formatAppended && i == lastUser) ? withoutFormatInstructions(text) : text;
				Map<String, String> entry = new LinkedHashMap<>();
				entry.put("role", message.getMessageType().getValue());
				entry.put("content", content);
				messages.add(entry);
			}
		}
		if (!system.isEmpty()) {
			fields.put(SYSTEM_FIELD, String.join("\n\n", system));
		}
		fields.put(MESSAGES_FIELD, messages);
		return DecisionInput.fields(fields);
	}

	/**
	 * The default reply: the answers as a JSON object, one field per question — a yes/no
	 * question's probability of yes, a choice's label, a score's value. A question the
	 * model refused to answer is {@code null}.
	 * @param response the response
	 * @return the JSON text
	 */
	public static String answersAsJson(DecisionResponse response) {
		return answersAsJson(response, null);
	}

	/**
	 * The answers as a JSON object, limited to {@code fields} when given.
	 * @param response the response
	 * @param fields the fields to include, in their order; {@code null} for every answer
	 * @return the JSON text
	 */
	public static String answersAsJson(DecisionResponse response, @Nullable Set<String> fields) {
		Map<String, @Nullable Object> answers = new LinkedHashMap<>();
		if (fields == null) {
			response.getAnswers().forEach((name, answer) -> answers.put(name, valueOf(answer)));
		}
		else {
			fields.forEach(field -> {
				DecisionAnswer answer = response.getAnswers().get(field);
				answers.put(field, answer == null ? null : valueOf(answer));
			});
		}
		return JSON.writeValueAsString(answers);
	}

	private static @Nullable Object valueOf(DecisionAnswer answer) {
		if (answer instanceof YesNoAnswer yesNo) {
			return yesNo.getProbability();
		}
		if (answer instanceof ChoiceAnswer choice) {
			return choice.getValue();
		}
		if (answer instanceof ScoreAnswer score) {
			return score.getValue();
		}
		return null;
	}

	/**
	 * Removes the JSON format instructions {@code ChatClient.entity(...)} appends to a
	 * user message on the prompt-based path. The message must end with exactly the block
	 * {@code BeanOutputConverter} produces, its schema slot holding a JSON object, or
	 * with exactly what {@code MapOutputConverter} produces, the block starting on its
	 * own line. Anything else is returned unchanged: a message that quotes some of those
	 * sentences, or carries text after them, is the user's content. The default input
	 * applies it to the last user message; a custom {@code inputConverter} can call it
	 * too.
	 * @param text a user message's text
	 * @return the text without the appended instructions
	 */
	public static String withoutFormatInstructions(String text) {
		String body = text.stripTrailing();
		if (endsWithBlock(body, MAP_FORMAT)) {
			return body.substring(0, body.length() - MAP_FORMAT.length()).stripTrailing();
		}
		int start = body.lastIndexOf(BEAN_FORMAT_PREFIX);
		if (start < 0 || (start > 0 && body.charAt(start - 1) != '\n') || !body.endsWith(BEAN_FORMAT_SUFFIX)
				|| start + BEAN_FORMAT_PREFIX.length() > body.length() - BEAN_FORMAT_SUFFIX.length()) {
			return text;
		}
		String schema = body.substring(start + BEAN_FORMAT_PREFIX.length(),
				body.length() - BEAN_FORMAT_SUFFIX.length());
		return isJsonObject(schema) ? body.substring(0, start).stripTrailing() : text;
	}

	private static boolean isJsonObject(String text) {
		try {
			return JSON.readTree(text).isObject();
		}
		catch (RuntimeException ex) {
			return false;
		}
	}

	/**
	 * @param decisionModel the model the questions are put to
	 * @return a new builder
	 */
	public static Builder builder(DecisionModel decisionModel) {
		return new Builder(decisionModel);
	}

	/**
	 * Builder for {@link DecisionChatModel}.
	 */
	public static final class Builder {

		private final DecisionModel decisionModel;

		private @Nullable DecisionOptions options;

		private final Map<String, DecisionQuestion> questions = new LinkedHashMap<>();

		private Function<Prompt, DecisionInput> inputConverter = DecisionChatModel::defaultInput;

		private Function<DecisionResponse, String> answerRenderer = DecisionChatModel::answersAsJson;

		private boolean defaultRenderer = true;

		private String provider = DEFAULT_PROVIDER;

		private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

		private @Nullable ChatModelObservationConvention observationConvention;

		private Builder(DecisionModel decisionModel) {
			Assert.notNull(decisionModel, "decisionModel must not be null");
			this.decisionModel = decisionModel;
		}

		/**
		 * Sets the options sent with every call to the decision model. Their model name
		 * is also what {@link DecisionChatModel#getOptions()} and the observations report
		 * as the requested model; a prompt whose chat options name a model overrides it
		 * for that call.
		 * @param options the options; {@code null} for the model's defaults
		 * @return this builder
		 */
		public Builder options(@Nullable DecisionOptions options) {
			this.options = options;
			return this;
		}

		/**
		 * Adds a question every call answers. Its name is the field of the reply, so name
		 * it after the record component it should land in.
		 * @param name the answer's name
		 * @param question the question
		 * @return this builder
		 */
		public Builder question(String name, DecisionQuestion question) {
			Assert.hasText(name, "name must not be empty");
			Assert.notNull(question, "question must not be null");
			Assert.isTrue(!this.questions.containsKey(name), "a question named '" + name + "' is already declared");
			this.questions.put(name, question);
			return this;
		}

		/**
		 * @param questions questions every call answers, keyed by name, in the map's
		 * iteration order: pass a {@code LinkedHashMap} rather than {@code Map.of}, whose
		 * order changes with every JVM run
		 * @return this builder
		 */
		public Builder questions(Map<String, ? extends DecisionQuestion> questions) {
			Assert.notNull(questions, "questions must not be null");
			questions.forEach(this::question);
			return this;
		}

		/**
		 * Replaces how a prompt becomes the input the questions are answered against, for
		 * example to send the conversation as a {@link DecisionInput#conversation(List)
		 * conversation input} or to carry media.
		 * @param inputConverter the converter; {@link DecisionChatModel#defaultInput} by
		 * default
		 * @return this builder
		 */
		public Builder inputConverter(Function<Prompt, DecisionInput> inputConverter) {
			Assert.notNull(inputConverter, "inputConverter must not be null");
			this.inputConverter = inputConverter;
			return this;
		}

		/**
		 * Replaces how the answers become the reply's text.
		 * @param answerRenderer the renderer; {@link DecisionChatModel#answersAsJson} by
		 * default
		 * @return this builder
		 */
		public Builder answerRenderer(Function<DecisionResponse, String> answerRenderer) {
			Assert.notNull(answerRenderer, "answerRenderer must not be null");
			this.answerRenderer = answerRenderer;
			this.defaultRenderer = false;
			return this;
		}

		/**
		 * Names the provider reported on observations, such as {@code openai} or
		 * {@code ollama}.
		 * @param provider the provider name; {@link #DEFAULT_PROVIDER} by default
		 * @return this builder
		 */
		public Builder provider(String provider) {
			Assert.hasText(provider, "provider must not be empty");
			this.provider = provider;
			return this;
		}

		/**
		 * Observes each call as a {@code gen_ai.client.operation}. Not observed by
		 * default.
		 * @param observationRegistry the registry
		 * @return this builder
		 */
		public Builder observationRegistry(ObservationRegistry observationRegistry) {
			Assert.notNull(observationRegistry, "observationRegistry must not be null");
			this.observationRegistry = observationRegistry;
			return this;
		}

		/**
		 * Replaces the observation convention, for example to add key values of your own.
		 * @param observationConvention the convention; Spring AI's
		 * {@link DefaultChatModelObservationConvention} by default
		 * @return this builder
		 */
		public Builder observationConvention(ChatModelObservationConvention observationConvention) {
			Assert.notNull(observationConvention, "observationConvention must not be null");
			this.observationConvention = observationConvention;
			return this;
		}

		public DecisionChatModel build() {
			Assert.notEmpty(this.questions, "a DecisionChatModel must declare at least one question");
			return new DecisionChatModel(this);
		}

	}

}
