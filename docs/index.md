# Spring AI Decision Extensions

Higher-level building blocks on top of the Spring AI
[`DecisionModel`](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/models/decision.html) API: a
model-as-a-judge made of atomic questions, self-refine and guardrail advisors for
`ChatClient`, and a chat model adapter for typed classifications.

## Overview

Most AI code coerces a text-generation model into emitting structured decisions, then parses
the results back into something the program can depend on. That round trip is where the
failures live: the model answers in a shape the parser did not expect, a rating drifts
between runs, and a single overall score hides the one defect that mattered.

A decision model is not a chat model. It takes an **input** and a map of **typed
questions**, and returns structured answers: no text generation, no JSON to coerce, no
parsing. Every question in a call is answered against the same input, so the pattern is
*atomic questions, composed in code* rather than one broad rubric prompt.

```java
DecisionResponse response = decisionModel.call(DecisionPrompt.builder()
        .text("Help! My payouts have been failing for 3 days.")
        .question("is_urgent",   YesNoQuestion.of("Does this convey urgency?"))
        .question("department",  ChoiceQuestion.builder()
                .text("Which team should handle this?")
                .option("billing",   "Payments, invoicing, refunds")
                .option("technical", "Bugs, outages, integrations")
                .option("sales",     "Pricing, upgrades, new accounts")
                .build())
        .question("frustration", ScoreQuestion.of("How frustrated is the customer?",
                "Calm", "Frustrated", "Very angry"))
        .build());

double urgency     = response.getYesNo("is_urgent").getProbability();   // 0.95
String department  = response.getChoice("department").getValue();       // "billing"
Double confidence  = response.getChoice("department").getConfidence();  // 0.82
double frustration = response.getScore("frustration").getValue();       // 1.1
```

That API ships with Spring AI. **This project adds what you build on top of it:**

- [`DecisionJudge`](judge/DecisionJudge.md): a model-as-a-judge built from atomic criteria
  instead of one rubric prompt, each with its own threshold, all in one model call
- [`DecisionSelfRefineAdvisor`](self-refine/DecisionSelfRefineAdvisor.md): a `ChatClient`
  advisor that judges every response and retries the ones that fall short
- [`DecisionGuardrailAdvisor`](guardrails/DecisionGuardrailAdvisor.md): advisors that
  screen the user message and the reply against a battery of hazards
- [`DecisionChatModel`](chat/DecisionChatModel.md): a decision model behind the `ChatModel`
  interface, so `ChatClient.entity(...)` returns a typed classification
- Patterns for acting on answers: a [confidence gate](patterns/DecisionConfidenceGate.md),
  a [consistency sampler](patterns/DecisionConsistency.md) and a
  [composite score](patterns/DecisionCompositeScore.md)

They depend only on the `DecisionModel` API, not on a provider, so they work with any
implementation: [Ollama, OpenAI or System One](concepts/providers.md).

## Project Structure

Each feature is its own artifact, so an application takes only what it uses.

```
spring-ai-decision-extensions/
├── spring-ai-decision-judge/        # DecisionJudge, criteria, verdicts, DecisionEvaluator
├── spring-ai-decision-self-refine/  # DecisionSelfRefineAdvisor
├── spring-ai-decision-guardrail/    # DecisionGuardrail, DecisionGuardrailAdvisor
├── spring-ai-decision-patterns/     # Confidence gate, consistency sampler, composite score
├── spring-ai-decision-chat/         # DecisionChatModel (experimental)
├── spring-ai-decision-test/         # StubDecisionModel and other test support
├── spring-ai-decision-common/       # What the two advisors share
└── spring-ai-decision-bom/          # Bill of materials for the seven above
```

| Artifact | Holds | Needs |
|----------|-------|-------|
| `spring-ai-decision-judge` | [`DecisionJudge`](judge/DecisionJudge.md), [`DecisionEvaluator`](judge/DecisionEvaluator.md) | `spring-ai-model` |
| `spring-ai-decision-self-refine` | [`DecisionSelfRefineAdvisor`](self-refine/DecisionSelfRefineAdvisor.md) | the judge, `spring-ai-client-chat` |
| `spring-ai-decision-guardrail` | [`DecisionGuardrail`, `DecisionGuardrailAdvisor`](guardrails/DecisionGuardrailAdvisor.md) | `spring-ai-client-chat` |
| `spring-ai-decision-patterns` | [`DecisionConfidenceGate`](patterns/DecisionConfidenceGate.md), [`DecisionConsistency`](patterns/DecisionConsistency.md), [`DecisionCompositeScore`](patterns/DecisionCompositeScore.md) | `spring-ai-model` |
| `spring-ai-decision-chat` | [`DecisionChatModel`](chat/DecisionChatModel.md) (experimental) | `spring-ai-model` |
| `spring-ai-decision-test` | [`StubDecisionModel`](testing/StubDecisionModel.md), `PromptField`, `ScriptedChatModel` | `spring-ai-model`, JsonPath, Hamcrest, AssertJ |

