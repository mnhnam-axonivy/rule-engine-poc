# Run it in production

On your machine the DMN engine runs next to the Designer, reachable from your computer only. In production it decides with the rules a business depends on. This page lists what changes on the way there.

The variables are those of [Set up and run your first decision]. They are set per application, in the Engine Cockpit or in the `variables.yaml` of the deployed project.

---

## Keep the DMN engine private

The DMN engine, the JIT runner of Apache KIE, has no authentication: anybody who reaches it can let it evaluate any model. Run it where only the Axon Ivy Engine reaches it:

- in a private network of the containers, without a published port, or
- on the host of the engine, with the port bound to `127.0.0.1`, as on [Set up and run your first decision].

Keep it away from the browsers of your users. The Portal and the connector call it from the engine, so only the engine needs to reach it. The editor dialog, project `rule-engine-editor`, also checks and saves through the engine. Deploy it only where rules are changed in the browser: an application that only evaluates rules does not need it. For now anybody who reaches the engine may open its process start `RuleEditor/editRule.ivp`; a permission comes later. In production rule owners use it in the Portal, as on [Edit rules in the Portal].

For HTTPS, terminate TLS at a reverse proxy in front of the DMN engine. Trust settings on the engine side belong to the REST client `ruleEngine` of the connector.

---

## Keep the versions

The versions are files of the application, in the file area of the Axon Ivy Engine: under its data directory, `Data.Directory` of `ivy.yaml`, a folder `rule-engine` with a folder per model. Back them up with the other data of the engine. In a cluster, the data directory has to be on shared storage, as for every file of an application.

The DMN engine keeps nothing: it needs no volume, a new container loses nothing, several containers share nothing, and several applications can use the same one.

---

## Decide where the versions come from

| `RuleEngine.AutoDeploy` | What happens |
| --- | --- |
| `true` (default) | A project stores its `.dmn` files as versions when it starts and when a file has changed. A version that a rule owner activated is never replaced: the file is stored as a draft beside it. |
| `false` | The projects store nothing. Versions come only through the Portal or `RuleEngine.register` from Java. A model without a version answers `MODEL_NOT_FOUND`. |

Most installations keep `true`: the project brings the first version, the rule owners take over from there.

---

## Size it

The DMN engine compiles the model with every evaluation. A small table takes some 15 milliseconds, a table of 1,000 rules about a quarter of a second, on one core. Give the container the cores your processes need at their busiest, or run several containers behind a load balancer: they share nothing.

---

## Watch it

- Monitor the health check `http://<dmn engine>/q/health/ready`. It answers `UP` when the engine is ready.
- `RuleEngine.TimeoutSeconds`, 10 by default, is how long a Business Rule Task waits. A timeout points to the network, an overloaded DMN engine or a very large model.
- `RuleEngine.LogRequests` set to `true` logs every request to the DMN engine and its answer, for a while, when you look for a problem.

---

## Checklist

- Every Business Rule Task has an Error Boundary Event, or the project has an Error Start Event for `rule:engine`, as on [Handle errors].
- The DMN engine is reachable from the engine only, not from the browsers of the users or the internet.
- The data directory of the engine, with the versions in `rule-engine`, is backed up.
- The health check is monitored.

---

## See also

- [Handle errors]
- [Several decisions and the Java API]
