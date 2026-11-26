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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springaicommunity.decision.judge.DecisionJudge;
import org.springaicommunity.decision.test.ScriptedChatModel;
import org.springaicommunity.decision.test.StubDecisionModel;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.FinishReasonCategory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.GuardrailRefusal;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.decision.answer.DecisionAnswer;
import org.springframework.ai.decision.question.ScoreQuestion;
import org.springframework.ai.decision.question.YesNoQuestion;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.MessageFilter;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springaicommunity.decision.test.PromptField.field;
import static org.springaicommunity.decision.test.StubDecisionModel.score;
import static org.springaicommunity.decision.test.StubDecisionModel.yesNo;

/**
 * The self-refine loop: judge every answer, feed the defect back, try again.
 *
 * @author Christian Tzolov
 */
class DecisionSelfRefineAdvisorTests {

	private static final ScoreQuestion HELPFULNESS = ScoreQuestion.builder()
		.text("How well does `assistant_answer` address `user_question`?")
		.level("Terrible: irrelevant or off-topic")
		.level("Mostly unhelpful: misses the main point")
		.level("Mostly helpful: minor gaps remain")
		.level("Excellent: fully and correctly addressed")
		.build();

	private static final YesNoQuestion PLAUSIBLE = YesNoQuestion.builder()
		.text("Are the values in `assistant_answer` physically plausible?")
		.whenNo("Contains an impossible or absurd value")
		.build();

	private static final Map<String, DecisionAnswer> PASSING = Map.of("helpfulness",
			score(3.0, 0.9, 0.0, 0.0, 0.0, 1.0), "is_plausible", yesNo(0.97));

	private static final Map<String, DecisionAnswer> FAILING = Map.of("helpfulness",
			score(3.0, 0.9, 0.0, 0.0, 0.0, 1.0), "is_plausible", yesNo(0.03));

	private static final Map<String, DecisionAnswer> UNHELPFUL = Map.of("helpfulness",
			score(0.0, 0.9, 1.0, 0.0, 0.0, 0.0), "is_plausible", yesNo(0.97));

	private static final Map<String, DecisionAnswer> FAILING_BOTH = Map.of("helpfulness",
			score(0.4, 0.9, 1.0, 0.0, 0.0, 0.0), "is_plausible", yesNo(0.03));

	private final StubDecisionModel model = new StubDecisionModel();

	@Test
	void returnsTheFirstAnswerWhenItPasses() {
		expectEvaluation(PASSING);
		ScriptedChatModel chatModel = new ScriptedChatModel("It is 15 degrees Celsius in Paris.");

		String content = chatClient(chatModel, 3).prompt("What is the weather in Paris?").call().content();

		assertThat(content).isEqualTo("It is 15 degrees Celsius in Paris.");
		assertThat(chatModel.callCount()).isEqualTo(1);
		this.model.verify();
	}

	@Test
	void retriesWithTheSynthesisedFeedbackAndReturnsTheCorrectedAnswer() {
		expectEvaluation(FAILING);
		expectEvaluation(PASSING);
		ScriptedChatModel chatModel = new ScriptedChatModel("It is -255 degrees Celsius in Paris.",
				"It is 15 degrees Celsius in Paris.");

		String content = chatClient(chatModel, 3).prompt("What is the weather in Paris?").call().content();

		assertThat(content).isEqualTo("It is 15 degrees Celsius in Paris.");
		assertThat(chatModel.callCount()).isEqualTo(2);

		// The first attempt sees only the question; the second carries the defect the
		// judge
		// found, worded from the rubric rather than generated by a judge model.
		assertThat(chatModel.userMessageOfCall(0)).isEqualTo("What is the weather in Paris?");
		assertThat(chatModel.userMessageOfCall(1)).contains("What is the weather in Paris?")
			.contains("Your previous answer was rejected by an automated evaluation")
			.contains("is_plausible: Contains an impossible or absurd value (scored 0.03, needs at least 0.70)")
			.contains("Answer again, correcting every point above.");

		this.model.verify();
	}

	@Test
	void tellsARetryWhatWasRejectedAndWhichEarlierDefectsMustNotComeBack() {
		// Without the rejected answer the model corrects blind, and without the earlier
		// defects it can fix one by bringing another back.
		expectEvaluation(FAILING);
		expectEvaluation(UNHELPFUL);
		expectEvaluation(PASSING);
		ScriptedChatModel chatModel = new ScriptedChatModel("It is -255 degrees Celsius in Paris.", "No idea.",
				"It is 15 degrees Celsius in Paris.");

		chatClient(chatModel, 3).prompt("What is the weather in Paris?").call().content();

		assertThat(chatModel.userMessageOfCall(1)).startsWith("What is the weather in Paris?")
			.contains("- is_plausible: Contains an impossible or absurd value")
			.contains("<rejected_answer>" + System.lineSeparator() + "It is -255 degrees Celsius in Paris."
					+ System.lineSeparator() + "</rejected_answer>")
			.doesNotContain("Answers before it were rejected");
		assertThat(chatModel.userMessageOfCall(2)).startsWith("What is the weather in Paris?")
			.contains("for these reasons:" + System.lineSeparator() + "- helpfulness: rated")
			.contains("Answers before it were rejected for these reasons, which must not come back:"
					+ System.lineSeparator() + "- is_plausible: Contains an impossible or absurd value")
			.contains("<rejected_answer>" + System.lineSeparator() + "No idea.")
			// Only the latest rejected answer is quoted, so the prompt does not grow.
			.doesNotContain("-255 degrees");
		// Still one user message: an advisor further in finds the question where it was.
		assertThat(chatModel.receivedPrompts()).allSatisfy(prompt -> assertThat(prompt.getInstructions()).hasSize(1));
		this.model.verify();
	}

