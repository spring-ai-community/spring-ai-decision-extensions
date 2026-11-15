# DecisionConfidenceGate

A reusable routing policy: a universal confidence floor, plus per-action thresholds for
actions that cost more when they are wrong.

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-decision-patterns</artifactId>
</dependency>
```

## What it does

The answer says *what*; confidence says *whether to act on it unattended*. This turns that
into three decisions:

| Decision | Meaning |
|----------|---------|
| `EXECUTE` | confident enough for this action: act on it |
| `CONFIRM` | above the floor but short of what this action demands: ask the user first |
| `ESCALATE` | below the floor, or no confidence was reported: nobody should act on this automatically |

## Quick Start

```java
DecisionConfidenceGate gate = DecisionConfidenceGate.builder()
    .floor(0.60d)
    .require("transfer_funds", 0.85d)
    .build();

switch (gate.decide(response.getChoice("intent"))) {
    case EXECUTE  -> perform(intent);
    case CONFIRM  -> askTheUserToConfirm(intent);
    case ESCALATE -> handOverToAHuman();
}
```

`decide(ChoiceAnswer)` uses the selected option as the action name. `decide(String, Double)`
takes them separately, for when the action is not simply the option:

```java
String action = untroubled ? "auto_close" : "route_to_" + department;
gate.decide(action, response.getChoice("department").getConfidence());
```

`DecisionConfidenceGate.withDefaultFloor()` is a gate with the default floor and no
per-action requirement.

## When no confidence is reported

`ChoiceAnswer.getConfidence()` is `null` when the provider doesn't report one. Without it
there is nothing to grant autonomy on, so the gate **escalates**.

With Ollama and System One, the client reports a confidence of `0` when the server sends
none. That is below any floor above zero, so it escalates too.

## Builder Configuration

| Builder method | Default | Description |
|---|---|---|
| `floor(double)` | `0.6` | The confidence below which every action escalates. |
| `require(String, double)` | — | Demand more for one action than the floor asks of the rest. |

`build()` rejects an action demanding *less* than the floor. Such an action could never be
reached, so it is a configuration mistake rather than a preference.

`floor()` and `requiredFor(action)` read the policy back, for logging or for showing a user
why a confirmation was asked.

## Why a class rather than an `if`

Two reasons. The asymmetry between actions is policy, and policy is easier to review, test
and change when it lives in one place than when it is spread across call sites. And the
floor is a genuinely separate idea from the per-action requirement: it is the line below
which the *question* did not decide, regardless of what you were going to do with the
answer.

Take a support ticket about a failing payment integration. It is genuinely both a billing
and a technical matter, so the options do not separate:

```text
department   : technical (confidence 0.44)
=> route_to_technical: too uncertain to act on, sent to a person
```

The confidence lands below the floor, and the gate sends the ticket to a person rather than
guessing, while clearer tickets in the same run clear the floor and are handled
automatically.

## See Also

- [Confidence](../concepts/confidence.md)
- [DecisionConsistency](DecisionConsistency.md): checking whether a threshold rests on noise
