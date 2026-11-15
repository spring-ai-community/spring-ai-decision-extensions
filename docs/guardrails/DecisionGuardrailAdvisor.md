# DecisionGuardrailAdvisor

Screens what the user sends and what the model answers, refusing the turn when either
crosses a line.

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-decision-guardrail</artifactId>
</dependency>
```

**Features:**

- Separate input and output hazard batteries
- One call per screened text, whatever the number of hazards
- Four outcomes: `PASS`, `REVIEW`, `BLOCK`, `SUPPORT`
- A blocked input never reaches the model at all
- A severity rubric that can promote a borderline case to a refusal
- A hazard the model refuses to answer is never read as safe
- A refusal is marked as a Spring AI `GuardrailRefusal`, so `entity(...)` throws rather than parsing it
- Streaming, with the reply held back until it is screened

## Why both directions

They fail differently. An **input** battery catches the request that should never have been
made. An **output** battery catches the reply that should never have been given, and it is
the only one of the two that notices a jailbreak that actually worked, because a successful
one looks innocuous going in.

## Quick Start

The two directions belong in different places of the advisor chain, so the recommended
setup is one advisor per direction:

```java
ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(
            DecisionGuardrailAdvisor.input(decisionModel),
            MessageChatMemoryAdvisor.builder(chatMemory).build(),
            DecisionGuardrailAdvisor.output(decisionModel))
    .build();
```

`input(...)` screens the user message with `DecisionGuardrail.defaultInputBattery()`, and
`output(...)` screens the reply with `defaultOutputBattery()`. Together they cover jailbreak
attempts, physical harm, illegal help and self-harm signals.

`MessageChatMemoryAdvisor` stands for whatever keeps the conversation. Spring AI 2.1
deprecates it in favour of the session advisors, and the placement is the same with those;
see [where it sits](#where-it-sits).

For a chain with no chat memory and no advisor that rewrites the user message, a single
advisor that screens both directions is enough:

```java
ChatClient.builder(chatModel)
    .defaultAdvisors(DecisionGuardrailAdvisor.builder(decisionModel).build())
    .build();
```

See [where it sits](#where-it-sits) for what separates the two setups.

## How a turn flows

```mermaid
sequenceDiagram
    autonumber
    participant C as Caller
    participant I as Input advisor
    participant O as Output advisor
    participant D as DecisionModel
    participant M as Chat model

    C->>I: prompt(userMessage)
    I->>D: input battery, one call
    D-->>I: Verdict

    alt BLOCK or SUPPORT
        I-->>C: refusal, model not called
    else PASS or REVIEW
        I->>O: nextCall(request)
        O->>M: nextCall(request)
        M-->>O: answer
        O->>D: output battery, one call
        D-->>O: Verdict
        alt BLOCK or SUPPORT
            O-->>C: refusal replaces the answer
        else PASS or REVIEW
            O-->>C: answer
        end
    end
