# Coming from spring-ai-typesafe

These components started in
[spring-ai-typesafe](https://github.com/spring-ai-community/spring-ai-typesafe), where they
were built on the TypeSafe client. Since Spring AI 2.1 the framework has its own
`DecisionModel` API, with TypeSafe's System One protocol as one provider among several.
This project is the same components re-implemented on that API.

What that buys: the judge, the advisors and the chat adapter now work with **any**
`DecisionModel`, not only TypeSafe.

## Where everything went

Part of `spring-ai-typesafe` moved into Spring AI itself; the rest is here.

| `spring-ai-typesafe` | Now | Where |
|---|---|---|
| `TypeSafeClient` | `SystemOneClient`, behind `SystemOneDecisionModel` | Spring AI, `spring-ai-systemone` |
| `spring-ai-starter-typesafe` | `spring-ai-starter-model-systemone` | Spring AI |
| `Noul`, `Choice`, `Score` | `YesNoQuestion`, `ChoiceQuestion`, `ScoreQuestion` | Spring AI, `spring-ai-model` |
| `SystemOneResponse` and the answers | `DecisionResponse`, `YesNoAnswer`, `ChoiceAnswer`, `ScoreAnswer` | Spring AI, `spring-ai-model` |
| `JevDocumentFilter`, `JevDocumentReranker` | `DecisionDocumentFilter`, `DecisionDocumentReranker` | Spring AI, RAG |
| `JevToolIndex` | `DecisionToolIndex` | Spring AI, tool search |
| `JevJudge`, `JevCriterion`, `JevJudgeInput`, `JevFinding`, `JevVerdict`, `JevEvaluator` | the same names with the `Decision` prefix | `spring-ai-decision-judge` |
| `JevSelfRefineAdvisor` | `DecisionSelfRefineAdvisor` | `spring-ai-decision-self-refine` |
| `JevGuardrail`, `JevGuardrailAdvisor` | `DecisionGuardrail`, `DecisionGuardrailAdvisor` | `spring-ai-decision-guardrail` |
| `JevConfidenceGate`, `JevConsistency`, `JevCompositeScore` | the same names with the `Decision` prefix | `spring-ai-decision-patterns` |
| `JevChatModel` | `DecisionChatModel` | `spring-ai-decision-chat` |

The packages moved from `org.springaicommunity.typesafe.*` to
`org.springaicommunity.decision.{judge, selfrefine, guardrail, patterns, chat}`.

## The API, side by side

```java
// spring-ai-typesafe
JevJudge judge = JevJudge.builder(typeSafeClient)
    .noul("is_plausible", Noul.builder()
        .instructions("Are the values in `assistant_answer` physically plausible?")
        .whenFalse("At least one value is impossible")
        .build(), 0.7d)
    .build();

// here
DecisionJudge judge = DecisionJudge.builder(decisionModel)
    .yesNo("is_plausible", YesNoQuestion.builder()
        .text("Are the values in `assistant_answer` physically plausible?")
        .whenNo("At least one value is impossible")
        .build(), 0.7d)
    .build();
```

| Was | Is |
|---|---|
| `builder(TypeSafeClient)` | `builder(DecisionModel)`, plus `options(DecisionOptions)` for the model name |
| `.noul(...)`, `JevCriterion.noul(...)` | `.yesNo(...)`, `DecisionCriterion.yesNo(...)` |
| `instructions`, `whenTrue`, `whenFalse` | `text`, `whenYes`, `whenNo` |
| `judge(JsonContent)` | `judge(DecisionInput)` |
| `JevChatModel.stateConverter(Function<Prompt, JsonContent>)` | `DecisionChatModel.inputConverter(Function<Prompt, DecisionInput>)` |
| `JevConsistency.sample(client, state, questions, n, JevBatchOptions)` | `DecisionConsistency.sample(model, fields, questions, n, Sampling)` |
| `JevGuardrail.screen(TypeSafeClient, String)` | `DecisionGuardrail.screen(DecisionModel, String)` |

Constants that carried the old name changed value:

| Constant | Was | Is |
|---|---|---|
| `DecisionEvaluator.VERDICT_METADATA_KEY` | `jevVerdict` | `decisionVerdict` |
| `DecisionEvaluator.FINDINGS_METADATA_KEY` | `jevFindings` | `decisionFindings` |
| `DecisionChatModel.RESPONSE_METADATA_KEY` | `jevResponse` | `decisionResponse` |
| `DecisionGuardrailAdvisor.OUTCOME_CONTEXT_KEY` | `jev.guardrail.outcome` | `decision.guardrail.outcome` |

## What behaves differently

Most of the port is a rename. These are the places where behaviour changed, and why.

### Because the API is provider-neutral

- **Confidence can be missing.** `getConfidence()` is `null` when a provider reports none.
  The [confidence gate](patterns/DecisionConfidenceGate.md) escalates such an answer, and
  the judge reads the probabilities rather than the confidence.
- **A provider can refuse one question.** The judge reports a refused criterion as `ERROR`.
  The guardrail treats a refused hazard as if it had fired. The chat adapter replies `null`
  for it.
- **There is no common outage exception.** The self-refine advisor no longer recognises
  `TypeSafeException`. It takes a
  [`transientErrorPredicate`](self-refine/DecisionSelfRefineAdvisor.md#when-judging-fails),
  whose default covers I/O failures, timeouts and Spring AI's `TransientAiException`.
- **No batch API.** `DecisionConsistency` fans its samples out itself; `Sampling` replaces
  `JevBatchOptions`.
- **No legend on a score.** `DecisionCompositeScore` reads a rubric's height from the
  answer's probabilities, or from the question with `of(response, questions)`.
- **The chat adapter's provider name** is `decision` unless you set `provider(...)`; it was
  always `typesafe`.

### Because the behaviour was wrong or risky

- **A failing score's feedback no longer contradicts itself.** With a fractional minimum,
  the feedback could read "rated 1.30, needs to reach 1.20". It now quotes the level the
  minimum counts from. The pass rule itself is unchanged: at least half of the probability
  on the levels at or above the minimum. See
  [how a verdict is decided](judge/DecisionJudge.md#low-confidence-is-undecided-not-failed).
- **A hazard keeps its own action.** A self-harm signal that is borderline but serious, or
  refused on review, now gets the support message instead of the plain refusal.
- **Input and output screening are separate advisors.**
  [`DecisionGuardrailAdvisor.input(...)`](guardrails/DecisionGuardrailAdvisor.md#where-it-sits)
  runs before chat memory, so a blocked message is never remembered and the battery sees
  what the person wrote. The single-advisor builder still works as before.
- **A retry is told more.** The self-refine advisor's retry message now carries the rejected
  answer and the defects of earlier attempts, not only the latest feedback.
- **A retry that asks for a tool ends refining** at the default order, where it used to
  restart the advisor and escape `maxRepeatAttempts`.
- **The tool-call-limit notice is not judged** or retried.
- **Format instructions are removed only on an exact match** in `DecisionChatModel`, and
  never on the native structured-output path, so user text cannot be cut by quoting them.
- **`DecisionConsistency` rejects a `uid` field** of your own instead of overwriting it.

## Artifacts

`typesafe-spring-ai` was one artifact. Here each feature is its own, behind one BOM:

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
```

To keep using TypeSafe as the model, add Spring AI's System One starter. It contributes the
`DecisionModel` bean these components take:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-systemone</artifactId>
</dependency>
```

```properties
spring.ai.systemone.api-key=${TYPESAFE_API_KEY}
```

## See Also

- [Providers](concepts/providers.md)
- [Decision Models](concepts/decision-models.md)
