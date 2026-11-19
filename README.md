# Axon Ivy Rules Engine

Replacement of the built-in Drools engine of Axon Ivy: DMN rules run in a separate decision runtime next to
the Ivy Engine and are used in processes through the program element **Business Rule Task**.
Based on Kogito and Drools from the open source project Apache KIE.

User documentation: [rule-engine-product/README.md](rule-engine-product/README.md).

```
 Axon Ivy                                        Decision runtime (one container)
 ┌───────────────────────────────┐              ┌──────────────────────────────────────┐
 │ Ivy project                   │              │ REST API  /api/models/...            │
 │   dmn/*.dmn                   │  register    │   load version, activate, rollback   │
 │   process with                │ ───────────► │   evaluate decision                  │
 │   Business Rule Task          │  evaluate    │ DMN engine (Apache KIE, interpreted) │
 │ rule-engine connector         │ ◄─────────── │ Database with all versions (H2,      │
 │   client, mapping             │   JSON       │   volume /data)                      │
 └───────────────┬───────────────┘              └───────────────────┬──────────────────┘
                 │            rule-engine-core                      │
                 └──────────  API records, DMN generator  ──────────┘
```

## Modules

| Module | Content |
| --- | --- |
| [rule-engine-core](rule-engine-core) | Ivy project, plain Java, shared by the connector and the runtime: the API of the runtime (`com.axonivy.utils.rule.engine.api`: requests, responses, statuses) and the DMN generator. |
| [rule-engine-runtime](rule-engine-runtime) | Quarkus service: loads DMN models at runtime, stores their versions, evaluates decisions. Compiles the sources of `rule-engine-core` into its own build. Serves the DMN editor page `/editor/` (Apache KIE DMN editor, fetched from npm by the build). Dockerfile for the generic image, built from the root of the repository. Contract: [openapi.yaml](rule-engine-runtime/openapi.yaml). |
| [rule-engine](rule-engine) | Ivy connector: program element `BusinessRuleTask`, client of the runtime, mapping, Java API `RuleEngine`. Requires `rule-engine-core`. |
| [rule-engine-demo](rule-engine-demo) | The demo: an invoice with five lines priced by `dmn/invoice-pricing.dmn` (line discounts, VAT, order discounts, shipping, approval), a page to change the invoice, and the tax formula to try out. |
| [rule-engine-test](rule-engine-test) | Tests of the connector: with a mocked runtime and against the real container. |
| [rule-engine-product](rule-engine-product) | Market product: README, product.json. |
| [portal](portal), [portal-components](portal-components) | Axon Ivy Portal 14.0.1.2 with the tab **Rule Management** in its Admin Settings. Not part of the Maven build of the rule engine, see [Rule Management in the Portal](#rule-management-in-the-portal). |

## Run it

```
docker compose up -d --build      # decision runtime on http://localhost:8090
```

Import `rule-engine-core`, `rule-engine`, `rule-engine-demo` into the Designer and start `InvoicePricingDemo/start.ivp`:
an invoice with five lines, priced by the rules. Change the invoice and click **Apply rules**.
The variable `RuleEngine.Url` points to `http://localhost:8090` by default.
Change the rules in the browser at `http://localhost:8090/editor/`, or in the Portal, and apply them again.

```
curl -X POST -H "Content-Type: application/xml" --data-binary @rule-engine-demo/dmn/invoice-pricing.dmn \
     http://localhost:8090/api/models/invoice-pricing/versions
curl -X POST -H "Content-Type: application/json" \
     -d '{"decision":"totals","input":{"customerTier":"Gold","country":"DE","paymentTerms":"Prepaid","promoCode":null,
          "invoiceDate":"2026-11-19","expressDelivery":true,
          "lines":[{"product":"Laptop","category":"Hardware","quantity":12,"unitPrice":1200}]}}' \
     http://localhost:8090/api/models/invoice-pricing/evaluate
# {"decision":"totals","model":"invoice-pricing","result":{"gross":14400,"lineDiscounts":1440.00,"subtotal":12960.00,
#  "orderDiscount":518.40,"net":12441.60,"vat":2363.90,"shippingFee":45,"total":14850.50},"results":{...},"version":1}
```

Every input of the decisions it needs must be in `input`, `null` if it has no value.

## Build and test

Java 25 and Maven. The Ivy modules are built with the project-build-plugin, which runs the tests in an Ivy engine.

| Command | What runs |
| --- | --- |
| `mvn verify` | Everything without Docker: tests of the runtime (REST API, version rules, generated DMN checked by the KIE validator and executed in the engine) and tests of the connector with a mocked runtime. |
| `docker compose build` then `mvn verify -Pcontainer.test -pl rule-engine-test -am` | `RuleEngineContainerTest`: the demo invoice and the test processes against the real runtime in a container (Testcontainers), including a rule changed and an input added as in the Portal, and the tax formula tried on a copy of the model. If Testcontainers times out waiting for the container (seen with Rancher Desktop), set `TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1`. |
| `mvn test -Pperformance -f rule-engine-runtime` | Performance baseline, written to `rule-engine-runtime/target/performance-baseline.md`. |
| `mvn quarkus:dev -f rule-engine-runtime` | Runtime in dev mode on port 8090, database in `rule-engine-runtime/data`. |

## How the REST client is built

The client of the connector follows the design of LangChain4j:

- `client.http`: the transport abstraction `HttpClient`, `HttpClientBuilder`, `HttpRequest`,
  `SuccessfulHttpResponse`, `HttpException` and `LoggingHttpClient`. The implementation is found through the
  service provider interface `HttpClientBuilderFactory` (`HttpClientBuilderLoader`).
- `client.http.ivy`: the default transport. It uses the REST client `ruleEngine` of Axon Ivy (Jersey), the same
  way Smart Workflow plugs the Ivy REST client into LangChain4j. Calls appear in the tracing of the engine.
- `client`: `RuleEngineClient` with a builder (`baseUrl`, `apiKey`, timeouts, `logRequests`, custom headers),
  and exceptions for unreachable, timeout and error answers.
- The typed requests and responses are the records of `rule-engine-core` (`com.axonivy.utils.rule.engine.api`):
  the runtime serves exactly these classes, so client and server cannot drift apart.

## Spike result: runtime loading (MARP-4893)

Runtime loading works and is used. The fallback (Kogito code generation with a pipeline template) is not needed.

- DMN files are compiled in memory with `DMNRuntimeBuilder` of `kie-dmn-core`, the same engine Kogito uses.
  No code generation, no KieContainer, no restart. This is the mechanism of the Kogito JIT executor.
- Kogito's Quarkus extension is not used: it compiles models at build time, which is the opposite of what we need.
- A version is an immutable compiled model. Activating another version swaps one reference,
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
| `ruleManagementModels()` | The models of the runtime and the `.dmn` files of the projects the runtime does not know yet: name, onRuntime, activeVersion, latestVersion, versionCount, decisions, project, file. The Portal detects the rule engine by this start. |
| `ruleManagementInfo()` | runtimeUrl, reachable, editorScript (link of the editor script on this engine), modelPrefix |
| `ruleManagementVersions(String)` | The versions of a model, newest first: version, status, source, author, description, createdAt, activatedAt, activatedBy, effectiveDate, previousVersion |
| `ruleManagementContent(String,Integer)` | model, version, dmn; version null opens the active one, a project file is read from its project |
| `ruleManagementValidate(String)` | valid, messages (the findings of the DMN engine) |
| `ruleManagementSave(String,String,String,String,Boolean)` | model, dmn, author, description, activate: a new version with the source *portal*, as draft or live |
| `ruleManagementActivate(String,Integer,String)` | model, version, author |
| `ruleManagementRollback(String,String)` | model, author |

`error` is null on success, else a map with `code` (UNREACHABLE, TIMEOUT, NOT_CONFIGURED, an error code of the
runtime such as INVALID_MODEL, or FAILED), `message` and `details`.

Build the Portal with `mvn install -f portal-components` and `mvn verify -f portal`.

## Decisions taken in this implementation

- **One shared core, two deployables.** `rule-engine-core` is an Axon Ivy project, so Ivy projects require it like
  any other. It is plain Java on purpose: the runtime adds its `src` folder as a source folder and compiles it
  with Java 21, so the Docker build needs no Ivy tooling, and any use of the Ivy API in the core breaks the runtime
  build at once. The OpenAPI annotations on its records document the runtime's contract; in Ivy they are a
  compile-only dependency and are ignored at runtime.

- **Versions are stored in the runtime container**, not in the system database of the Ivy Engine as MARP-4895
  describes. The container holds the engine and an embedded H2 database on the volume `/data`.
  Consequence: the runtime is not stateless, several runtime instances need a shared database
  (the store is JPA, switching the database is a configuration and driver change).
- **Dropdown**: the inscription API of 14.0.1 has no single-select and no button. The decision dropdown is the
  multi-select widget, the first selected entry is used.
- **New DMN...**: without a change event, selecting the entry cannot generate the file at that moment.
  The file is generated when an inscription mask of the project is opened the next time, or when the element
  is executed. The selection switches to the generated decision through the config migrator of the element.
- **Mapping by name only**: inputs and results are always found by name. A value that has to be prepared, or a
  field with another name, is set in the process data before the element, for example in a Script. Input fields
  and Output fields only describe a new DMN.
- **Field picker**: fields are entered as field paths in text fields, until Core delivers a data picker.
- **Model names**: the name of a model on the runtime is the file name of the `.dmn` file.
  `RuleEngine.ModelPrefix` separates applications that share one runtime.
- **Packed projects**: an engine keeps a deployed project as its `.iar`; `Project.directory()` is then that file.
  `ProjectDmnFiles` reads the `.dmn` files from it as a zip (under `META-INF/ivy`), so the Business Rule Task and
  the Portal find them on an engine as in the Designer. Writing a file (New DMN, `RuleEngine.export`)
  needs the project folder of the Designer.
- **Inputs of the version that answers**: the Business Rule Task takes the inputs a decision needs from the `.dmn` file
  of the project. A version activated in the Portal or through the API answers instead of the file, and may need
  more, e.g. a column a rule owner added. So the task also sends the inputs of the active version when it did not
  come from a project, found in the process data by name. What the runtime has active is asked at most every five
  seconds; if another version answers than expected, the task asks again and evaluates once more.
- **Portal editor in the engine, not the runtime**: the Portal tab could embed the page `/editor/` of the
  runtime in a frame. It does not: that page needs the runtime to be reachable from every browser and the API key
  in the browser, and it has its own look and its own list of models. The tab is built from Portal components;
  only the KIE editor itself is embedded, served by the engine.