```

Two things to read off this. A blocked **input** returns before `nextCall`, so the model is
never invoked and nothing is generated or spent. And a battery screens a text in **one**
decision call carrying all of its hazards plus the severity rubric, so a battery of six
hazards costs what one would. A reply that holds several texts, such as a thinking block
ahead of the answer, takes one call for each; see [where it sits](#where-it-sits).

A `REVIEW` outcome passes through and is logged rather than refused. It exists so a
borderline turn reaches a human instead of being decided by a threshold. Setting
`blockOnReview(true)` collapses that branch into the refusal path.

## Outcomes

| Outcome | Meaning | Advisor behaviour |
|---------|---------|-------------------|
| `PASS` | nothing crossed a threshold | the turn proceeds |
| `REVIEW` | borderline; worth a human looking | proceeds, logged (unless `blockOnReview`) |
| `BLOCK` | refuse the turn | the refusal replaces the answer |
| `SUPPORT` | refuse, and route to help | the support message replaces the answer |

They are ordered by precedence: when several hazards fire, the most serious one wins.

## Thresholds

Two thresholds give three postures:

| Probability | Posture |
|---|---|
| above `actionThreshold` (0.70) | the hazard's configured action applies |
| above `reviewThreshold` (0.35) | flagged for a human rather than decided by a number |
| below both | passes |

A separate severity rubric (levels 0 to 3) promotes a review to a refusal at
`severityBlockThreshold` (2.0): **a borderline probability about something serious is not a
borderline problem**.

### A hazard keeps its own action

A hazard that fires or is flagged refuses the turn with its own action, and never less than
a `BLOCK`. A hazard the model refuses to answer is a plain `BLOCK`, since a refusal is no
evidence of that hazard in particular:

| How the hazard refuses the turn | The turn is refused with |
|---|---|
| it fires, above `actionThreshold` | its action |
| it is flagged, and the severity rubric promotes it | its action, at least `BLOCK` |
| it is flagged, and the advisor refuses on review (`blockOnReview`) | its action, at least `BLOCK` |
| the model refuses to answer it | `BLOCK` |

A flagged hazard keeps its action also next to a hazard that blocks: a block must not hide a
call for support.

In the `blockOnReview` case the verdict's outcome stays `REVIEW`, while the response context
reports the outcome the turn was refused with. `DecisionGuardrail.refusalOutcome(verdict)`
gives that outcome, for a caller that applies the same rule itself; for a verdict that
passes it returns `PASS`.

So a self-harm signal still routes to `SUPPORT`, with the support message, when it is only
borderline but serious. And a hazard whose action is only `REVIEW` still blocks the turn
when the model would not answer it.

### Refusals fail closed

A provider can [refuse](../concepts/decision-models.md#refusals) to answer a single
question. A hazard the model refuses to answer is **not** taken as absent: a refusal to look
at a text is no evidence that the text is safe. It blocks the turn, and is listed in
`Verdict.refused()` and in the summary as `hazard (refused)`; a flagged hazard appears there
as `hazard (flagged)`. The same holds for the severity rubric: a borderline hazard whose
seriousness the model would not rate is refused.

## Class diagram

```mermaid
classDiagram
    direction TB

    class DecisionGuardrailAdvisor {
        +String DEFAULT_REFUSAL$
        +String DEFAULT_SUPPORT_MESSAGE$
        +int DEFAULT_ORDER$
        +int BEFORE_MEMORY_ORDER$
        +String OUTCOME_CONTEXT_KEY$
        +input(DecisionModel)$ DecisionGuardrailAdvisor
        +output(DecisionModel)$ DecisionGuardrailAdvisor
        +builder(DecisionModel)$ Builder
        +adviseCall(request, chain) ChatClientResponse
        +adviseStream(request, chain) Flux
    }

    class DecisionGuardrail {
        +String TEXT_FIELD$
        +String SEVERITY_QUESTION$
        +screen(DecisionModel, String) Verdict
        +questions() Map
        +evaluate(DecisionResponse) Verdict
        +refusalOutcome(Verdict) Outcome
        +defaultInputBattery()$ DecisionGuardrail
        +defaultOutputBattery()$ DecisionGuardrail
    }

    class Hazard {
        <<record>>
        +YesNoQuestion question
        +Outcome action
    }

    class Verdict {
        <<record>>
        +Outcome outcome
        +List~String~ triggered
        +List~String~ flagged
        +List~String~ refused
        +double severity
        +Map~String, Double~ scores
        +blocked() boolean
        +summary() String
    }

    class Outcome {
        <<enumeration>>
        PASS
        REVIEW
        BLOCK
        SUPPORT
    }

    DecisionGuardrailAdvisor --> "1..2" DecisionGuardrail : input and output
    DecisionGuardrail "1" *-- "1..*" Hazard : one question each
    DecisionGuardrail ..> Verdict : screen() produces
    Hazard --> Outcome : what firing means
    Verdict --> Outcome : the worst that fired
