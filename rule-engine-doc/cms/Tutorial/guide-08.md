# Handle errors

The decision runtime is another system. It can be down, slow, or it can refuse a call. In each of these cases the Business Rule Task throws a **BPMN error**, and your process decides what happens next.

> **Walkthrough:** the button next to the title plays this page in a simulated Designer: the runtime is stopped and slowed down, the Error Boundary Event catches the errors, and you see what happens without it.

---

## The runtime is not reachable

Stop the container and click **Apply rules** on the page of **Rule Engine Demo: invoice discount**:

```bash
docker compose stop
```

The Business Rule Task cannot connect and throws `rule:engine:unreachable`. The same happens when `RuleEngine.Url` points to a wrong host or port.

In the demo the process does not fail. An **Error Boundary Event** on the element catches the error, and the process continues on another path to the end **not priced**. It returns the error, and the page shows it in red. Start the container again with `docker compose start`.

---

## Catch the error

The Error Boundary Event of the demo has the error code `rule:engine`. Error codes are hierarchical: `rule:engine` catches every error of the Business Rule Task.

| Error code | Thrown when | Typical reaction |
| --- | --- | --- |
| `rule:engine:unreachable` | The runtime cannot be reached: container down, wrong URL, network problem. | Retry later, or continue with a default. |
| `rule:engine:timeout` | The runtime did not answer within the timeout. | Retry later, or continue with a default. |
| `rule:engine:dmn` | The runtime refused the call: unknown model or decision, invalid DMN file, failed evaluation, wrong API key. | Fix the model or the data. Retrying does not help. |
| `rule:engine:mapping` | The element does not fit the decision: no decision selected, an input without a process data field, a result that does not fit its field. | Fix the process. Retrying does not help. |

Catch one error code to treat it differently from the others, for example `rule:engine:timeout` on one boundary event and `rule:engine` on a second one.

In the output code of the boundary event, `error` is the error that was caught:

```java
in.errorCode = error.getErrorCode();
in.errorMessage = error.getMessage();
```

| Attribute of the error | Content |
| --- | --- |
| `ruleEngineModel` | The name of the model. |
| `ruleEngineCode` | For `rule:engine:dmn`: the code of the runtime, such as `MODEL_NOT_FOUND`, `DECISION_NOT_FOUND`, `EVALUATION_FAILED`, `INVALID_MODEL` or `UNAUTHORIZED`. |
| `ruleEngineDetails` | For `rule:engine:dmn`: the findings of the DMN engine as text. |

Without an Error Boundary Event that fits, the error is unhandled: the process stops at the Business Rule Task. An Error Start Event with the same code catches it for the whole project instead.

---

## A model the runtime does not have

Normally a project brings its models along: the element registers the `.dmn` file before it evaluates it. With `RuleEngine.AutoDeploy` set to `false` it does not, and the runtime has to get the model another way, through its API or a Rules UI.

If the model is not there, the runtime answers `MODEL_NOT_FOUND` and the element throws `rule:engine:dmn`. The error message names the model:

```text
Model 'invoice-discount' is not loaded.
```

The same error code covers everything the runtime refuses. A model that gets a value it does not accept, for example a text where it expects a number, or a value that is not one of the allowed values of its type, answers `EVALUATION_FAILED` with the reason.

---

## Timeout

A Business Rule Task waits for the runtime as long as the variable `RuleEngine.TimeoutSeconds` says, 10 seconds by default. Then it throws `rule:engine:timeout`. The variable holds for every Business Rule Task of the application, which is why the element has no field for a timeout. As a variable it can be overridden on an engine without a new build.

A decision takes about a millisecond on the runtime. A timeout therefore points to the network or to a runtime that is overloaded or just starting, not to a rule that is slow.

---

## See also

- [Run it in production]: the checklist for a runtime next to a production engine.
- [Set up and run your first decision]
- [Use an existing DMN decision]