The judge, the patterns and the chat adapter need only the `DecisionModel` API. The two
advisors also need `ChatClient`. The test module brings the assertion libraries its
`PromptField` uses. `spring-ai-decision-common` comes in transitively with the
advisors; there is no need to declare it.

## Quick Start

**1. Import the BOM and add the artifacts you use:**

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
</dependencies>
```

The BOM keeps the artifacts on one version, so they cannot drift apart from each other.

**2. Add a decision model.** Any Spring AI provider works. The quickest to try is Ollama,
which runs on your machine and needs no API key:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-ollama</artifactId>
</dependency>
```

```bash
ollama pull d1-omni:600m
```

The starter contributes a `DecisionModel` bean. See [Providers](concepts/providers.md) for
OpenAI and System One.

**3. Judge, retry and screen a chat:**

```java
@Bean
ChatClient chatClient(ChatModel chatModel, DecisionModel decisionModel) {

    DecisionJudge judge = DecisionJudge.builder(decisionModel)
        .score("helpfulness", ScoreQuestion.builder()
                .text("How well does `assistant_answer` address `user_question`?")
                .level("Terrible: irrelevant or off-topic")
                .level("Mostly unhelpful: misses the main point")
                .level("Mostly helpful: minor gaps remain")
                .level("Excellent: fully and correctly addressed")
                .build(), 2.0)
        .yesNo("is_plausible", YesNoQuestion.builder()
                .text("Are the values in `assistant_answer` physically plausible?")
                .whenNo("Contains an impossible or absurd value")
                .build(), 0.7)
        .build();

    return ChatClient.builder(chatModel)
        .defaultAdvisors(
                DecisionGuardrailAdvisor.input(decisionModel),
                DecisionSelfRefineAdvisor.builder().judge(judge).maxRepeatAttempts(3).build(),
                DecisionGuardrailAdvisor.output(decisionModel))
        .build();
}
```

Every answer the model gives is screened, then judged against both criteria, and retried
with the defect as feedback when it falls short.

## What to read next

| If you want to… | Read |
|-----------------|------|
| Understand yes/no, choice and score questions | [Decision Models](concepts/decision-models.md) |
| Pick and configure a provider | [Providers](concepts/providers.md) |
| Know when to act on an answer automatically | [Confidence](concepts/confidence.md) |
| Evaluate a model's answers | [DecisionJudge](judge/DecisionJudge.md) |
| Retry a bad answer automatically | [DecisionSelfRefineAdvisor](self-refine/DecisionSelfRefineAdvisor.md) |
| Screen unsafe input or output | [DecisionGuardrailAdvisor](guardrails/DecisionGuardrailAdvisor.md) |
| Get a typed classification out of `ChatClient` | [DecisionChatModel](chat/DecisionChatModel.md) |
| Test your judges and guardrails offline | [StubDecisionModel](testing/StubDecisionModel.md) |
| Move from `spring-ai-typesafe` | [Coming from spring-ai-typesafe](migration.md) |

Spring AI itself uses decision models for retrieval and tool selection, with
[`DecisionDocumentReranker`, `DecisionDocumentFilter`](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/rag/modules.html#_decisiondocumentreranker)
and [`DecisionToolIndex`](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/tools/tool-search.html).
Those ship with the framework and are documented there.

## Requirements

- Java 17+
- Spring AI `2.1.0` or later, the first version with the `DecisionModel` API
- A decision model: [Ollama, OpenAI or a System One server](concepts/providers.md)

## Building

```bash
./mvnw clean verify
```

The whole suite runs offline against a scripted `DecisionModel`; no key is needed.

## Links

- [Spring AI Decision Models](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/models/decision.html)
- [Spring AI Documentation](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/)
- [Source on GitHub](https://github.com/spring-ai-community/spring-ai-decision-extensions)

## License

Apache License 2.0