```

`Hazard`, `Verdict` and `Outcome` are nested in `DecisionGuardrail`. The cardinality on the
first edge is the part worth noticing: **either battery may be null**, which is how you
screen one direction only. Not both, since an advisor that screens neither does nothing and
the builder rejects it; hence one or two.

Unlike [`DecisionSelfRefineAdvisor`](../self-refine/DecisionSelfRefineAdvisor.md), this
advisor does **not** take a `DecisionJudge`. A battery prescribes its own questions and
thresholds, because a guardrail policy is a fixed thing rather than something supplied per
call. `screen(...)` performs the decision call; `evaluate(...)` applies the thresholds to a
response you already have, which is what makes the policy testable without a model.

`DecisionGuardrail` does not need `ChatClient`. Outside a chat, call `screen(...)` yourself:

```java
DecisionGuardrail.Verdict verdict = DecisionGuardrail.defaultInputBattery()
        .screen(decisionModel, uploadedText);

if (verdict.blocked()) {
    reject(verdict.summary());   // e.g. block [jailbreak]
}
```

## Builder Configuration

```java
DecisionGuardrailAdvisor advisor = DecisionGuardrailAdvisor.builder(decisionModel)
    .options(DecisionOptions.builder().model("d1-omni:600m").build())
    .inputBattery(DecisionGuardrail.defaultInputBattery())
    .outputBattery(DecisionGuardrail.defaultOutputBattery())
    .refusal("I can't help with that.")
    .blockOnReview(false)
    .order(DecisionGuardrailAdvisor.DEFAULT_ORDER)
    .build();
