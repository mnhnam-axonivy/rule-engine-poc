# Run it in production

On your machine the decision runtime runs open, next to the Designer. In production it decides with the rules a business depends on. This page lists what changes on the way there.

The variables are those of [Set up and run your first decision]. They are set per application, in the Engine Cockpit or in the `variables.yaml` of the deployed project.

---

## Protect the API

Without a key, anybody who reaches the runtime can load rules into it and evaluate them. Give it one:

| Side | Setting |
| --- | --- |
| Container | Environment variable `RULE_ENGINE_API_KEY` |
| Axon Ivy Engine | Variable `RuleEngine.ApiKey`, the same secret |

A call without the right key is refused, and the Business Rule Task throws `rule:engine:dmn` with the code `UNAUTHORIZED`.

Keep the runtime away from the browsers of your users. The Portal and the connector call it from the engine, so only the engine needs to reach it. Its editor page, `/editor/`, is meant for development and for administrators; in production rule owners use the Portal, as on [Edit rules in the Portal].

For HTTPS, terminate TLS at a reverse proxy in front of the runtime, or give the runtime a certificate. It then serves HTTPS on port 8443 and no plain HTTP:

```bash
docker run -d --name axonivy-rule-engine -p 8443:8443 -v "$PWD/certs:/certs:ro" \
       --restart unless-stopped -e RULE_ENGINE_API_KEY=<secret> \
       -e QUARKUS_HTTP_SSL_CERTIFICATE_FILES=/certs/tls.crt \
       -e QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES=/certs/tls.key \
       -e QUARKUS_HTTP_INSECURE_REQUESTS=disabled \
       axonivy/rule-engine-runtime:dev
```

Trust settings on the engine side belong to the REST client `ruleEngine` of the connector.

---

## Keep the versions

The versions are files of the application, in the file area of the Axon Ivy Engine: under its data directory, `Data.Directory` of `ivy.yaml`, a folder `rule-engine` with a folder per model. Back them up with the other data of the engine. In a cluster, the data directory has to be on shared storage, as for every file of an application.

The runtime keeps nothing: it needs no volume, a new container loses nothing, and several runtime instances share nothing.

---

## Decide where the versions come from

| `RuleEngine.AutoDeploy` | What happens |
| --- | --- |
| `true` (default) | A project stores its `.dmn` files as versions when it starts and when a file has changed. A version that a rule owner activated is never replaced: the file is stored as a draft beside it. |
| `false` | The projects store nothing. Versions come only through the Portal or `RuleEngine.register` from Java. A model without a version answers `MODEL_NOT_FOUND`. |

Most installations keep `true`: the project brings the first version, the rule owners take over from there.

---

## Several applications, one runtime

Two applications can have a `.dmn` file with the same name, say `invoice-discount`. On a shared runtime they would be one model. Give each application a prefix:

| Application | `RuleEngine.ModelPrefix` | Model on the runtime |
| --- | --- | --- |
| Sales | `sales-` | `sales-invoice-discount` |
| Purchasing | `purchasing-` | `purchasing-invoice-discount` |

The Business Rule Task, the Portal and the Java API add the prefix themselves. Inside the application the model is still `invoice-discount`.

---

## Watch it

- Monitor the health check `http://<runtime>/q/health/ready`. It answers `UP` with the number of versions it holds in memory.
- `RuleEngine.TimeoutSeconds`, 10 by default, is how long a Business Rule Task waits. A decision takes about a millisecond, so a timeout points to the network or an overloaded runtime.
- `RuleEngine.LogRequests` set to `true` logs every request to the runtime and its answer, for a while, when you look for a problem.

---

## Checklist

- Every Business Rule Task has an Error Boundary Event, or the project has an Error Start Event for `rule:engine`, as on [Handle errors].
- `RULE_ENGINE_API_KEY` of the container and `RuleEngine.ApiKey` of the engine are set to the same secret.
- The runtime is reachable from the engine, not from the browsers of the users.
- The data directory of the engine, with the versions in `rule-engine`, is backed up.
- Applications that share the runtime have a `RuleEngine.ModelPrefix` each.
- The health check is monitored.

---

## See also

- [Handle errors]
- [Several decisions and the Java API]
