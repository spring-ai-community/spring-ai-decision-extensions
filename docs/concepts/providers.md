# Providers

The components in this project take a `DecisionModel` and nothing else, so any Spring AI
implementation works. Spring AI ships three. Each has a Spring Boot starter that contributes
a `DecisionModel` bean.

| Provider | Starter | Default model | Runs |
|----------|---------|---------------|------|
| [Ollama](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/integrations/models/ollama/decision.html) | `spring-ai-starter-model-ollama` | `d1-omni:600m` | on your machine, no API key |
| [OpenAI](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/integrations/models/openai/decision.html) | `spring-ai-starter-model-openai` | `gpt-6-luna` | hosted |
| [System One](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/integrations/models/systemone.html) | `spring-ai-starter-model-systemone` | `jev-latest` | hosted (TypeSafe) or local (Laya, llmman, Ollama) |

The Spring AI reference pages linked above are the full description of each. This page
covers what matters when you use them with this project.

## Ollama

The quickest way to start. Install Ollama 0.40.4 or later, pull the default decision model,
and add the starter:

```bash
ollama pull d1-omni:600m
```

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-ollama</artifactId>
</dependency>
```

```properties
# application.properties; all optional
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.decision.model=d1-omni:600m
spring.ai.ollama.init.pull-model-strategy=when_missing
```

!!! tip "Small models lean on descriptions"
    Small decision models, such as `d1-omni:600m`, depend a lot on the descriptions of the
    options and levels. Write the `whenYes`/`whenNo` criteria and the option descriptions
    out, rather than relying on a name alone.

## OpenAI

`OpenAiDecisionModel` uses the OpenAI Decisions API, an endpoint separate from chat
completions.

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

```properties
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.decision.model=gpt-6-luna
```

OpenAI is the one provider that can **refuse** a single question and still answer the
others. See [how each component treats a refusal](decision-models.md#refusals).

## System One

System One is the `POST /v1/systemone` protocol of the decision models of
[TypeSafe](https://www.typesafe.ai). Laya, llmman and Ollama serve it too, so one
implementation works with all four.

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-systemone</artifactId>
</dependency>
```

```properties
spring.ai.systemone.api-key=${TYPESAFE_API_KEY}
# For a local server instead of TypeSafe:
# spring.ai.systemone.base-url=http://localhost:8002
# spring.ai.systemone.decision.model=english
```

## What each supports

| Feature | Ollama | OpenAI | System One |
|---------|--------|--------|------------|
| Text input | Yes | Yes | Yes |
| Text with images | No | Yes, inline images only | No |
| Fields input | Yes | Yes | Yes |
| Conversation input | Yes | Yes | Yes |
| Probabilities | Yes | Yes | Yes |
| Confidence (choice, score) | Yes | Yes | Yes |
| Refusals | No | Yes | No |
| Response id | No | Yes | TypeSafe only |

Three of these matter for the components here:

- **Fields input is what they send.** The [judge](../judge/DecisionJudge.md), the
  [guardrail](../guardrails/DecisionGuardrailAdvisor.md) and the
  [chat adapter](../chat/DecisionChatModel.md) all build a `FieldsInput`, which every
  provider supports.
- **A confidence of `0` can mean "none reported".** With Ollama and System One, the client
  reports `0` when the server sends no confidence. The
  [confidence gate](../patterns/DecisionConfidenceGate.md) escalates such an answer, which
  is the safe reading.
- **Text only, except OpenAI.** An image in the input makes the call fail on Ollama and
  System One. `DecisionChatModel`'s default input rejects media for that reason.

## More than one decision model

When two provider starters are on the classpath, each auto-configures its own decision
model. Choose one with `spring.ai.model.decision`:

```properties
# ollama, openai, systemone or none
spring.ai.model.decision=ollama
```

Or build the models yourself and give each component the one it should use. A common split
is a small local model for the guardrail, which runs on every turn, and a larger one for the
judge:

```java
DecisionGuardrailAdvisor.builder(localDecisionModel).build();
DecisionJudge.builder(hostedDecisionModel).build();
```

A component can also use a different model of the same provider through its `options(...)`:

```java
DecisionJudge.builder(decisionModel)
    .options(DecisionOptions.builder().model("d1-omni:2b").build())
    .build();
```

## Thresholds do not carry over

The providers use different models, so their probabilities differ. A threshold tuned on one
model is not valid on another. When you change the provider or the model, check your
thresholds again; [DecisionConsistency](../patterns/DecisionConsistency.md) shows which of
them sit on unstable answers.

## Failures and retries

The `DecisionModel` API has no common exception for an outage. Each provider throws its own,
after its own retries: the System One client, for example, retries connection failures,
timeouts, rate limits and server errors before it throws a `SystemOneException`.

The [self-refine advisor](../self-refine/DecisionSelfRefineAdvisor.md#when-judging-fails)
needs to tell an outage from a misconfiguration, and takes a predicate for it. For a System
One or Ollama model, add the provider's own test to the default:

```java
DecisionSelfRefineAdvisor.builder()
    .judge(judge)
    .transientErrorPredicate(((Predicate<Throwable>) DecisionSelfRefineAdvisor::isTransient)
            .or(SystemOneRetryUtils::isTransient))
    .build();
```

## Observability

Spring AI observes decision model calls like the calls of other models: a
`gen_ai.client.operation` observation with the `decision` operation name and the provider as
`gen_ai.system`. Every call a judge or a guardrail makes is recorded that way, with no
configuration here. See
[Decision Model observability](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/observability/index.html)
in the Spring AI reference.

## See Also

- [Decision Models](decision-models.md): the API in brief
- [Ollama decision models](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/integrations/models/ollama/decision.html)
- [OpenAI decision models](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/integrations/models/openai/decision.html)
- [System One decision models](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/integrations/models/systemone.html)