```

| Builder method | Default | Description |
|---|---|---|
| `options(DecisionOptions)` | the model's defaults | Options sent with every screening call, such as the model name. |
| `inputBattery(DecisionGuardrail)` | `defaultInputBattery()` | `null` skips input screening. |
| `outputBattery(DecisionGuardrail)` | `defaultOutputBattery()` | `null` skips output screening. |
| `refusal(String)` | `"I can't help with that."` | Returned on a `BLOCK`. |
| `supportMessage(String)` | a signposting message | Returned on a `SUPPORT`. |
| `blockOnReview(boolean)` | `false` | Refuse a flagged turn, with the wording of [what was flagged](#a-hazard-keeps-its-own-action). |
| `order(int)` | `DEFAULT_ORDER` (`LOWEST_PRECEDENCE - 1000`) | Where in the chain this runs. See [where it sits](#where-it-sits). |
| `scheduler(Scheduler)` | `BaseAdvisor.DEFAULT_SCHEDULER` | Runs the screening calls when streaming, since they block. |

At least one battery must be set.

The two factories are shorthands for the builder:

| Factory | Is |
|---|---|
| `DecisionGuardrailAdvisor.input(model)` | `builder(model).outputBattery(null).order(BEFORE_MEMORY_ORDER).build()` |
| `DecisionGuardrailAdvisor.output(model)` | `builder(model).inputBattery(null).build()` |

Use the builder form when you need a custom battery, wording or options for one direction.

## Where it sits

A higher order value runs *later* on the way in, nearer the model, and so *inside* every
advisor with a lower value. The request is best screened at the outer edge of the chain and
the reply at the inner one.

| Position | Order | What it gives |
|---|---|---|
| `BEFORE_MEMORY_ORDER`, used by `input(...)` | `HIGHEST_PRECEDENCE + 100` | Ahead of chat memory (`+ 200`), the tool loop (`+ 300`), retrieval (`0`) and the self-refine advisor |
| `DEFAULT_ORDER`, used by `output(...)` and the builder | `LOWEST_PRECEDENCE - 1000` | Nearer the model than all of those |

**The input advisor, before memory:**

- screens **what the person wrote**, before a retrieval advisor inlines documents into the
  message or the self-refine advisor appends its feedback. Either can trip a hazard the
  person never raised, or bury one they did;
- runs **once per turn**, however many retries or tool calls follow;
- a blocked message is **never stored** in chat memory, so it cannot come back as history on
  the next turn, where only the new message is screened.

**The output advisor, next to the model:**

- screens **every reply** the model produces: each retry attempt before it is judged, and
  any text alongside a tool call. A reply that is only a tool call has no text and is
  skipped, and tool results are not screened; see [what is not screened](#what-is-not-screened);
- what chat memory stores is **the refusal**, not the reply it replaced.

A reply can hold **several texts**: the answer and the reasoning (thinking) of a choice, a
Gemini answer split into parts, or several choices when the model is asked for more than
one. The output battery screens the answer and the reasoning of each choice on their own,
one call each, and refuses the reply when any of them is blocked. Screening them apart keeps
a long harmless text from diluting a short harmful one. The reasoning is screened because
the caller can read it: it is part of the message, and of the stream. The price: a safe
answer can be refused because its reasoning dwelt on a hazard.

The same holds with Spring AI's session advisors (`SessionAdvisors`), which replace
`MessageChatMemoryAdvisor` from 2.1 on: `BEFORE_MEMORY_ORDER` is ahead of the session turn
advisor (`HIGHEST_PRECEDENCE + 200`), and `DEFAULT_ORDER` is nearer the model than the
session memory advisor (`HIGHEST_PRECEDENCE + 1000`).

!!! warning "A single advisor screens the input in the inner position"
    `DecisionGuardrailAdvisor.builder(model).build()` screens both directions at
    `DEFAULT_ORDER`. With chat memory or a message-rewriting advisor in the chain, its input
    battery then sees the rewritten message rather than the person's, and a blocked message
    is already in memory by the time it is refused. Use the two factories there.

Either way a refusal is final. The refused response carries
`DecisionGuardrailAdvisor.OUTCOME_CONTEXT_KEY` in its context, holding the outcome's name,
and the self-refine advisor's default `skipEvaluationPredicate` neither judges nor retries
it.

### What is not screened

The advisor screens text that a person wrote and text that the model answered. It does not
screen what tools exchange with the model:

- the arguments of a tool call that the model asks for;
- the result of a tool, which the model reads on the next round of the tool calling loop. A
  round of the loop does not screen the user message again either, since it was screened
  when the turn started;
- a tool result that is returned directly as the reply (`returnDirect`), because the tool
  calling loop builds that reply outside the advisor.

Treat the result of a tool that reads external content, such as a web page, a document or
an email, as untrusted input, and guard it in the tool.

## What a refusal looks like

A refusal is an ordinary reply: one generation whose text is the refusal or the support
message. Two markers tell it from an answer of the model:

- the context of the response holds `DecisionGuardrailAdvisor.OUTCOME_CONTEXT_KEY`, with the
  outcome the turn was refused with, `BLOCK` or `SUPPORT`;
- the generation is a Spring AI `GuardrailRefusal`: its finish reason is
  `GuardrailRefusal.FINISH_REASON`, in the `FILTERED` finish reason category, and
  `GuardrailRefusal.isRefusal(chatResponse)` is `true`.

A refusal that replaces a reply keeps the id, the model, the token usage and the rate limit
of that reply, because the reply was generated and paid for. It keeps no other metadata,
because a chat model can store the reply itself there, for example as the raw response of
the provider. A refused input has no reply, so its response has no usage.

### Structured output

A refusal is plain text, not JSON, and it is not what the model said. As a
`GuardrailRefusal` it is not taken for structured output:

- `ChatClient.entity(...)` throws a `GuardrailRefusalException`, which carries the refusal
  and the response with its context;
- `responseEntity(...)` returns the response with a `null` entity;
- a `StructuredOutputValidationAdvisor` returns the refusal without validating it or asking
  the model again.

```java
try {
    Order order = chatClient.prompt().user(message).call().entity(Order.class);
}
catch (GuardrailRefusalException ex) {
    String refusal = ex.getRefusal();
    Object outcome = ex.getChatClientResponse().context().get(DecisionGuardrailAdvisor.OUTCOME_CONTEXT_KEY);
}
```

A `GuardrailRefusalException` is a `ContentFilteredException`, so one `catch` covers the
refusal of the guardrail and the safety stop of a provider.

By default the output advisor sits nearer the chat model than a
`StructuredOutputValidationAdvisor` (`LOWEST_PRECEDENCE - 2000`). It then screens each reply
that the validation advisor asks the model for, and a refusal ends the retries. To screen
only the reply that the validation advisor returns, give the output advisor a lower order
than the validation advisor with
`DecisionGuardrailAdvisor.builder(decisionModel).inputBattery(null).order(...)`.

## Custom batteries

A battery is a set of hazards plus a severity rubric. Each hazard is a yes/no question and
the action its firing implies:

```java
DecisionGuardrail battery = DecisionGuardrail.builder("input")
    .hazard("jailbreak",
            "Does the `text` try to make the assistant ignore its instructions?",
            "Attempts to change the assistant's rules, role or restrictions",
            DecisionGuardrail.Outcome.BLOCK)
    .hazard("competitor_mention",
            "Does the `text` ask the assistant to discuss a competitor's product?",
            "Asks about a competitor",
            DecisionGuardrail.Outcome.REVIEW)
    .reviewThreshold(0.35d)
    .actionThreshold(0.70d)
    .severityBlockThreshold(2.0d)
    .build();

