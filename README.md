# Spring AI Decision Extensions

Higher-level building blocks on top of the Spring AI
[`DecisionModel`](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/api/decision.html) API: a
model-as-a-judge made of atomic questions, self-refine and guardrail advisors, and a chat
model adapter for typed classifications.

They work with any `DecisionModel` (OpenAI, Ollama, System One), because they depend only
on the API and not on a provider.

> [!NOTE]
> Ported from [spring-ai-typesafe](https://github.com/spring-ai-community/spring-ai-typesafe),
> where these components were tied to the TypeSafe client. Not released yet.
> To move an application over, see
> [Coming from spring-ai-typesafe](https://spring-ai-community.github.io/spring-ai-decision-extensions/latest-snapshot/migration/).

## What is in it

Each feature is its own artifact, so an application takes only what it uses.

| Artifact | Class | What it does |
| -------- | ----- | ------------ |
| `spring-ai-decision-judge` | `DecisionJudge` | Judges an answer against atomic criteria, each with its own threshold, in one model call |
| | `DecisionEvaluator` | The judge behind Spring AI's `Evaluator` interface |
| `spring-ai-decision-self-refine` | `DecisionSelfRefineAdvisor` | Judges every chat response and retries with the defect as feedback |
| `spring-ai-decision-guardrail` | `DecisionGuardrail` | A battery of hazards, each its own question and threshold, asked in one model call |
| | `DecisionGuardrailAdvisor` | Screens the user message and the reply against a battery |
| `spring-ai-decision-patterns` | `DecisionConfidenceGate` | Decides whether to act on a choice, confirm it or escalate it |
| | `DecisionConsistency` | Samples the same questions repeatedly and reports how much the answers move |
| | `DecisionCompositeScore` | Weighs several scores into one number, for ranking |
| `spring-ai-decision-chat` | `DecisionChatModel` | Experimental. A decision model as a `ChatModel`, so `ChatClient.entity(...)` returns a typed classification |
| `spring-ai-decision-test` | `StubDecisionModel` | A scripted `DecisionModel`, for testing judges and guardrails offline |
| `spring-ai-decision-common` | | What the advisors share. Comes in transitively; there is no need to declare it |

The judge, the patterns and the chat adapter need only the DecisionModel API. The two
advisors, self-refine and guardrail, also need `ChatClient`.

## Getting it

Import the BOM, then declare the artifacts without versions:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springaicommunity</groupId>
            <artifactId>spring-ai-decision-bom</artifactId>
            <version>0.5.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>spring-ai-decision-self-refine</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>spring-ai-decision-guardrail</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>spring-ai-decision-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

## Example

```java
DecisionJudge judge = DecisionJudge.builder(decisionModel)
    .score("helpfulness", ScoreQuestion.builder()
            .text("How well does `assistant_answer` address `user_question`?")
            .level("Terrible: irrelevant or off-topic")
            .level("Mostly unhelpful: misses the main point")
            .level("Mostly helpful: minor gaps")
            .level("Excellent: fully and correctly addressed")
            .build(), 2.0)
    .yesNo("is_plausible", YesNoQuestion.builder()
            .text("Are the values in `assistant_answer` physically plausible?")
            .whenNo("Contains an impossible or absurd value")
            .build(), 0.7)
    .build();

ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(
            DecisionGuardrailAdvisor.input(decisionModel),
            DecisionSelfRefineAdvisor.builder().judge(judge).maxRepeatAttempts(3).build(),
            DecisionGuardrailAdvisor.output(decisionModel))
    .build();
```

The guardrail is two advisors because the two directions belong in different places.
`input(...)` runs first, ahead of chat memory and retrieval, so it screens what the person
wrote and a blocked message is never remembered. `output(...)` runs last, next to the
model, so every reply is screened before it is judged, retried or stored.

## Documentation

The full documentation is published at
[spring-ai-community.github.io/spring-ai-decision-extensions](https://spring-ai-community.github.io/spring-ai-decision-extensions/):
the concepts, a page per component, testing support and a
[guide for moving from `spring-ai-typesafe`](https://spring-ai-community.github.io/spring-ai-decision-extensions/latest-snapshot/migration/).

Its sources are in [`docs/`](docs/index.md). It is a
[MkDocs Material](https://squidfunk.github.io/mkdocs-material/) site, to preview locally:

```bash
pip install mkdocs-material
mkdocs serve
```

## Requirements

Java 17 or later, and Spring AI `2.1.0` or later, the first version with the
`DecisionModel` API.

## Building

```bash
./mvnw clean verify
```

The whole suite runs offline against a scripted `DecisionModel`; no key is needed.

## License

[Apache License 2.0](LICENSE.txt)
