# Axon Ivy Rules Engine

Replacement of the built-in Drools engine of Axon Ivy: DMN rules are evaluated by the DMN engine of Apache KIE, in a
container next to the Ivy Engine, and are used in processes through the program element **Business Rule Task**.

User documentation: [rule-engine-product/README.md](rule-engine-product/README.md).

```
 Axon Ivy                                        DMN engine (container of Apache KIE, no data)
 ┌───────────────────────────────┐              ┌──────────────────────────────────────┐
 │ Ivy project                   │              │ Apache KIE JIT runner                │
 │   dmn/*.dmn                   │  validate    │   POST /jitdmn/validate              │
 │   process with                │ ───────────► │   POST /jitdmn/dmnresult             │
 │   Business Rule Task          │  evaluate    │ DMN engine (Drools DMN, interpreted) │
 │ rule-engine connector         │ ◄─────────── │ Every call carries the DMN file,     │
 │   versions: files of the app  │   JSON       │ nothing is kept                      │
 │   client, mapping, generator  │              │                                      │
 │   DMN editor (KIE, bundled)   │              │                                      │
 └───────────────────────────────┘              └──────────────────────────────────────┘
```

## Modules

| Module | Content |
| --- | --- |
| [rule-engine](rule-engine) | Ivy connector: program element `BusinessRuleTask`, the versions of the models in the file area of the application (`VersionStore`), client of the DMN engine container, mapping, DMN generator, Java API `RuleEngine`, and the Apache KIE DMN editor (bundled by its Maven build from npm) for the Portal and the page `{application}/api/rule-engine/editor`. |
| [rule-engine-demo](rule-engine-demo) | The demo: an invoice with five lines whose discount is decided by one decision table, `dmn/invoice-discount.dmn`, from the customer tier and the order amount; a page to change the invoice and see the rule that decided. |
| [rule-engine-test](rule-engine-test) | Tests of the connector: with a mocked DMN engine and against the real container. |
| [rule-engine-product](rule-engine-product) | Market product: README, product.json. |
| [portal](portal), [portal-components](portal-components) | Axon Ivy Portal 14.0.1.2 with the tab **Rule Management** in its Admin Settings. Not part of the Maven build of the rule engine, see [Rule Management in the Portal](#rule-management-in-the-portal). |

## Run it

The DMN engine is the image `apache/incubator-kie-kogito-jit-runner` of Apache KIE, the same release as the DMN
editor of the connector:

```bash
docker run -d --name axonivy-rule-engine -p 127.0.0.1:8090:8080 --restart unless-stopped \
       apache/incubator-kie-kogito-jit-runner:10.2.0
```

| Part | What it does |
| --- | --- |
| `-p 127.0.0.1:8090:8080` | The engine on `http://localhost:8090`, the default of `RuleEngine.Url`, reachable from this host only. It has no authentication: run it where only the Ivy Engine reaches it. Another port: `-p 127.0.0.1:8091:8080`, and set `RuleEngine.Url` to it. |
| `--restart unless-stopped` | Starts the engine again with Docker, unless you stopped it. |

| To | Run |
| --- | --- |
| Stop it, start it again | `docker stop axonivy-rule-engine`, `docker start axonivy-rule-engine` |
| See why it does not start | `docker logs axonivy-rule-engine` |
| Use a new image | `docker rm -f axonivy-rule-engine`, then `docker run` as above. Nothing is lost: the container keeps nothing. |

The container needs no volume: the versions are files of the Ivy application, see
[Where the versions are](#where-the-versions-are).

Import `rule-engine`, `rule-engine-demo` into the Designer and start `InvoicePricingDemo/start.ivp`:
an invoice with five lines and its discount, decided by the rules. Change the customer tier or a quantity and click
**Apply rules**. Change the rules in the Portal, or change the `.dmn` file of the project, also in the browser with
the page `{application}/api/rule-engine/editor` of the engine, and apply them again.

The DMN engine on its own: check a file, and evaluate it with inputs (with [jq](https://jqlang.org)):

```bash
curl -X POST -H "Content-Type: application/xml" --data-binary @rule-engine-demo/dmn/invoice-discount.dmn \
     http://localhost:8090/jitdmn/validate
jq -n --rawfile model rule-engine-demo/dmn/invoice-discount.dmn \
      '{model: $model, context: {customerTier: "Gold", orderAmount: 12000}}' \
  | curl -X POST -H "Content-Type: application/json" --data-binary @- http://localhost:8090/jitdmn/dmnresult
# {"decisionResults":[{"decisionName":"discount","evaluationStatus":"SUCCEEDED",
#   "result":{"discountPercent":8,"discountReason":"Gold, order of 10,000 or more"},...}],...}
```

The engine evaluates every decision of the model. A decision whose inputs are missing fails without harming the
others; the connector only looks at the decision it asked for.

## Build and test

Java 25 and Maven. The Ivy modules are built with the project-build-plugin, which runs the tests in an Ivy engine.
The build of `rule-engine` downloads the DMN editor `@kie-tools/dmn-editor-standalone` from npm once, checks its
SHA-512 and keeps it in `~/.m2/repository/.cache/kie-tools`.

| Command | What runs |
| --- | --- |
| `mvn verify` | The tests of the connector (versions and their life cycle in the file area, Business Rule Task, evaluation of a version, Portal bridge, bundled editor) with a mocked DMN engine. No Docker. |
| `mvn verify -Pcontainer.test -pl rule-engine-test -am` | `RuleEngineContainerTest`: the demo invoice and the test processes against the real DMN engine in a container (Testcontainers pulls the image), including a rule changed and an input added as in the Portal. If Testcontainers times out waiting for the container (seen with Rancher Desktop), set `TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1`. |

A Maven build rewrites the `target` folders of the projects that a running Designer uses: stop the engine of the
Designer first, or it redeploys the application.

## How the REST client is built

The connector calls the DMN engine in Java, with JAX-RS on the REST client of Axon Ivy:

- `client.RuleEngineClient` with a builder (`baseUrl`, timeouts, `logRequests`): `validate(dmn)`,
  `evaluate(dmn, input)` and `isReady()`. Each method is one JAX-RS call on the REST client `ruleEngine` of
  rest-clients.yaml (`Ivy.rest().client`), so the calls appear in the tracing of the engine, and rest-clients.yaml is
  the place for TLS settings. Failures become exceptions for unreachable, timeout and error answers; a model the
  engine cannot read is `INVALID_MODEL`.
- `client.internal.JitDmn` holds the JSON of the JIT runner; `client.DmnResult` is what the connector reads from it:
  every decision with its result, status and findings.
- `deploy.Evaluator` evaluates a stored version: it sends the DMN file of the version, keeps the decision that was
  asked for and answers `EvaluateResponse`, or `DECISION_NOT_FOUND` and `EVALUATION_FAILED`.
- `client.internal.RuntimeUrlFeature`, registered on `ruleEngine`: sends each call to the base URL of the client
  (the variable `RuleEngine.Url`) and keeps the path of the call. `RequestLogFilter` logs the calls when
  `RuleEngine.LogRequests` is on.
- Tests replace the DMN engine by `MockDmnEngine`, a REST service of the test project with the endpoints of the JIT
  runner.

## Where the versions are

The versions of the models are files of the Ivy application, in its file area, where IvyScript `File` writes too:
`[Data.Directory]/<security system>/apps/<application>/files/application/rule-engine/` of the engine, e.g. under
`/ivy/data` in the Docker image of the engine. `VersionStore` keeps them, one folder per model:

```
rule-engine/discount/versions.json   status, source, author, dates and previous version of each version
rule-engine/discount/1.dmn           the DMN file of version 1, never changed once written
rule-engine/discount/2.dmn
```

- The store enforces the life cycle: draft, approved, active, retired; at most one active version; an effective date;
  a rollback to the version that was active before. A version activated from the Portal is never replaced by a
  project deployment.
- `versions.json` is replaced in one step, so a reader sees the old or the new file. Changes are serialized within
  the engine. A version whose effective date has come is activated by the first read after it.
- The DMN engine checks a DMN file (`/jitdmn/validate`) before it is stored.
- Every evaluation sends the DMN file of the version to the DMN engine; there is nothing to load or keep in step.
- In the Designer, the file area of the Developer application is under the `work` folder of its engine, which the
  Designer clears when it redeploys the application: versions saved in the Portal are lost there. The files of the
  projects are stored again on their next start.

## Spike result: evaluation without code generation (MARP-4893)

Evaluating models that change at runtime works without code generation and without a service of our own.

- The JIT runner of Apache KIE (`jitexecutor` of `incubator-kie-kogito-apps`) compiles the DMN file of each request
  in memory with the DMN engine of Drools, the same engine Kogito uses, and evaluates it. No KieContainer, no
  restart, no data in the container.
- Kogito's Quarkus extension is not used: it compiles models at build time, which is the opposite of what we need.
- Versions: Apache KIE 10.2.0. It reads DMN 1.1 to 1.6. The generator writes DMN 1.5, which the KIE editor upgrades
  to 1.6 on save.
- Inputs are checked against the allowed values of their type: `"Platinum"` for a type that allows `"Gold"` and
  `"Silver"` fails the evaluation. A value of the wrong base type, e.g. a text for a number, is not refused: the
  expressions that use it give null.

## Performance baseline (MARP-4893)

The JIT runner compiles the model of every request, so the time grows with the size of the model. Measured against
the container on the same host (12 core Windows 11 machine), through its REST API, with one client.

| Model | Size | First call | mean | p50 | p95 |
| --- | --- | --- | --- | --- | --- |
| `invoice-discount.dmn` of the demo, 7 rules | 7.5 KB | 143 ms | 14.5 ms | 13.8 ms | 21.6 ms |
| Decision table with 1,000 rules, 2 inputs, hit policy FIRST | 232 KB | 417 ms | 228 ms | 233 ms | 288 ms |

Validating the demo model takes 22 ms (p50).

## Rule Management in the Portal

The Portal has no dependency on the rule engine. It finds it the way it finds Smart Workflow: by the signature of
a callable sub process in the security context (`SubProcessCallStartEvent.find` with `SearchScope.SECURITY_CONTEXT`).
If it is there, the Admin Settings show the tab **Rule Management**; otherwise nothing changes.

| Side | Where | What |
| --- | --- | --- |
| Rule engine | `rule-engine/process/Portal/RuleManagement.p.json`, `com.axonivy.utils.rule.engine.portal.RuleManagement` | The bridge. One start per call, every start answers `result` and `error`, made of JDK types only (String, Integer, Boolean, List, Map), so the Portal needs no class of the rule engine. |
| Rule engine | `com.axonivy.utils.rule.engine.editor.DmnEditor` | REST resource `{app}/api/rule-engine/dmn-editor/dmn-editor.js`: the KIE DMN editor bundled in the connector, with an ETag. Also the page `{app}/api/rule-engine/editor`. |
| Portal | `com.axonivy.portal.rule.RuleEngineBridge` | Detection (`isAvailable()`) and the calls, through `IvyAdapterService`. |
| Portal | `com.axonivy.portal.bean.RuleManagementBean`, component `com.axonivy.portal.component.RuleManagement`, `rule-management.js` | The tab: the rules, their versions, the editor. Texts under `/ch.ivy.addon.portalkit.ui.jsf/components/RuleManagement` in all five languages. |

The contract, tested by `TestRuleManagement` in `rule-engine-test`:

| Signature | Result |
| --- | --- |
| `ruleManagementModels()` | The stored models and the `.dmn` files of the projects that are not stored yet: name, stored, activeVersion, latestVersion, versionCount, decisions, project, file; pendingVersion and pendingFrom if the application brought a version that is not live (a draft from a project, newer than the active version). The Portal detects the rule engine by this start. |
| `ruleManagementInfo()` | runtimeUrl (the URL of the DMN engine), reachable, editorScript (link of the editor script on this engine) |
| `ruleManagementVersions(String)` | The versions of a model, newest first: version, status, source, author, description, createdAt, effectiveDate (live from), activatedBy, previousVersion |
| `ruleManagementContent(String,Integer)` | model, version, dmn; version null opens the active one, a project file is read from its project |
| `ruleManagementValidate(String)` | valid, messages (the findings of the DMN engine) |
| `ruleManagementSave(String,String,String,String,Boolean)` | model, dmn, author, description, activate: a new version with the source *portal*, as draft or live |
| `ruleManagementActivate(String,Integer,String)` | model, version, author |
| `ruleManagementRollback(String,String)` | model, author |
| `ruleManagementRetire(String,Integer)` | model, version: withdraws a version that is not active, e.g. a version of the application the rule owner does not want |

`error` is null on success, else a map with `code` (UNREACHABLE, TIMEOUT, NOT_CONFIGURED, an error code such as
INVALID_MODEL or INVALID_STATE, or FAILED), `message` and `details`. Listing and opening versions needs no DMN engine;
checking and saving does.

Build the Portal with `mvn install -f portal-components` and `mvn verify -f portal`.

## Decisions taken in this implementation

- **One Ivy connector, the DMN engine of Apache KIE.** `rule-engine` holds everything of ours: the versions, the
  client, the mapping, the generator and the editor. The container is the JIT runner of Apache KIE as it is
  published; there is no image to build and no code of ours in it.
- **Every evaluation carries the DMN file.** The container keeps nothing, so a restart, several containers or a new
  image change nothing. The price is the compilation of the model with each evaluation, see
  [Performance baseline](#performance-baseline-marp-4893).
- **Versions are files of the Ivy application**, in its file area, not in the system database as MARP-4895
  describes. An Ivy cluster needs the data directory on shared storage, as for any application files; two nodes that
  change the same model at the same moment are not coordinated.
- **Dropdown**: the inscription API of 14.0.1 has no single-select and no button. The decision dropdown is the
  multi-select widget, the first selected entry is used.
- **New DMN...**: without a change event, selecting the entry cannot generate the file at that moment.
  The file is generated when an inscription mask of the project is opened the next time, or when the element
  is executed. The selection switches to the generated decision through the config migrator of the element.
- **Mapping by name only**: inputs and results are always found by name. A value that has to be prepared, or a
  field with another name, is set in the process data before the element, for example in a Script. Input fields
  and Output fields only describe a new DMN.
- **Field picker**: fields are entered as field paths in text fields, until Core delivers a data picker.
- **Model names**: the name of a model is the file name of the `.dmn` file.
- **Packed projects**: an engine keeps a deployed project as its `.iar`; `Project.directory()` is then that file.
  `ProjectDmnFiles` reads the `.dmn` files from it as a zip (under `META-INF/ivy`), so the Business Rule Task and
  the Portal find them on an engine as in the Designer. Writing a file (New DMN, `RuleEngine.export`)
  needs the project folder of the Designer.
- **Inputs of the version that answers**: the Business Rule Task evaluates the active version of the store, and
  reads the inputs its decision needs, directly or through the decisions it requires, from the DMN file of that
  version. A version activated in the Portal or through the API may need more than the `.dmn` file of the project,
  e.g. a column a rule owner added; the task finds them in the process data by name.
- **The editor is served by the engine**: the browser never talks to the DMN engine, which has no authentication.
  The Portal tab is built from Portal components; only the KIE editor itself is embedded. The page
  `{application}/api/rule-engine/editor` lets a logged in user check a model with the DMN engine.