	@Test
	void buildsTheRetryPromptFromTheOriginalRequestRatherThanCompounding() {
		expectEvaluation(FAILING);
		expectEvaluation(FAILING);
		expectEvaluation(PASSING);
		ScriptedChatModel chatModel = new ScriptedChatModel("-255", "-256", "15");

		chatClient(chatModel, 3).prompt("What is the weather in Paris?").call().content();

		// Each retry augments the original user message once, so the feedback block never
		// stacks up across attempts.
		assertThat(chatModel.userMessageOfCall(1)).containsOnlyOnce("Your previous answer was rejected");
		assertThat(chatModel.userMessageOfCall(2)).containsOnlyOnce("Your previous answer was rejected");
		this.model.verify();
	}

	@Test
	void returnsTheLastAnswerOnceAttemptsAreExhausted() {
		expectEvaluation(FAILING);
		expectEvaluation(FAILING);
		expectEvaluation(FAILING);
		ScriptedChatModel chatModel = new ScriptedChatModel("-255", "-256", "-257");

		String content = chatClient(chatModel, 2).prompt("What is the weather in Paris?").call().content();

		assertThat(content).isEqualTo("-257");
		assertThat(chatModel.callCount()).isEqualTo(3);
		this.model.verify();
	}

	@Test
	void canBeConfiguredToFailInsteadOfShippingARejectedAnswer() {
		expectEvaluation(FAILING);
		expectEvaluation(FAILING);
		ScriptedChatModel chatModel = new ScriptedChatModel("-255", "-256");

		ChatClient chatClient = ChatClient.builder(chatModel)
			.defaultAdvisors(DecisionSelfRefineAdvisor.builder()
				.judge(judge())
				.maxRepeatAttempts(1)
				.failOnExhaustedAttempts(true)
				.build())
			.build();

		assertThatExceptionOfType(DecisionSelfRefineFailedException.class)
			.isThrownBy(() -> chatClient.prompt("What is the weather in Paris?").call().content())
			.satisfies(ex -> {
				assertThat(ex.verdict().passed()).isFalse();
				assertThat(ex.verdict().failures()).singleElement()
					.satisfies(finding -> assertThat(finding.name()).isEqualTo("is_plausible"));
				assertThat(ex.getMessage()).contains("after 1 retries");
			});

		this.model.verify();
	}

	@Test
	void doesNotJudgeAResponseTheSkipPredicateRejects() {
		ScriptedChatModel chatModel = new ScriptedChatModel("It is 15 degrees Celsius in Paris.");

		ChatClient chatClient = ChatClient.builder(chatModel)
			.defaultAdvisors(DecisionSelfRefineAdvisor.builder()
				.judge(judge())
				.skipEvaluationPredicate((request, response) -> true)
				.build())
			.build();

		assertThat(chatClient.prompt("What is the weather in Paris?").call().content())
			.isEqualTo("It is 15 degrees Celsius in Paris.");

		// Nothing was scripted, so any call to the decision model would fail the test.
		assertThat(this.model.prompts()).isEmpty();
		assertThat(chatModel.callCount()).isEqualTo(1);
	}

	@Test
	void sendsTheQuestionAndAnswerToTheModelAsNamedFields() {
		this.model.expect()
			.andExpect(field("$.user_question")
				.value(org.hamcrest.Matchers.containsString("What is the weather in Paris?")))
			.andExpect(field("$.assistant_answer").value("It is 15 degrees Celsius in Paris."))
			.andExpect(prompt -> assertThat(prompt.getQuestions())
				.containsExactly(Map.entry("helpfulness", HELPFULNESS), Map.entry("is_plausible", PLAUSIBLE)))
			.respondWith(PASSING);

		chatClient(new ScriptedChatModel("It is 15 degrees Celsius in Paris."), 3)
			.prompt("What is the weather in Paris?")
			.call()
			.content();

		this.model.verify();
	}

	@Test
	void judgesTheAnswerRatherThanAThinkingBlockAheadOfIt() {
		this.model.expect()
			.andExpect(field("$.assistant_answer").value("It is 15 degrees Celsius in Paris."))
			.respondWith(PASSING);
		this.model.expect()
			.andExpect(field("$.assistant_answer").value("It is 16 degrees Celsius in Paris."))
			.respondWith(PASSING);

		// Not displayed, the thinking comes back empty; displayed, it is the reasoning.
		chatClient(new ScriptedChatModel(ScriptedChatModel.afterThinking("", "It is 15 degrees Celsius in Paris.")), 3)
			.prompt("What is the weather in Paris?")
			.call()
			.content();
		chatClient(new ScriptedChatModel(
				ScriptedChatModel.afterThinking("The tool said 16.", "It is 16 degrees Celsius in Paris.")), 3)
			.prompt("What is the weather in Paris?")
			.call()
			.content();

		this.model.verify();
	}

	@Test
	void includesTheSystemMessageInWhatIsJudged() {
		this.model.expect()
			.andExpect(field("$.user_question")
				.value(org.hamcrest.Matchers.containsString("You are a terse weather assistant.")))
			.respondWith(PASSING);

		chatClient(new ScriptedChatModel("15C"), 3).prompt()
			.system("You are a terse weather assistant.")
			.user("What is the weather in Paris?")
			.call()
			.content();

		this.model.verify();
	}

