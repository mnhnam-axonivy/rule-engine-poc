# Axon Ivy Rules Engine

Replacement of the built-in Drools engine of Axon Ivy: DMN rules run in a separate decision runtime next to
the Ivy Engine and are used in processes through the program element **Business Rule Task**.
Based on Kogito and Drools from the open source project Apache KIE.

User documentation: [rule-engine-product/README.md](rule-engine-product/README.md).

```
 Axon Ivy                                        Decision runtime (one container, no data)
 ┌───────────────────────────────┐              ┌──────────────────────────────────────┐
 │ Ivy project                   │              │ REST API  /api/models/...            │
 │   dmn/*.dmn                   │  load        │   load a version into memory         │
 │   process with                │ ───────────► │   evaluate a decision                │
 │   Business Rule Task          │  evaluate    │ DMN engine (Apache KIE, interpreted) │
 │ rule-engine connector         │ ◄─────────── │ Compiled versions, in memory only    │
 │   client, mapping, generator  │   JSON       │                                      │
 │   versions: files of the app  │              │                                      │
 └───────────────────────────────┘              └──────────────────────────────────────┘
```

## Modules

| Module | Content |
| --- | --- |
| [rule-engine](rule-engine) | Ivy connector: program element `BusinessRuleTask`, the versions of the models in the file area of the application (`VersionStore`), client of the runtime (`com.axonivy.utils.rule.engine.api`: requests, responses, statuses), mapping, DMN generator, Java API `RuleEngine`. |
| [rule-engine-runtime](rule-engine-runtime) | The decision runtime, a Quarkus service and REST API, not an Ivy project: holds the versions the Ivy application loads into it, in memory only, and evaluates decisions. Stores nothing. Serves the DMN editor page `/editor/` to edit and check a `.dmn` file (Apache KIE DMN editor, fetched from npm by the build). Its generic image: `mvn package` writes the build context to `target/docker`, with the Dockerfile of `src/main/docker`; the `Dockerfile` of the folder builds the same image with Maven inside Docker, without Java on the host. Contract: [openapi.yaml](rule-engine-runtime/openapi.yaml). |
| [rule-engine-demo](rule-engine-demo) | The demo: an invoice with five lines whose discount is decided by one decision table, `dmn/invoice-discount.dmn`, from the customer tier and the order amount; a page to change the invoice and see the rule that decided. |
| [rule-engine-test](rule-engine-test) | Tests of the connector: with a mocked runtime and against the real container. |
| [rule-engine-product](rule-engine-product) | Market product: README, product.json. |
| [portal](portal), [portal-components](portal-components) | Axon Ivy Portal 14.0.1.2 with the tab **Rule Management** in its Admin Settings. Not part of the Maven build of the rule engine, see [Rule Management in the Portal](#rule-management-in-the-portal). |

## Run it

The decision runtime is the image `axonivy/rule-engine-runtime:dev`. Build it in one of two ways, in the root of the
repository. Both build the same image.

| Build with | Commands | What happens |
| --- | --- | --- |
| Docker only | `docker build -t axonivy/rule-engine-runtime:dev rule-engine-runtime` | Maven runs inside Docker, with [rule-engine-runtime/Dockerfile](rule-engine-runtime/Dockerfile). No Java or Maven on the host. |
| Maven | `mvn package -f rule-engine-runtime`, then `docker build -t axonivy/rule-engine-runtime:dev rule-engine-runtime/target/docker` | Java 21 or newer and Maven on the host. `mvn package` writes the build context of the image to `rule-engine-runtime/target/docker`: the Dockerfile of [src/main/docker](rule-engine-runtime/src/main/docker/Dockerfile), `LICENSE`, `NOTICE` and the application in `quarkus-app/`. |

Then start it:

```bash
docker run -d --name axonivy-rule-engine -p 8090:8080 --restart unless-stopped axonivy/rule-engine-runtime:dev
```

| Part | What it does |
| --- | --- |
| `-p 8090:8080` | The runtime on `http://localhost:8090`, the default of `RuleEngine.Url`. Another port: `-p 8091:8080`, and set `RuleEngine.Url` to it. |
| `--restart unless-stopped` | Starts the runtime again with Docker, unless you stopped it. |
| `-e RULE_ENGINE_API_KEY=<secret>` | Not needed on your machine. Protects `/api`; the engine sends the same secret, `RuleEngine.ApiKey`. `--env-file .env` reads it from a file instead. |

| To | Run |
| --- | --- |
| Stop it, start it again | `docker stop axonivy-rule-engine`, `docker start axonivy-rule-engine` |
| See why it does not start | `docker logs axonivy-rule-engine` |
| Use a new image | `docker rm -f axonivy-rule-engine`, then `docker run` as above. Nothing is lost: the Ivy application loads its versions into the new container. |

The runtime needs no volume: the versions are files of the Ivy application, see
[Where the versions are](#where-the-versions-are).

### All in one with Maven

```bash
mvn clean install -f rule-engine-runtime
```

Builds and tests the runtime, builds the image from `target/docker`, removes the container `axonivy-rule-engine`
even if it runs, and runs the new image in its place, with the options of the `docker run` above. The image is built
before the old container is removed, so a failed build leaves the old one running.

| Property | Default |
| --- | --- |
| `docker.port` | `8090` |
| `docker.api-key` | empty: the API is open |
| `docker.container` | `axonivy-rule-engine` |
| `docker.image` | `axonivy/rule-engine-runtime:dev` |
| `docker.skip` | `false`. `-Ddocker.skip=true` installs without Docker: no image, the container stays as it is. |

For example `mvn clean install -f rule-engine-runtime -Ddocker.api-key=<secret>`. `mvn install` in the root of the
repository does the same for the runtime; `mvn verify` and `mvn package` never touch Docker.

Import `rule-engine`, `rule-engine-demo` into the Designer and start `InvoicePricingDemo/start.ivp`:
an invoice with five lines and its discount, decided by the rules. Change the customer tier or a quantity and click **Apply rules**.
The variable `RuleEngine.Url` points to `http://localhost:8090` by default.
Change the rules in the Portal, or change the `.dmn` file of the project, also in the browser at
`http://localhost:8090/editor/`, and apply them again.

The runtime on its own: load a version into its memory, then evaluate it.

```
curl -X PUT -H "Content-Type: application/xml" --data-binary @rule-engine-demo/dmn/invoice-discount.dmn \
     http://localhost:8090/api/models/invoice-discount/versions/1
curl -X POST -H "Content-Type: application/json" \
     -d '{"decision":"discount","input":{"customerTier":"Gold","orderAmount":12000}}' \
     http://localhost:8090/api/models/invoice-discount/versions/1/evaluate
# {"decision":"discount","model":"invoice-discount","result":{"discountPercent":8,
#  "discountReason":"Gold, order of 10,000 or more"},"results":{...},"version":1}
```

Every input of the decisions it needs must be in `input`, `null` if it has no value.

## Build and test

Java 25 and Maven. The Ivy modules are built with the project-build-plugin, which runs the tests in an Ivy engine.

| Command | What runs |
| --- | --- |
| `mvn verify` | Everything without Docker: tests of the runtime (REST API, versions in memory, generated DMN checked by the KIE validator and executed in the engine) and tests of the connector (versions and their life cycle in the file area, Business Rule Task, Portal bridge) with a mocked runtime. |
| The image built, see [Run it](#run-it), then `mvn verify -Pcontainer.test -pl rule-engine-test -am` | `RuleEngineContainerTest`: the demo invoice and the test processes against the real runtime in a container (Testcontainers), including a rule changed and an input added as in the Portal. If Testcontainers times out waiting for the container (seen with Rancher Desktop), set `TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1`. |
| `mvn test -Pperformance -f rule-engine-runtime` | Performance baseline, written to `rule-engine-runtime/target/performance-baseline.md`. |
| `mvn quarkus:dev -f rule-engine-runtime` | Runtime in dev mode on port 8090. |
| `mvn package -f rule-engine-runtime` | Runtime with its tests, and the build context of its image in `rule-engine-runtime/target/docker`. `DockerfileTest` fails if the two Dockerfiles do not describe the same image. |
| `mvn clean install -f rule-engine-runtime` | The same, then the new image replaces the running container, see [All in one with Maven](#all-in-one-with-maven). |

## How the REST client is built

The connector calls the runtime in Java, with JAX-RS on the REST client of Axon Ivy:

- `client`: `RuleEngineClient` with a builder (`baseUrl`, `apiKey`, timeouts, `logRequests`). Each method is one
  JAX-RS call on the REST client `ruleEngine` of rest-clients.yaml (`Ivy.rest().client`), so the calls appear in the
  tracing of the engine, and rest-clients.yaml is the place for TLS settings. Its `JsonFeature` maps the records to
  JSON and back. Failures become exceptions for unreachable, timeout and error answers; an error answer keeps the
  error code of the runtime.
- `client.internal.RuntimeUrlFeature`, registered on `ruleEngine`: sends each call to the base URL of the client
  (the variable `RuleEngine.Url`) and keeps the path of the call. `RequestLogFilter` logs the calls when
  `RuleEngine.LogRequests` is on.
- The typed requests and responses are the records of `com.axonivy.utils.rule.engine.api`, sent as JSON. The runtime
  has records of its own; `ContractTest` fails if the two do not have the same fields.
- Tests replace the runtime by `MockRuntime`, a REST service of the test project.

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
- The runtime checks a DMN file (`/api/validate`) before it is stored.
- `RuntimeLoader` loads a version into the runtime before its first evaluation in this engine. When the runtime
  answers that it does not hold it (after a restart, or when it made room), it loads it again and evaluates once more.
  The runtime keeps at most `rule.engine.model-cache-size` versions (100), dropping the one used least recently.

## Spike result: runtime loading (MARP-4893)

Runtime loading works and is used. The fallback (Kogito code generation with a pipeline template) is not needed.

- DMN files are compiled in memory with `DMNRuntimeBuilder` of `kie-dmn-core`, the same engine Kogito uses.
  No code generation, no KieContainer, no restart. This is the mechanism of the Kogito JIT executor.
- Kogito's Quarkus extension is not used: it compiles models at build time, which is the opposite of what we need.
- A version is an immutable compiled model. Loading a version again swaps one reference,
  evaluations that are running keep the version they started with.
- Versions: Apache KIE 10.2.0 on Quarkus 3.38. It reads DMN 1.1 to 1.6. The generator writes DMN 1.5,
  which the KIE editor upgrades to 1.6 on save.
- Risk that stays: `org.kie.dmn.core.internal.utils` is internal API of KIE. It is isolated in
  `DmnCompiler` and `DmnEvaluator` and covered by tests, so an upgrade of KIE shows a break at once.
- Inputs are type-checked (`rule.engine.type-check=true`): a value outside of the allowed values of an enum
  fails the evaluation instead of silently matching no rule.

## Performance baseline (MARP-4893)

Decision table with 1,000 rules, 2 inputs (number range, string), hit policy FIRST, 206 KB of DMN.
Measured on a 12 core Windows 11 machine with Java 25, runtime and client on the same host.

| Step | Time |
| --- | --- |
| Validate (schema, model, compilation), first call | 3.2 s |
| Compile (load a version) | 0.7 s |

| Evaluation | mean | p50 | p95 | p99 |
| --- | --- | --- | --- | --- |
| In the engine, last rule matches | 0.26 ms | 0.28 ms | 0.43 ms | 0.56 ms |
| In the engine, no rule matches | 0.36 ms | 0.37 ms | 0.58 ms | 0.77 ms |
| REST API, 1 client | 1.59 ms | 1.54 ms | 2.34 ms | 2.75 ms |

Throughput through the REST API with 8 parallel clients: 2,243 evaluations per second.
The position of the matching rule does not matter: the engine evaluates all rows of a table.

## Rule Management in the Portal

The Portal has no dependency on the rule engine. It finds it the way it finds Smart Workflow: by the signature of
a callable sub process in the security context (`SubProcessCallStartEvent.find` with `SearchScope.SECURITY_CONTEXT`).
If it is there, the Admin Settings show the tab **Rule Management**; otherwise nothing changes.

| Side | Where | What |
| --- | --- | --- |
| Rule engine | `rule-engine/process/Portal/RuleManagement.p.json`, `com.axonivy.utils.rule.engine.portal.RuleManagement` | The bridge. One start per call, every start answers `result` and `error`, made of JDK types only (String, Integer, Boolean, List, Map), so the Portal needs no class of the rule engine. |
| Rule engine | `com.axonivy.utils.rule.engine.portal.DmnEditorScript` | REST resource `{app}/api/rule-engine/dmn-editor/dmn-editor.js`: the KIE DMN editor, fetched once from the runtime (`/editor/lib/dmn-editor.js`) and kept in memory. The browser never talks to the runtime. |
| Portal | `com.axonivy.portal.rule.RuleEngineBridge` | Detection (`isAvailable()`) and the calls, through `IvyAdapterService`. |
| Portal | `com.axonivy.portal.bean.RuleManagementBean`, component `com.axonivy.portal.component.RuleManagement`, `rule-management.js` | The tab: the rules, their versions, the editor. Texts under `/ch.ivy.addon.portalkit.ui.jsf/components/RuleManagement` in all five languages. |

The contract, tested by `TestRuleManagement` in `rule-engine-test`:

| Signature | Result |
| --- | --- |
| `ruleManagementModels()` | The stored models and the `.dmn` files of the projects that are not stored yet: name, stored, activeVersion, latestVersion, versionCount, decisions, project, file; pendingVersion and pendingFrom if the application brought a version that is not live (a draft from a project, newer than the active version). The Portal detects the rule engine by this start. |
| `ruleManagementInfo()` | runtimeUrl, reachable, editorScript (link of the editor script on this engine), modelPrefix |
| `ruleManagementVersions(String)` | The versions of a model, newest first: version, status, source, author, description, createdAt, effectiveDate (live from), activatedBy, previousVersion |
| `ruleManagementContent(String,Integer)` | model, version, dmn; version null opens the active one, a project file is read from its project |
| `ruleManagementValidate(String)` | valid, messages (the findings of the DMN engine) |
| `ruleManagementSave(String,String,String,String,Boolean)` | model, dmn, author, description, activate: a new version with the source *portal*, as draft or live |
| `ruleManagementActivate(String,Integer,String)` | model, version, author |
| `ruleManagementRollback(String,String)` | model, author |
| `ruleManagementRetire(String,Integer)` | model, version: withdraws a version that is not active, e.g. a version of the application the rule owner does not want |

`error` is null on success, else a map with `code` (UNREACHABLE, TIMEOUT, NOT_CONFIGURED, an error code such as
INVALID_MODEL or INVALID_STATE, or FAILED), `message` and `details`. Listing and opening versions needs no runtime;
checking and saving does.

Build the Portal with `mvn install -f portal-components` and `mvn verify -f portal`.

## Decisions taken in this implementation

- **Two projects, two deployables.** `rule-engine` is the Ivy connector; `rule-engine-runtime` is the decision
  runtime, a Maven project of its own (Java 21, Quarkus) and no Ivy project. The two share no code, only JSON: each
  has its records for the requests and responses. So the image is built from `rule-engine-runtime` alone, without Ivy
  tooling. What keeps the two in step is `ContractTest` of the runtime: its tests, and only they, compile the packages
  `api` and `dmn.generator` of the connector. These two packages are plain Java 21 on purpose; any use of the Ivy API
  in them breaks the tests of the runtime at once.

- **Versions are files of the Ivy application**, in its file area, not in the system database as MARP-4895
  describes, and not in the runtime. The runtime holds no data and needs no volume; several runtime instances share
  nothing. An Ivy cluster needs the data directory on shared storage, as for any application files; two nodes that
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
- **Model names**: the name of a model is the file name of the `.dmn` file. On the runtime,
  `RuleEngine.ModelPrefix` is put in front of it: it separates applications that share one runtime.
- **Packed projects**: an engine keeps a deployed project as its `.iar`; `Project.directory()` is then that file.
  `ProjectDmnFiles` reads the `.dmn` files from it as a zip (under `META-INF/ivy`), so the Business Rule Task and
  the Portal find them on an engine as in the Designer. Writing a file (New DMN, `RuleEngine.export`)
  needs the project folder of the Designer.
- **Inputs of the version that answers**: the Business Rule Task evaluates the active version of the store, and
  takes the inputs its decision needs from what the runtime answers when it loads that version. A version activated
  in the Portal or through the API may need more than the `.dmn` file of the project, e.g. a column a rule owner
  added; the task finds them in the process data by name.
- **Portal editor in the engine, not the runtime**: the Portal tab could embed the page `/editor/` of the
  runtime in a frame. It does not: that page needs the runtime to be reachable from every browser and the API key
  in the browser, and it has its own look. The tab is built from Portal components;
  only the KIE editor itself is embedded, served by the engine.
