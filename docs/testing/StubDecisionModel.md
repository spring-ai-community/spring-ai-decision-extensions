# StubDecisionModel

A `DecisionModel` that answers from a script instead of calling a provider, and records
every prompt it was given. It lets you test judges, guardrails and anything else built on
the `DecisionModel` API offline, with no provider and no key.

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-decision-test</artifactId>
    <scope>test</scope>
</dependency>
```

The artifact also holds [`PromptField`](#asserting-on-the-prompt), for assertions on the
input a decision model was given, and [`ScriptedChatModel`](#scriptedchatmodel), a chat
model that replies from a script.

## What to test with it

A real model decides *what the answers are*. Everything after that is your code: the
thresholds, the criteria that depend on each other, the code checks, the hazards' actions.
That part is deterministic, and worth testing without a model:

- that a probability just under a threshold fails a criterion, and just over it passes;
- that a refused question blocks a guardrail turn rather than passing it;
- that an advisor retries, and what its retry prompt says;
- that the right fields reach the model.

What a stub cannot tell you is whether your *questions* are good. For that, run them against
a real model in an integration test, and check their stability with
[DecisionConsistency](../patterns/DecisionConsistency.md).

## Quick Start

```java
import static org.springaicommunity.decision.test.StubDecisionModel.score;
import static org.springaicommunity.decision.test.StubDecisionModel.yesNo;

class WeatherJudgeTests {

    private final StubDecisionModel model = new StubDecisionModel();

    @Test
    void failsAnImplausibleAnswerOnPlausibilityAlone() {
        this.model.respondWith(Map.of(
                "helpfulness", score(3.0, 0.9, 0.0, 0.0, 0.0, 1.0),
                "is_plausible", yesNo(0.03)));

        DecisionVerdict verdict = judge(this.model).judge(
                "What is the weather in Paris?", "It is -255 degrees Celsius in Paris.");

        assertThat(verdict.failures()).extracting(DecisionFinding::name).containsExactly("is_plausible");
        this.model.verify();
    }
}
```

## Scripting the answers

A stub answers in one of two ways.

**Call by call.** `respondWith(...)` and `failWith(...)` queue the outcome of one call each,
consumed in order. A call with nothing queued fails the test, so an unexpected call cannot
pass unnoticed, and `verify()` fails when a queued outcome was never asked for.

```java
StubDecisionModel model = new StubDecisionModel();
model.respondWith(Map.of("is_plausible", yesNo(0.03)))        // first call
     .respondWith(Map.of("is_plausible", yesNo(0.97)))        // second call
     .failWith(new IllegalStateException("provider down"));   // third call
```

**The same every time.** `answering(...)` gives the same answers to every call, or computes
them from the prompt. `failing(...)` fails every call.

```java
StubDecisionModel steady = StubDecisionModel.answering(Map.of("urgent", yesNo(0.9)));

StubDecisionModel byText = StubDecisionModel.answering(prompt -> {
    Object text = ((FieldsInput) prompt.getInstructions()).getFields().get("text");
    boolean flagged = String.valueOf(text).contains("bomb");
    return Map.of("physical_harm", yesNo(flagged ? 0.95 : 0.01));
});

StubDecisionModel down = StubDecisionModel.failing(new IllegalStateException("provider down"));
```

Only the questions a prompt asks are answered, so one script can serve prompts that ask for
a subset of it. A question the script has no answer for fails the call, as a provider that
left a question out would.

## Building answers

Static factories build each kind of answer:

| Factory | Builds |
|---|---|
| `yesNo(probability)` | a yes/no answer |
| `choice(value, confidence)` | a choice with a confidence and no probabilities |
| `choice(value, confidence, probabilities(...))` | a choice with the probability of each option |
| `score(value)` | a score with neither a confidence nor probabilities |
| `score(value, confidence, probabilities...)` | a score with the probability of each level, lowest first |
| `refusal(reason)` | a refusal, for any question |

```java
choice("billing", 0.82, probabilities("billing", 0.9, "technical", 0.1));
score(2.34, 0.51, 0.0, 0.07, 0.52, 0.41);   // a four-level rubric
refusal("policy");
```

The confidence can be `null`, which is how to test code against a provider that reports
none.

!!! note "Answers are checked like a provider's"
    The response is built with the `DecisionResponse` builder, which rejects a score above
    its rubric, or probabilities that do not match the number of levels. A scripted answer
    that could not come from a real model fails the test rather than passing through.

## Asserting on the prompt

Every prompt is recorded. Read them back with `prompts()` and `lastPrompt()`:

```java
assertThat(model.prompts()).hasSize(2);
assertThat(model.lastPrompt().getOptions()).isSameAs(options);
```

To check a prompt at the moment of the call, start the script with `expect()`. `PromptField`
asserts on one value of the prompt's fields, addressed by a JSON path:

```java
import static org.springaicommunity.decision.test.PromptField.field;

model.expect()
    .andExpect(field("$.assistant_answer").value("It is 15 degrees Celsius in Paris."))
    .andExpect(field("$.tool_calls[0].name").value("weather"))
    .andExpect(field("$.tool_calls.length()").value(1))
    .andExpect(field("$.supporting_context").doesNotExist())
    .respondWith(Map.of("is_plausible", yesNo(0.97)));
```

`value(...)` also takes a Hamcrest matcher, such as `containsString("Paris")`. A broken
expectation fails the test with an `AssertionError` when the call is made.

`PromptField` reads a fields input, which is what the judge, the guardrail and the chat
adapter send. For a text or conversation input, assert on `prompt.getInstructions()`
directly with `andExpect(prompt -> ...)`.

## ScriptedChatModel

A `ChatModel` that replies with a scripted sequence of answers and records the prompts it
was given. Use it to test an advisor chain end to end:

```java
StubDecisionModel judgeModel = new StubDecisionModel();
judgeModel.respondWith(FAILING).respondWith(PASSING);
ScriptedChatModel chatModel = new ScriptedChatModel(
        "It is -255 degrees Celsius in Paris.", "It is 15 degrees Celsius in Paris.");

String content = ChatClient.builder(chatModel)
    .defaultAdvisors(DecisionSelfRefineAdvisor.builder().judge(judge(judgeModel)).build())
    .build()
    .prompt("What is the weather in Paris?")
    .call()
    .content();

assertThat(content).isEqualTo("It is 15 degrees Celsius in Paris.");
assertThat(chatModel.callCount()).isEqualTo(2);
assertThat(chatModel.userMessageOfCall(1)).contains("Your previous answer was rejected");
```

Two scripted steps go beyond plain text:

| Step | The model replies with |
|---|---|
| `ScriptedChatModel.toolCall(name, arguments)` | a tool call, so `ChatClient` runs its tool loop |
| `ScriptedChatModel.afterThinking(thinking, answer)` | a thinking block as a generation of its own, ahead of the answer |

`receivedPrompts()`, `callCount()` and `userMessageOfCall(index)` read back what the model
was asked.

## See Also

- [DecisionJudge](../judge/DecisionJudge.md#testing-with-decisionjudge): using a judge as a test oracle
- [DecisionGuardrailAdvisor](../guardrails/DecisionGuardrailAdvisor.md#testing-the-policy-without-a-model): testing a battery's policy
