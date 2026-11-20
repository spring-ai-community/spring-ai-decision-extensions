# DecisionConsistency

Asks the same questions repeatedly and reports how much the answers move.

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-decision-patterns</artifactId>
</dependency>
```

## Why

A single number tells you where an answer sits; it does not tell you whether it would sit
there again. That matters when a value lands near a threshold, because there the difference
between acting and not acting can be noise rather than judgement.

Sampling a question a few times and looking at the spread is how you find out which of your
thresholds rest on solid ground.

## Quick Start

```java
Map<String, Object> fields = Map.of("ticket", "My payouts have been failing for 3 days.");
Map<String, DecisionQuestion> questions = Map.of(
        "urgent", YesNoQuestion.of("Does this convey urgency?"));

DecisionConsistency.Report report = DecisionConsistency.sample(decisionModel, fields, questions, 15);

report.statistics().forEach((name, stats) ->
        System.out.printf("%-20s mean %.3f  sd %.3f  range %.3f%n",
                name, stats.mean(), stats.standardDeviation(), stats.range()));
```

The overload without a sample count draws `DEFAULT_SAMPLES` (15). A run needs at least two
samples to say anything about spread.

## The question this answers

```java
// The questions whose samples fall on both sides of the threshold you rely on
List<String> shaky = report.unstableAt(0.70d);
```

A criterion that straddles its threshold makes a decision that is not reproducible: the same
input would be accepted on one run and rejected on the next. Either move the threshold,
sharpen the question, or route that case to a human.

```java
public record Statistics(String name, List<Double> values, double mean,
                         double standardDeviation, double min, double max) {
    double range();
    boolean straddles(double threshold);
}
```

## How the draws are kept independent

Each sample carries a fresh throwaway `uid` among its fields
(`DecisionConsistency.UID_FIELD`), which forces independent draws rather than a repeat of
one cached computation. The fields are otherwise untouched, and the questions are identical
across samples.

Because that field name is reserved, fields that already contain a `uid` are rejected with
an `IllegalArgumentException`. Pass your own identifier under another name.

The questions staying identical matters: this measures the **model's own stability**, not
the effect of rewording a question. Rephrasing to see whether the answer changes is a
different and also useful experiment, but it is not this one.

## What is summarised

Only yes/no answers and scores, which have a single number to track: the probability of yes,
and the score's value. A choice's stability is a question about its whole distribution
rather than one value, so it is left out of the statistics. Read `report.samples()` and
inspect each `getChoice(name).getProbabilities()` yourself if you need it.

A question the model [refused](../concepts/decision-models.md#refusals) in one sample has no
value there, so that sample is left out of the question's statistics.

## Failures

Samples that fail are collected rather than thrown, so a run still produces statistics over
whatever came back:

```java
if (!report.failures().isEmpty()) {
    log.warn("{} of {} samples failed", report.failures().size(),
             report.failures().size() + report.samples().size());
}
```

Each `Failure` carries the sample's index and the exception the call failed with. A sample
that your own executor rejects, for example a bounded pool that is full, is a `Failure` too,
with the `RejectedExecutionException` as its cause.

## Cost and concurrency

Fifteen samples are fifteen model calls, four at a time by default. It is a development-time
and CI-time tool: run it when choosing a threshold or when a rubric changes, not on every
request.

`Sampling` controls how the samples are drawn:

```java
DecisionConsistency.sample(decisionModel, fields, questions, 15,
        DecisionConsistency.Sampling.defaults()
            .withConcurrency(8)
            .withOptions(DecisionOptions.builder().model("d1-omni:2b").build()));
```

| `Sampling` | Default | Description |
|---|---|---|
| `withConcurrency(int)` | `4` | How many calls may be in flight at once. |
| `withExecutor(Executor)` | a pool of the run's own | The executor to run on, for example a virtual-thread executor. A supplied executor is never shut down. |
| `withOptions(DecisionOptions)` | the model's defaults | Options sent with every call, such as the model name. |

!!! tip "One call at a time for a local model"
    Some local servers answer one request at a time. Use `withConcurrency(1)` there, so the
    samples queue in the client rather than time out on the server.

## See Also

- [Confidence](../concepts/confidence.md): a different question about one answer
- [Providers](../concepts/providers.md#thresholds-do-not-carry-over): re-measure when the model changes
