# DecisionEvaluator

A [`DecisionJudge`](DecisionJudge.md) behind Spring AI's `Evaluator` interface, so a
decision model drops into code already written against the framework's evaluation SPI.

It ships in `spring-ai-decision-judge`, next to the judge.

## Why

Spring AI's `org.springframework.ai.evaluation.Evaluator` is a one-method interface:

```java
EvaluationResponse evaluate(EvaluationRequest request);
```

Its two shipped implementations, `RelevancyEvaluator` and `FactCheckingEvaluator`, prompt a
chat model and parse a verdict out of the prose. `DecisionEvaluator` implements the same
interface with typed questions instead, so anything holding an `Evaluator` gets a stricter
one without changing a line.

## Quick Start

```java
Evaluator evaluator = new DecisionEvaluator(judge);

EvaluationResponse result = evaluator.evaluate(
        new EvaluationRequest(userQuestion, retrievedDocuments, assistantAnswer));

if (!result.isPass()) {
    log.warn("{} (score {})", result.getFeedback(), result.getScore());
}
```

## The mapping

| `EvaluationRequest` | Judged input field |
|---|---|
| `getUserText()` | `user_question` |
| `getResponseContent()` | `assistant_answer` |
| `getDataList()` | `supporting_context`, one entry per document with text (only when non-empty) |

| `EvaluationResponse` | From |
|---|---|
| `isPass()` | `verdict.passed()` |
| `getScore()` | the fraction of the criteria that applied which passed; `NOT_APPLICABLE` ones are left out |
| `getFeedback()` | `verdict.feedback()` |
| `getMetadata()` | the full `DecisionVerdict` and a per-criterion outcome map |

```java
DecisionVerdict verdict = (DecisionVerdict) result.getMetadata().get(DecisionEvaluator.VERDICT_METADATA_KEY);
Map<String, String> findings =
        (Map<String, String>) result.getMetadata().get(DecisionEvaluator.FINDINGS_METADATA_KEY);
// {helpfulness=PASSED, is_plausible=FAILED}
```

!!! note "The single score is lossy on purpose"
    `EvaluationResponse` has room for one float, while a judge holds one threshold per
    criterion. The score is the fraction of the applicable criteria that passed, and the
    per-criterion detail is preserved in the metadata. If that view is what you are after,
    call [`DecisionJudge.judge`](DecisionJudge.md) directly and read the `DecisionVerdict`.

    An inconclusive criterion counts as neither passed nor failed, so it lowers the score
    without being treated as a failure, the same stance `DecisionVerdict.passed()` takes.

## Supporting documents

`getDataList()` carries the retrieved `Document`s, which is the evidence a groundedness
criterion needs. Their texts reach the input as the `supporting_context` list, one entry per
document, so write such a criterion against that field:

```java
DecisionJudge grounded = DecisionJudge.builder(decisionModel)
    .yesNo("is_grounded", YesNoQuestion.builder()
        .text("Is every claim in `assistant_answer` supported by `supporting_context`?")
        .whenNo("Introduces facts the context does not support")
        .build(), 0.7d)
    .build();
```

To skip that criterion when a request carries no documents, make it conditional:

```java
DecisionCriterion.yesNo("is_grounded", groundedQuestion, 0.7d)
    .appliesWhen(input -> !input.context().isEmpty())
```

This is also how a groundedness check gets real evidence in a RAG setting. The
[self-refine advisor](../self-refine/DecisionSelfRefineAdvisor.md) sees the retrieved
context only inside `user_question`, and only at its default order.

## See Also

- [DecisionJudge](DecisionJudge.md)
- [Spring AI evaluation testing](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/evaluation/evaluators.html)
- [`DecisionDocumentFilter`](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/rag/modules.html#_decisiondocumentreranker), Spring AI's own way to screen the retrieved context before it is used