	@Test
	void rejectsAnAdvisorWithoutAJudge() {
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> DecisionSelfRefineAdvisor.builder().build())
			.withMessageContaining("judge must be set");
	}

	@Test
	void handsToolCallsToTheJudgeAsTheirOwnField() {
		// A ToolResponseMessage carries no text, so it has to be unpacked. Without this
		// the
		// tool's output never reaches the judge and a groundedness criterion has nothing
		// to
		// check the answer against. The call and its result are paired by id.
		this.model.expect()
			.andExpect(field("$.tool_calls[0].name").value("currentWeather"))
			.andExpect(field("$.tool_calls[0].arguments").value("{\"city\":\"Paris\"}"))
			.andExpect(field("$.tool_calls[0].result").value("15"))
			.andExpect(field("$.user_question")
				.value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("currentWeather"))))
			.respondWith(PASSING);

		AssistantMessage toolCall = AssistantMessage.builder()
			.toolCalls(List
				.of(new AssistantMessage.ToolCall("call_1", "function", "currentWeather", "{\"city\":\"Paris\"}")))
			.build();
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("call_1", "currentWeather", "15")))
			.build();

		ChatClient.builder(new ScriptedChatModel("It is 15 degrees Celsius in Paris."))
			.defaultAdvisors(DecisionSelfRefineAdvisor.builder().judge(judge()).maxRepeatAttempts(1).build())
			.build()
			.prompt(new Prompt(List.of(new UserMessage("What is the weather in Paris?"), toolCall, toolResponse)))
			.call()
			.content();

		this.model.verify();
	}

	@Test
	void keepsParallelToolCallsApartWhenTheProviderLeavesIdsBlank() {
		// Some providers (Google GenAI) send every tool call with an empty id. Keyed by
		// id,
		// the two calls would collapse into one carrying the wrong result.
		this.model.expect()
			.andExpect(field("$.tool_calls.length()").value(2))
			.andExpect(field("$.tool_calls[0].name").value("currentWeather"))
			.andExpect(field("$.tool_calls[0].result").value("15"))
			.andExpect(field("$.tool_calls[1].name").value("localTime"))
			.andExpect(field("$.tool_calls[1].result").value("14:00"))
			.respondWith(PASSING);

		AssistantMessage toolCalls = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("", "function", "currentWeather", "{}"),
					new AssistantMessage.ToolCall("", "function", "localTime", "{}")))
			.build();
		ToolResponseMessage toolResponses = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("", "localTime", "14:00"),
					new ToolResponseMessage.ToolResponse("", "currentWeather", "15")))
			.build();

		judgeOnce(List.of(new UserMessage("Weather and time in Paris?"), toolCalls, toolResponses));
	}

	@Test
	void pairsAnIdReusedAcrossTurnsWithTheResultOfItsOwnTurn() {
		this.model.expect()
			.andExpect(field("$.tool_calls.length()").value(2))
			.andExpect(field("$.tool_calls[0].arguments").value("{\"city\":\"Paris\"}"))
			.andExpect(field("$.tool_calls[0].result").value("15"))
			.andExpect(field("$.tool_calls[1].arguments").value("{\"city\":\"Oslo\"}"))
			.andExpect(field("$.tool_calls[1].result").value("3"))
			.respondWith(PASSING);

		judgeOnce(List.of(new UserMessage("Weather in Paris, then Oslo?"), AssistantMessage.builder()
			.toolCalls(List
				.of(new AssistantMessage.ToolCall("call_0", "function", "currentWeather", "{\"city\":\"Paris\"}")))
			.build(),
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("call_0", "currentWeather", "15")))
					.build(),
				AssistantMessage.builder()
					.toolCalls(List.of(new AssistantMessage.ToolCall("call_0", "function", "currentWeather",
							"{\"city\":\"Oslo\"}")))
					.build(),
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("call_0", "currentWeather", "3")))
					.build()));
	}

	private void judgeOnce(List<org.springframework.ai.chat.messages.Message> messages) {
		ChatClient.builder(new ScriptedChatModel("It is 15 degrees Celsius in Paris."))
			.defaultAdvisors(DecisionSelfRefineAdvisor.builder().judge(judge()).maxRepeatAttempts(1).build())
			.build()
			.prompt(new Prompt(messages))
			.call()
			.content();

		this.model.verify();
	}

	@Test
	void returnsTheBestAttemptRatherThanTheLastOnceAttemptsAreExhausted() {
		// A later attempt is not necessarily a better one: attempt 2 fails one criterion,
		// attempt 3 regresses to failing two.
		expectEvaluation(FAILING_BOTH);
		expectEvaluation(FAILING);
		expectEvaluation(FAILING_BOTH);
		ScriptedChatModel chatModel = new ScriptedChatModel("first", "second", "third");

		String content = chatClient(chatModel, 2).prompt("What is the weather in Paris?").call().content();

		assertThat(content).isEqualTo("second");
		this.model.verify();
	}

	@Test
	void failsWithTheVerdictOfTheBestAttempt() {
		expectEvaluation(FAILING_BOTH);
		expectEvaluation(FAILING);
		expectEvaluation(FAILING_BOTH);

		ChatClient chatClient = ChatClient.builder(new ScriptedChatModel("first", "second", "third"))
			.defaultAdvisors(DecisionSelfRefineAdvisor.builder()
				.judge(judge())
				.maxRepeatAttempts(2)
				.failOnExhaustedAttempts(true)
				.build())
			.build();

		assertThatExceptionOfType(DecisionSelfRefineFailedException.class)
			.isThrownBy(() -> chatClient.prompt("What is the weather in Paris?").call().content())
			.satisfies(ex -> assertThat(ex.verdict().failures()).singleElement()
				.satisfies(finding -> assertThat(finding.name()).isEqualTo("is_plausible")));
	}

	@Test
	void readsTheToolCallsFromThePromptAtTheDefaultOrder() {
		// At the default order the advisor runs inside the tool loop, so the calls and
		// their results arrive in the prompt it receives.
		this.model.expect()
			.andExpect(field("$.tool_calls[0].name").value("weather"))
			.andExpect(field("$.tool_calls[0].arguments").value(org.hamcrest.Matchers.containsString("Paris")))
			.andExpect(field("$.tool_calls[0].result").value(org.hamcrest.Matchers.containsString("15 degrees")))
			.respondWith(PASSING);
		WeatherTool tool = new WeatherTool("15 degrees Celsius");

		String content = ChatClient
			.builder(new ScriptedChatModel(ScriptedChatModel.toolCall("weather", "{\"city\":\"Paris\"}"),
					"It is 15 degrees Celsius in Paris."))
			.defaultTools(tool)
			.defaultAdvisors(DecisionSelfRefineAdvisor.builder().judge(judge()).build())
			.build()
			.prompt("What is the weather in Paris?")
			.call()
			.content();

		assertThat(content).isEqualTo("It is 15 degrees Celsius in Paris.");
		assertThat(tool.calls()).isEqualTo(1);
		this.model.verify();
	}

	@Test
	void stopsRefiningWhenARetryAsksForAToolInsideTheToolLoop() {
		// Running the tool would hand the turn back to the tool loop, which re-enters
		// the advisor with the attempt count and the feedback gone: maxRepeatAttempts
		// would not hold and the tools would run again. The best answer so far is
		// returned instead.
		expectEvaluation(FAILING);
		String toolCall = ScriptedChatModel.toolCall("weather", "{\"city\":\"Paris\"}");
		ScriptedChatModel chatModel = new ScriptedChatModel(toolCall, "It is -125 degrees Celsius in Paris.", toolCall,
				"never asked for", toolCall, "never asked for");
		WeatherTool tool = new WeatherTool("-125 degrees Celsius");

		String content = ChatClient.builder(chatModel)
			.defaultTools(tool)
			.defaultAdvisors(DecisionSelfRefineAdvisor.builder().judge(judge()).maxRepeatAttempts(1).build())
			.build()
			.prompt("What is the weather in Paris?")
			.call()
			.content();

		assertThat(content).isEqualTo("It is -125 degrees Celsius in Paris.");
		// The tool call, the answer and the one retry; one answer judged, one tool run.
		assertThat(chatModel.callCount()).isEqualTo(3);
		assertThat(tool.calls()).isEqualTo(1);
		this.model.verify();
		assertThat(this.model.prompts()).hasSize(1);
	}

	@Test
	void failsWhenARetryAsksForAToolAndARejectedAnswerMustNotShip() {
		expectEvaluation(FAILING);
		String toolCall = ScriptedChatModel.toolCall("weather", "{\"city\":\"Paris\"}");
		ScriptedChatModel chatModel = new ScriptedChatModel(toolCall, "It is -125 degrees Celsius in Paris.", toolCall);

		assertThatExceptionOfType(DecisionSelfRefineFailedException.class)
			.isThrownBy(() -> ChatClient.builder(chatModel)
				.defaultTools(new WeatherTool("-125 degrees Celsius"))
				.defaultAdvisors(DecisionSelfRefineAdvisor.builder()
					.judge(judge())
					.maxRepeatAttempts(3)
					.failOnExhaustedAttempts(true)
					.build())
				.build()
				.prompt("What is the weather in Paris?")
				.call()
				.content())
			.satisfies(ex -> assertThat(ex.verdict().failures()).singleElement()
				.satisfies(finding -> assertThat(finding.name()).isEqualTo("is_plausible")));
		assertThat(chatModel.callCount()).isEqualTo(3);
	}

	@Test
	void doesNotJudgeOrRetryTheNoticeOfAnExceededToolCallLimit() {
		// The notice is the tool loop's, not the model's answer. Judging it fails it,
		// and every retry runs the tools into the same limit again.
		String toolCall = ScriptedChatModel.toolCall("weather", "{\"city\":\"Paris\"}");
		ScriptedChatModel chatModel = new ScriptedChatModel(toolCall, toolCall, toolCall, toolCall, toolCall, toolCall);
		WeatherTool tool = new WeatherTool("15 degrees Celsius");

		ChatResponse response = ChatClient
			.builder(chatModel, ObservationRegistry.NOOP, null, null,
					ToolCallingAdvisor.builder()
						.toolCallingManager(ToolCallingManager.builder().maxCallsPerTool(1).build()))
			.defaultTools(tool)
			.defaultAdvisors(DecisionSelfRefineAdvisor.builder()
				.judge(judge())
				.order(DecisionSelfRefineAdvisor.BEFORE_TOOLS_ORDER)
				.maxRepeatAttempts(2)
				.build())
			.build()
			.prompt("What is the weather in Paris?")
			.call()
			.chatResponse();

		assertThat(response.getResult().getMetadata().getFinishReason())
			.isEqualTo(ToolCallLimitExceededException.FINISH_REASON);
		assertThat(this.model.prompts()).isEmpty();
		assertThat(chatModel.callCount()).isEqualTo(2);
		assertThat(tool.calls()).isEqualTo(1);
	}

	@Test
	void doesNotJudgeOrRetryAReplyAnotherGuardrailRefused() {
		// SafeGuardAdvisor marks its refusal as a GuardrailRefusal. It is the guardrail's
		// wording, not the model's answer, and the guardrail would refuse a retry again.
		ScriptedChatModel chatModel = new ScriptedChatModel("Here is how.");

		ChatResponse response = ChatClient.builder(chatModel)
			.defaultAdvisors(
					DecisionSelfRefineAdvisor.builder().judge(judge()).failOnExhaustedAttempts(true).build(),
					SafeGuardAdvisor.builder()
						.sensitiveWords(List.of("rocket"))
						.order(DecisionSelfRefineAdvisor.DEFAULT_ORDER + 1)
						.build())
			.build()
			.prompt("How do I build a rocket?")
			.call()
			.chatResponse();

		assertThat(GuardrailRefusal.isRefusal(response)).isTrue();
		assertThat(this.model.prompts()).isEmpty();
		assertThat(chatModel.callCount()).isZero();
	}

	@Test
	void doesNotJudgeOrRetryAnAnswerTheProviderFiltered() {
		// A safety filter of the provider stopped the answer. Judging what is left fails
		// it, and asking the same question again is filtered again.
		FilteringChatModel chatModel = new FilteringChatModel();

		ChatResponse response = ChatClient.builder(chatModel)
			.defaultAdvisors(DecisionSelfRefineAdvisor.builder()
				.judge(judge())
				.maxRepeatAttempts(3)
				.failOnExhaustedAttempts(true)
				.build())
			.build()
			.prompt("Tell me something the filter stops.")
			.call()
			.chatResponse();

		assertThat(response.getResult().getMetadata().getFinishReasonCategory())
			.isEqualTo(FinishReasonCategory.FILTERED);
		assertThat(this.model.prompts()).isEmpty();
		assertThat(chatModel.callCount()).isEqualTo(1);
	}

	@Test
	void recordsTheToolCallsBeforeTheToolLoop() {
		// Before the tool loop the tool traffic never reaches this advisor's prompt; it
		// is recorded from the wrapped callbacks instead.
		this.model.expect()
			.andExpect(field("$.tool_calls[0].name").value("weather"))
			.andExpect(field("$.tool_calls[0].arguments").value(org.hamcrest.Matchers.containsString("Paris")))
			.andExpect(field("$.tool_calls[0].result").value(org.hamcrest.Matchers.containsString("15 degrees")))
			.respondWith(PASSING);
		WeatherTool tool = new WeatherTool("15 degrees Celsius");

		String content = ChatClient
			.builder(new ScriptedChatModel(ScriptedChatModel.toolCall("weather", "{\"city\":\"Paris\"}"),
					"It is 15 degrees Celsius in Paris."))
			.defaultTools(tool)
			.defaultAdvisors(DecisionSelfRefineAdvisor.builder()
				.judge(judge())
				.order(DecisionSelfRefineAdvisor.BEFORE_TOOLS_ORDER)
				.build())
			.build()
			.prompt("What is the weather in Paris?")
			.call()
			.content();

		assertThat(content).isEqualTo("It is 15 degrees Celsius in Paris.");
		assertThat(tool.calls()).isEqualTo(1);
		this.model.verify();
	}

	@Test
	void reRunsTheToolsOnARetry() {
		// The point of BEFORE_TOOLS_ORDER: a tool that returned something wrong is asked
		// again, and each attempt is judged against its own tool calls.
		this.model.expect()
			.andExpect(field("$.tool_calls.length()").value(1))
			.andExpect(field("$.tool_calls[0].result").value(org.hamcrest.Matchers.containsString("-125")))
			.respondWith(FAILING);
		this.model.expect()
			.andExpect(field("$.tool_calls.length()").value(1))
			.andExpect(field("$.tool_calls[0].result").value(org.hamcrest.Matchers.containsString("15")))
			.respondWith(PASSING);
		WeatherTool tool = new WeatherTool("-125 degrees Celsius", "15 degrees Celsius");

		String content = ChatClient
			.builder(new ScriptedChatModel(ScriptedChatModel.toolCall("weather", "{\"city\":\"Paris\"}"),
					"It is -125 degrees Celsius in Paris.",
					ScriptedChatModel.toolCall("weather", "{\"city\":\"Paris\"}"),
					"It is 15 degrees Celsius in Paris."))
			.defaultTools(tool)
			.defaultAdvisors(DecisionSelfRefineAdvisor.builder()
				.judge(judge())
				.order(DecisionSelfRefineAdvisor.BEFORE_TOOLS_ORDER)
				.maxRepeatAttempts(3)
				.build())
			.build()
			.prompt("What is the weather in Paris?")
			.call()
			.content();

		assertThat(content).isEqualTo("It is 15 degrees Celsius in Paris.");
		assertThat(tool.calls()).isEqualTo(2);
		this.model.verify();
	}

	@Test
	void leavesRejectedAttemptsOutOfChatMemory() {
		// Chat memory sits outside this advisor at its default order, so only the answer
		// finally returned is remembered.
		expectEvaluation(FAILING);
		expectEvaluation(PASSING);
		ChatMemory memory = MessageWindowChatMemory.builder().build();

		ChatClient
			.builder(
					new ScriptedChatModel("It is -125 degrees Celsius in Paris.", "It is 15 degrees Celsius in Paris."))
			.defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build(),
					DecisionSelfRefineAdvisor.builder().judge(judge()).build())
			.build()
			.prompt("What is the weather in Paris?")
			.advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, "c1"))
			.call()
			.content();

		assertThat(memory.get("c1")).extracting(org.springframework.ai.chat.messages.Message::getText)
			.containsExactly("What is the weather in Paris?", "It is 15 degrees Celsius in Paris.");
		this.model.verify();
	}

	@Test
	void leavesRejectedAttemptsOutOfChatMemoryBeforeTheToolLoop() {
		// BEFORE_TOOLS_ORDER is still inside a chat memory advisor, which runs outside the
		// tool loop: each attempt runs the tools again, and memory sees the final answer.
		expectEvaluation(FAILING);
		expectEvaluation(PASSING);
		ChatMemory memory = MessageWindowChatMemory.builder().build();
		WeatherTool tool = new WeatherTool("-125 degrees Celsius", "15 degrees Celsius");

		ChatClient.builder(twoAttemptsWithTools())
			.defaultTools(tool)
			.defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build(), beforeTools())
			.build()
			.prompt("What is the weather in Paris?")
			.advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, "c1"))
			.call()
			.content();

		assertThat(tool.calls()).isEqualTo(2);
		assertThat(memory.get("c1")).extracting(Message::getText)
			.containsExactly("What is the weather in Paris?", "It is 15 degrees Celsius in Paris.");
		this.model.verify();
	}

	@Test
	void leavesRejectedAttemptsOutOfTheSessionAtTheDefaultOrder() {
		// The session memory advisor runs inside the tool loop, and this advisor inside it
		// at its default order: the session sees one request and the answer finally
		// returned.
		expectEvaluation(FAILING);
		expectEvaluation(PASSING);
		SessionService sessionService = sessionService();
		WeatherTool tool = new WeatherTool("-125 degrees Celsius");

		ChatClient
			.builder(new ScriptedChatModel(ScriptedChatModel.toolCall("weather", "{\"city\":\"Paris\"}"),
					"It is -125 degrees Celsius in Paris.", "It is 15 degrees Celsius in Paris."))
			.defaultTools(tool)
			.defaultAdvisors(withSession(SessionAdvisors.builder(sessionService),
					DecisionSelfRefineAdvisor.builder().judge(judge()).build()))
			.build()
			.prompt("What is the weather in Paris?")
			.advisors(SessionAdvisors.session("s1"))
			.call()
			.content();

		List<Message> stored = sessionService.getMessages("s1");
		assertThat(tool.calls()).isEqualTo(1);
		assertThat(stored).extracting(Message::getMessageType)
			.containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL, MessageType.ASSISTANT);
		assertThat(stored.get(3).getText()).isEqualTo("It is 15 degrees Celsius in Paris.");
		this.model.verify();
	}

	@Test
	void theSessionKeepsEveryAttemptBeforeTheToolLoop() {
		// At BEFORE_TOOLS_ORDER the session memory advisor is inside the retry loop: it
		// stores the rejected answer with its tool traffic, and the feedback prompt of the
		// retry as a user message.
		expectEvaluation(FAILING);
		expectEvaluation(PASSING);
		SessionService sessionService = sessionService();
		ScriptedChatModel chatModel = twoAttemptsWithTools();
		WeatherTool tool = new WeatherTool("-125 degrees Celsius", "15 degrees Celsius");

		ChatClient.builder(chatModel)
			.defaultTools(tool)
			.defaultAdvisors(withSession(SessionAdvisors.builder(sessionService), beforeTools()))
			.build()
			.prompt("What is the weather in Paris?")
			.advisors(SessionAdvisors.session("s1"))
			.call()
			.content();

		List<Message> stored = sessionService.getMessages("s1");
		assertThat(tool.calls()).isEqualTo(2);
		assertThat(stored).extracting(Message::getMessageType)
			.containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL, MessageType.ASSISTANT,
					MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL, MessageType.ASSISTANT);
		assertThat(stored.get(3).getText()).isEqualTo("It is -125 degrees Celsius in Paris.");
		assertThat(stored.get(4).getText()).contains("rejected by an automated evaluation", "<rejected_answer>");
		assertThat(stored.get(7).getText()).isEqualTo("It is 15 degrees Celsius in Paris.");
		// The round after the retry's tool call still carries the feedback.
		assertThat(chatModel.userMessageOfCall(3)).contains("rejected by an automated evaluation");
		this.model.verify();
	}

	@Test
	void aRetryThatCallsAToolLosesFeedbackThatIsFilteredOutOfTheSession() {
		// The tool loop rebuilds the prompt of each round from the session. A message
		// filter that keeps the feedback prompt out of the session therefore drops it from
		// the round after the tool call, and the model writes its second answer without
		// it. This is why the feedback prompt must stay in the session.
		expectEvaluation(FAILING);
		expectEvaluation(PASSING);
		SessionService sessionService = sessionService();
		ScriptedChatModel chatModel = twoAttemptsWithTools();
		MessageFilter withoutFeedback = MessageFilter.skipEmptyMessages()
			.and(message -> !(message instanceof UserMessage
					&& message.getText().contains("rejected by an automated evaluation")));

		ChatClient.builder(chatModel)
			.defaultTools(new WeatherTool("-125 degrees Celsius", "15 degrees Celsius"))
			.defaultAdvisors(withSession(SessionAdvisors.builder(sessionService)
				.memory(memory -> memory.messageFilter(withoutFeedback)), beforeTools()))
			.build()
			.prompt("What is the weather in Paris?")
			.advisors(SessionAdvisors.session("s1"))
			.call()
			.content();

		assertThat(sessionService.getMessages("s1")).extracting(Message::getMessageType)
			.containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL, MessageType.ASSISTANT,
					MessageType.ASSISTANT, MessageType.TOOL, MessageType.ASSISTANT);
		assertThat(chatModel.userMessageOfCall(2)).contains("rejected by an automated evaluation");
		assertThat(chatModel.userMessageOfCall(3)).isEqualTo("What is the weather in Paris?");
		this.model.verify();
	}

	/**
	 * A tool whose result goes straight back to the caller.
	 */
	/**
	 * A chat model whose every answer a safety filter of the provider stopped.
	 */
	static class FilteringChatModel extends ScriptedChatModel {

		@Override
		public ChatResponse call(Prompt prompt) {
			super.call(prompt);
			ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
				.finishReason("content_filter")
				.finishReasonCategory(FinishReasonCategory.FILTERED)
				.build();
			return new ChatResponse(List.of(new Generation(new AssistantMessage(""), metadata)));
		}

	}

	static class DirectTool {

		private int calls;

		@Tool(description = "Look up a record", returnDirect = true)
		String lookup(String id) {
			this.calls++;
			return "record " + id;
		}

	}

	/**
	 * A weather tool that returns its scripted results in turn and counts its calls.
	 */
	static class WeatherTool {

		private final java.util.Deque<String> results;

		private int calls;

		WeatherTool(String... results) {
			this.results = new java.util.ArrayDeque<>(List.of(results));
		}

		@Tool(description = "Get the current weather for a city")
		String weather(String city) {
			this.calls++;
			return this.results.size() > 1 ? this.results.poll() : this.results.peek();
		}

		int calls() {
			return this.calls;
		}

	}

	@Test
	void returnsTheAnswerUnjudgedWhenJudgingFails() {
		// An outage says nothing about the answer, so by default it must not fail the
		// call.
		this.model.failWith(new IllegalStateException("unavailable", new java.net.SocketTimeoutException("timed out")));
		ScriptedChatModel chatModel = new ScriptedChatModel("It is 15 degrees Celsius in Paris.");

		String content = chatClient(chatModel, 3).prompt("What is the weather in Paris?").call().content();

		assertThat(content).isEqualTo("It is 15 degrees Celsius in Paris.");
		assertThat(chatModel.callCount()).isEqualTo(1);
		this.model.verify();
	}

	@Test
	void rethrowsAClientErrorEvenWhenFailingOpen() {
		// A bad key or an invalid question is a misconfiguration, not an outage: failing
		// open on it would silently turn judging off.
		IllegalArgumentException malformed = new IllegalArgumentException("malformed question");
		this.model.failWith(malformed);

		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> chatClient(new ScriptedChatModel("It is 15 degrees Celsius in Paris."), 3)
				.prompt("What is the weather in Paris?")
				.call()
				.content())
			.isSameAs(malformed);
		this.model.verify();
	}

	@Test
	void failsOpenOnAFailureTheCallerNamesAsTransient() {
		// The provider's own rate limit or server error is unknown to the default test.
		class RateLimited extends RuntimeException {

		}
		this.model.failWith(new RateLimited());
		ScriptedChatModel chatModel = new ScriptedChatModel("It is 15 degrees Celsius in Paris.");

		String content = ChatClient.builder(chatModel)
			.defaultAdvisors(DecisionSelfRefineAdvisor.builder()
				.judge(judge())
				.transientErrorPredicate(failure -> failure instanceof RateLimited)
				.build())
			.build()
			.prompt("What is the weather in Paris?")
			.call()
			.content();

		assertThat(content).isEqualTo("It is 15 degrees Celsius in Paris.");
		this.model.verify();
	}

	@Test
	void tellsAnOutageFromAMisconfigurationWithoutKnowingTheProvider() {
		assertThat(DecisionSelfRefineAdvisor
			.isTransient(new java.io.UncheckedIOException(new java.io.IOException("reset")))).isTrue();
		assertThat(DecisionSelfRefineAdvisor
			.isTransient(new IllegalStateException(new java.util.concurrent.TimeoutException("too slow")))).isTrue();
		assertThat(DecisionSelfRefineAdvisor.isTransient(new IllegalArgumentException("bad key"))).isFalse();
	}

	@Test
	void doesNotJudgeAToolResultReturnedDirectly() {
		// A returnDirect tool's output is not the model's answer: judging it, and
		// retrying
		// on failure, would re-run a tool that may have side effects.
		DirectTool tool = new DirectTool();

		String content = ChatClient
			.builder(new ScriptedChatModel(ScriptedChatModel.toolCall("lookup", "{\"id\":\"42\"}")))
			.defaultTools(tool)
			.defaultAdvisors(DecisionSelfRefineAdvisor.builder()
				.judge(judge())
				.order(DecisionSelfRefineAdvisor.BEFORE_TOOLS_ORDER)
				.build())
			.build()
			.prompt("Look up 42")
			.call()
			.content();

		assertThat(content).contains("record 42");
		assertThat(tool.calls).isEqualTo(1);
		this.model.verify();
	}

	@Test
	void prefersAJudgedAttemptOverOneFailFastLeftUnjudged() {
		// Attempts 1 and 3 fail the code check and, under failFast, are never sent to the
		// model.
		// Counting failures alone would prefer them (1 failure) to attempt 2 (1 failure,
		// but one question passed); ranking by passed minus failed prefers attempt 2.
		this.model.respondWith(FAILING);
		DecisionJudge judge = DecisionJudge.builder(this.model)
			.check("mentions_paris", input -> input.answer() != null && input.answer().contains("Paris"),
					"does not mention Paris")
			.score("helpfulness", HELPFULNESS, 2.0d)
			.yesNo("is_plausible", PLAUSIBLE, 0.7d)
			.failFast(true)
			.build();

		String content = ChatClient
			.builder(new ScriptedChatModel("It is cold.", "It is -125 degrees in Paris.", "Cold."))
			.defaultAdvisors(DecisionSelfRefineAdvisor.builder().judge(judge).maxRepeatAttempts(2).build())
			.build()
			.prompt("What is the weather in Paris?")
			.call()
			.content();

		assertThat(content).isEqualTo("It is -125 degrees in Paris.");
		this.model.verify();
	}

	@Test
	void canBeConfiguredToFailWhenJudgingFails() {
		// Transient, so only the policy stands between this failure and a pass.
		this.model.failWith(new java.io.UncheckedIOException(new java.io.IOException("connection reset")));

		ChatClient chatClient = ChatClient.builder(new ScriptedChatModel("It is 15 degrees Celsius in Paris."))
			.defaultAdvisors(DecisionSelfRefineAdvisor.builder()
				.judge(judge())
				.judgeErrorPolicy(DecisionSelfRefineAdvisor.JudgeErrorPolicy.FAIL_CLOSED)
				.build())
			.build();

		assertThatExceptionOfType(java.io.UncheckedIOException.class)
			.isThrownBy(() -> chatClient.prompt("What is the weather in Paris?").call().content());
		this.model.verify();
	}

	@Test
	void givesTheSkipPredicateTheRequestThatProducedTheResponse() {
		// From the second attempt on, the request actually sent is the feedback-augmented
		// one. A predicate handed the original would be deciding on a prompt that was
		// never used.
		expectEvaluation(FAILING);
		expectEvaluation(PASSING);
		List<String> promptsSeenByPredicate = new ArrayList<>();

		ChatClient
			.builder(
					new ScriptedChatModel("It is -255 degrees Celsius in Paris.", "It is 15 degrees Celsius in Paris."))
			.defaultAdvisors(DecisionSelfRefineAdvisor.builder()
				.judge(judge())
				.maxRepeatAttempts(3)
				.skipEvaluationPredicate((request, response) -> {
					promptsSeenByPredicate.add(request.prompt().getUserMessage().getText());
					return false;
				})
				.build())
			.build()
			.prompt("What is the weather in Paris?")
			.call()
			.content();

		assertThat(promptsSeenByPredicate).hasSize(2);
		assertThat(promptsSeenByPredicate.get(0)).isEqualTo("What is the weather in Paris?");
		assertThat(promptsSeenByPredicate.get(1)).contains("Your previous answer was rejected");

		this.model.verify();
	}

	@Test
	void rejectsARetryBudgetItCannotHonour() {
		// Integer.MAX_VALUE used to overflow the loop bound to Integer.MIN_VALUE, so the
		// body never ran and the call failed without ever reaching the model.
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> DecisionSelfRefineAdvisor.builder().judge(judge()).maxRepeatAttempts(Integer.MAX_VALUE))
			.withMessageContaining(
					"maxRepeatAttempts must be between 1 and " + DecisionSelfRefineAdvisor.MAX_REPEAT_ATTEMPTS_LIMIT);

		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> DecisionSelfRefineAdvisor.builder().judge(judge()).maxRepeatAttempts(0));
	}

	private ChatClient chatClient(ScriptedChatModel chatModel, int maxRepeatAttempts) {
		return ChatClient.builder(chatModel)
			.defaultAdvisors(
					DecisionSelfRefineAdvisor.builder().judge(judge()).maxRepeatAttempts(maxRepeatAttempts).build())
			.build();
	}

	private DecisionJudge judge() {
		return DecisionJudge.builder(this.model)
			.score("helpfulness", HELPFULNESS, 2.0d)
			.yesNo("is_plausible", PLAUSIBLE, 0.7d)
			.build();
	}

	private void expectEvaluation(Map<String, DecisionAnswer> answers) {
		this.model.respondWith(answers);
	}

	/** Two attempts that each call the weather tool before they answer. */
	private static ScriptedChatModel twoAttemptsWithTools() {
		String toolCall = ScriptedChatModel.toolCall("weather", "{\"city\":\"Paris\"}");
		return new ScriptedChatModel(toolCall, "It is -125 degrees Celsius in Paris.", toolCall,
				"It is 15 degrees Celsius in Paris.");
	}

	private DecisionSelfRefineAdvisor beforeTools() {
		return DecisionSelfRefineAdvisor.builder()
			.judge(judge())
			.order(DecisionSelfRefineAdvisor.BEFORE_TOOLS_ORDER)
			.maxRepeatAttempts(3)
			.build();
	}

	private static SessionService sessionService() {
		return DefaultSessionService.builder().sessionRepository(InMemorySessionRepository.builder().build()).build();
	}

	private static List<Advisor> withSession(SessionAdvisors.Builder sessionAdvisors, Advisor advisor) {
		List<Advisor> all = new ArrayList<>(sessionAdvisors.build());
		all.add(advisor);
		return all;
	}

}