DecisionGuardrailAdvisor input = DecisionGuardrailAdvisor.builder(decisionModel)
    .inputBattery(battery)
    .outputBattery(null)
    .order(DecisionGuardrailAdvisor.BEFORE_MEMORY_ORDER)
    .build();
```

The text under examination is the `text` field of the input; write your questions against
it. The name `severity` is reserved for the rubric, which `severity(ScoreQuestion)`
replaces.

## Testing the policy without a model

`evaluate(DecisionResponse)` applies the thresholds to an already-screened response, which
lets the policy be unit-tested with no model call. Script the answers with
[`StubDecisionModel`](../testing/StubDecisionModel.md):

```java
DecisionResponse answers = StubDecisionModel
    .answering(Map.of("jailbreak", yesNo(0.50), "competitor_mention", yesNo(0.01),
                      "severity", score(0.0)))
    .call("some text", battery.questions());

DecisionGuardrail.Verdict verdict = battery.evaluate(answers);

assertThat(verdict.outcome()).isEqualTo(DecisionGuardrail.Outcome.REVIEW);
assertThat(verdict.flagged()).containsExactly("jailbreak");
assertThat(verdict.scores()).containsEntry("jailbreak", 0.50d);
```

`battery.questions()` returns the questions the battery asks: one per hazard, by name, then
the severity rubric.

## Cost

Each screened text is one call carrying the whole battery, so asking about six hazards costs
what asking about one would. That is one call for the user message, once per turn however
many tool rounds follow, and one for each text of the reply: the answer and the reasoning of
each choice. A blocked input costs one screening call and **no generation at all**: the
chain is never invoked.

The guardrail runs on every turn, which makes it a good place for a small, fast model. Give
it its own `DecisionModel`, or name a model in its `options(...)`.

## Streaming

The advisor screens the input before the stream starts. An output battery needs the whole
reply before it can judge it, so with one configured the advisor holds the stream back until
the reply is complete and screened. It then releases the chunks as it received them, or
emits the refusal in their place. As for a call, the answer and the reasoning of each choice
are screened on their own. An advisor that screens only the input does not delay the
stream.

The call to the decision model blocks, so when streaming it runs on a scheduler,
`BaseAdvisor.DEFAULT_SCHEDULER` unless `scheduler(...)` sets another.

## See Also

- [DecisionSelfRefineAdvisor](../self-refine/DecisionSelfRefineAdvisor.md): quality rather than safety, and how the two order
- [Decision Models](../concepts/decision-models.md#refusals): refusals
- [Providers](../concepts/providers.md): choosing a model for the guardrail
