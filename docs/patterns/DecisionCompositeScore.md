# DecisionCompositeScore

Several score dimensions combined into one number with weights you control.

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-decision-patterns</artifactId>
</dependency>
```

!!! warning "For ordering, never for gating"
    The argument for atomic questions over a single rubric prompt is that independent checks
    must be thresholded **independently**. An answer that is fluent and on topic but quotes
    an impossible temperature has to fail on plausibility alone, and any average hides
    exactly that.

    So do not build a pass/fail decision on a composite.
    [`DecisionJudge`](../judge/DecisionJudge.md) thresholds each criterion separately and is
    the right tool for that.

## What it is genuinely for

Ranking a field of candidates that have **all already passed**: which five of forty
applicants to read first, which support ticket to work next.

There the dimensions are not pass conditions, they are preferences, and the weights are the
policy. Keeping them in code means a disappointing ranking is fixed by changing a weight
rather than by rewording a question.

## Quick Start

```java
DecisionCompositeScore engineering = DecisionCompositeScore.builder()
    .weight("python_depth",    0.40d)
    .weight("system_design",   0.40d)
    .weight("team_leadership", 0.10d)
    .weight("generalist",      0.10d)
    .build();

candidates.sort(Comparator.comparingDouble(c -> -engineering.of(c.response())));
```

Each weighted name must be a score question the response answers.

## Normalisation

Each dimension is divided by its rubric's own height before weighting, so dimensions with
different numbers of levels are comparable. With weights summing to 1, the composite is
itself in `[0, 1]`.

The height of a rubric is its number of levels minus one. There are two ways to tell the
composite what it is:

| Call | Reads the height from | Use it when |
|---|---|---|
| `of(response)` | the probabilities the answer reports, one per level | the provider reports probabilities |
| `of(response, questions)` | the `ScoreQuestion` that was asked | the provider reports none, or you would rather not depend on it |

```java
double composite = engineering.of(response, questions);

double depth = DecisionCompositeScore.normalise(response.getScore("python_depth"));            // 0.0 – 1.0
double same  = DecisionCompositeScore.normalise(response.getScore("python_depth"), depthRubric);
```

`of(response)` throws an `IllegalStateException` for an answer that reports no
probabilities. Returning `0` instead would score every candidate identically, a
plausible-looking number that silently collapses the ranking.

## Same data, different profiles

The point of keeping weights in code is that one set of answers serves several rankings:

```java
DecisionCompositeScore ic = DecisionCompositeScore.builder()
    .weight("python_depth", 0.40d).weight("system_design", 0.40d)
    .weight("team_leadership", 0.10d).weight("generalist", 0.10d).build();

DecisionCompositeScore manager = DecisionCompositeScore.builder()
    .weight("python_depth", 0.15d).weight("system_design", 0.20d)
    .weight("team_leadership", 0.40d).weight("generalist", 0.25d).build();
```

One call produces the answers; two weightings produce two shortlists.

## Builder Configuration

| Builder method | Default | Description |
|---|---|---|
| `weight(String, double)` | — | A score question's share of the composite. |
| `allowUnnormalisedWeights(boolean)` | `false` | Permit weights that do not sum to 1, which puts the composite's range somewhere other than `[0, 1]`. |

`build()` rejects weights that do not sum to 1 unless you opt out, because weights that do
not sum to one are usually a typo rather than an intention.

## See Also

- [DecisionJudge](../judge/DecisionJudge.md): the right tool when the answer is pass or fail
- [Decision Models](../concepts/decision-models.md#score): why scores are continuous
