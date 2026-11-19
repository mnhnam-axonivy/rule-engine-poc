# Run it in production

On your machine the decision runtime runs open, next to the Designer, and a lost volume costs nothing. In production it holds the rules a business depends on. This page lists what changes on the way there.

The variables are those of [Set up and run your first decision]. They are set per application, in the Engine Cockpit or in the `variables.yaml` of the deployed project.

---

## Protect the API

Without a key, anybody who reaches the runtime can load and activate rules. Give it one:

| Side | Setting |
| --- | --- |
| Container | Environment variable `RULE_ENGINE_API_KEY` |
| Axon Ivy Engine | Variable `RuleEngine.ApiKey`, the same secret |

A call without the right key is refused, and the Business Rule Task throws `rule:engine:dmn` with the code `UNAUTHORIZED`.

Keep the runtime away from the browsers of your users. The Portal and the connector call it from the engine, so only the engine needs to reach it. Its editor page, `/editor/`, is meant for development and for administrators; in production rule owners use the Portal, as on [Edit rules in the Portal].

For HTTPS, terminate TLS at a reverse proxy in front of the runtime, or give the runtime a certificate: the Docker Compose file has the settings as comments (`QUARKUS_HTTP_SSL_CERTIFICATE_FILES`, `QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES`). Trust settings on the engine side belong to the REST client `ruleEngine` of the connector.

---

## Keep the versions

The runtime stores every version of every model in a database file under `/data` in the container. Mount a volume there, or the versions of your rule owners are gone with the next container:

```yaml
volumes:
  - rule-engine-data:/data
```

Back the volume up like any other database. Several runtime instances need one shared database instead of a file each; the store is JPA, so that is a matter of configuration and a driver.

---

## Decide who loads the models

| `RuleEngine.AutoDeploy` | What happens |
| --- | --- |
| `true` (default) | A project registers its `.dmn` files when it starts and when a file has changed. A version that a rule owner activated is never replaced: the file is stored as a draft beside it. |
| `false` | The projects send nothing. Models come only through the Portal, the API or `RuleEngine.register` from Java. A model the runtime does not have answers `MODEL_NOT_FOUND`. |

Most installations keep `true`: the project brings the first version, the rule owners take over from there.

---

## Several applications, one runtime

Two applications can have a `.dmn` file with the same name, say `invoice-pricing`. On a shared runtime they would be one model. Give each application a prefix:

| Application | `RuleEngine.ModelPrefix` | Model on the runtime |
| --- | --- | --- |
| Sales | `sales-` | `sales-invoice-pricing` |
| Purchasing | `purchasing-` | `purchasing-invoice-pricing` |

The Business Rule Task, the Portal and the Java API add the prefix themselves. Inside the application the model is still `invoice-pricing`.

---

## Watch it

- Monitor the health check `http://<runtime>/q/health/ready`. It answers `UP` with the number of active models.
- `RuleEngine.TimeoutSeconds`, 10 by default, is how long a Business Rule Task waits. A decision takes about a millisecond, so a timeout points to the network or an overloaded runtime.
- `RuleEngine.LogRequests` set to `true` logs every request to the runtime and its answer, for a while, when you look for a problem.

---

## Checklist

- Every Business Rule Task has an Error Boundary Event, or the project has an Error Start Event for `rule:engine`, as on [Handle errors].
- `RULE_ENGINE_API_KEY` of the container and `RuleEngine.ApiKey` of the engine are set to the same secret.
- The runtime is reachable from the engine, not from the browsers of the users.
- The container has a volume for `/data`, and the volume is backed up.
- Applications that share the runtime have a `RuleEngine.ModelPrefix` each.
- The health check is monitored.

---

## See also

- [Handle errors]
- [Several decisions and the Java API]
