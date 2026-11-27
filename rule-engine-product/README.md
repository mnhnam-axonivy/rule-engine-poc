# Axon Ivy Rules Engine

Use business rules in your processes without writing code. Rules are modelled as
[DMN](https://www.omg.org/dmn/) decision tables, live as `.dmn` files in your Ivy project and are evaluated
by a decision runtime that runs in a container next to the Axon Ivy Engine.

The Axon Ivy Rules Engine is based on Kogito and Drools from the open source project Apache KIE.

- **Business Rule Task**: a process element that evaluates a decision with your process data.
  Inputs and results are mapped by name, there is nothing to map by hand.
- **Rules change without deployment**: a new version of a model is live with the next evaluation.
  Versions can be activated at an effective date and rolled back. They are files of your Ivy application.
- **Start from your process data**: generate a ready-to-edit `.dmn` file from selected fields
  and only fill in the rules.
- **One generic container image**: models are loaded at runtime, you never build an image, and the container
  keeps no data.

## Demo

Start `InvoicePricingDemo/start.ivp`: an invoice with five lines. Its discount is decided by one decision table,
`dmn/invoice-discount.dmn`, from the customer tier and the order amount. Change the invoice, click **Apply rules**,
and see which rule decided.

| Part | What it shows |
| --- | --- |
| Dialog `InvoicePricingDialog` | The customer, the customer tier and five lines. **Apply rules** shows the order amount, the discount with its reason and the total, and the table of the version that decided, with the rule that matched marked. |
| Callable `InvoicePricing`, `price(Invoice)` | A Script sums the lines into `orderAmount`. The Business Rule Task reads `customerTier` and `orderAmount` by name from `in.invoice` and writes `discountPercent` and `discountReason` back by name. A Script applies the discount. An error comes back in `errorCode` and `errorMessage`. |
| `dmn/invoice-discount.dmn` | One decision table, hit policy FIRST: seven rules from Platinum to Standard, for orders below and from 10,000. |

Try this on the page:

1. Tier **Gold** instead of Silver: 5 % instead of 2 %.
2. **5** laptops instead of 4: the order is 10,400, and the rule for orders of 10,000 or more decides.
3. In the Portal (Rule Management), change the discount of "Silver customer" from 2 to 3 %, save and
   activate, then **Apply rules** again: the page shows the new version and its table, without a deployment. Roll back
   and it is as before.

Edit the `.dmn` files in the browser with the DMN editor of the decision runtime, `http://localhost:8090/editor/`
(see [Edit rules in the browser](#edit-rules-in-the-browser)), or in VS Code with the
[Apache KIE DMN editor for VS Code](https://marketplace.visualstudio.com/items?itemName=kie-group.vscode-extension-kogito-bundle).

## Setup

### 1. Start the decision runtime

The runtime is a container that holds the rule engine. It keeps no data: the versions of the models are files of
your Ivy application, which loads them into the runtime when it needs them, also after a restart.

```bash
docker run -d --name axonivy-rule-engine -p 8090:8080 \
       --restart unless-stopped -e RULE_ENGINE_API_KEY=<secret> axonivy/rule-engine-runtime:dev
```

| Part | What it does |
| --- | --- |
| `-p 8090:8080` | The runtime on port 8090 of the host. |
| `-e RULE_ENGINE_API_KEY=<secret>` | Protects the API. Leave it out for local development only: without a key the API is open. |

`docker stop axonivy-rule-engine` stops it, `docker start axonivy-rule-engine` starts it again. For a new image,
remove the container with `docker rm -f axonivy-rule-engine` and run it again: nothing is lost.

Check it: `http://localhost:8090/q/health/ready`. The API is described at `http://localhost:8090/q/swagger-ui`,
the DMN editor is at `http://localhost:8090/editor/`.

### 2. Configure the connector

Set the URL of the runtime in the variables of your application. For production set an API key on both sides:
`RULE_ENGINE_API_KEY` of the container and `RuleEngine.ApiKey` of the engine.

```yaml
@variables.yaml@
```

TLS: terminate it at a reverse proxy in front of the runtime, or give the runtime a certificate. The runtime then
serves HTTPS on port 8443 and no plain HTTP:

```bash
docker run -d --name axonivy-rule-engine -p 8443:8443 -v "$PWD/certs:/certs:ro" \
       --restart unless-stopped -e RULE_ENGINE_API_KEY=<secret> \
       -e QUARKUS_HTTP_SSL_CERTIFICATE_FILES=/certs/tls.crt \
       -e QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES=/certs/tls.key \
       -e QUARKUS_HTTP_INSECURE_REQUESTS=disabled \
       axonivy/rule-engine-runtime:dev
```

Trust settings of the engine side belong to the REST client `ruleEngine` in `rest-clients.yaml`.

### 3. Use the Business Rule Task

1. Put your `.dmn` file in the folder `dmn` of your project.
2. Add the element **BusinessRuleTask** from *Extensions › Program Elements* to your process.
3. Select the decision in the dropdown **DMN decision**. It lists every `.dmn` file of the project and its decisions.

That is all: the process data needs fields that are named like the inputs and results of the decision.
Set the inputs before the element, for example in a Script; a value that has to be calculated, or a field
with another name, is prepared there too (the demo sums the lines into `in.invoice.orderAmount`).

| Group | Field | Meaning |
| --- | --- | --- |
| Decision | DMN decision | The decision to evaluate: `model › decision`, `model › all decisions`, or *New DMN...*. Each entry lists the inputs and results of its decision, with their types. |
| New DMN | New DMN name | Only for *New DMN...*: the file name, required. The name of another `.dmn` file is an error. |
| New DMN | Input fields | Only for *New DMN...*: the fields the new decision reads, like `in.invoice.total, in.invoice.discountPercent`. Each becomes an input named like its field. |
| New DMN | Output fields | Only for *New DMN...*: the fields the new decision fills, like `in.invoice.approver`. Each becomes an output named like its field. |

**Mapping by name.** An input `customerTier` is read from the field `customerTier` of the process data.
Fields of nested data classes are found too (`in.invoice.customerTier`); the field closest to the root wins.
A result is written to the field with the name of the decision. A structured result fills a data class,
or, if there is no field for the decision, each entry goes to the field with its name.
Names of hand-written models may differ in case and blanks: `Risk Level` finds `riskLevel`.

**Types.** string, number, boolean, date, date and time, time, duration, enum (as string),
nested data class (as structure) and list (as collection), in both directions.
A field that is not set is sent as `null`.

**Traceability.** The Business Rule Task always evaluates the active version of the model. The version that
decided is stored on the case as custom field `ruleVersion_<model>`; read it in a script with
`ivy.case.customFields().stringField("ruleVersion_invoice-discount").getOrNull()`.

**Errors.** Catch them with an Error Boundary Event or an Error Start Event. The error code `rule:engine`
catches all of them.

| BPMN error | Raised when |
| --- | --- |
| `rule:engine:unreachable` | The runtime cannot be reached. |
| `rule:engine:timeout` | The runtime did not answer within `RuleEngine.TimeoutSeconds`. |
| `rule:engine:dmn` | Unknown model, no active version, unknown decision, invalid DMN file, failed evaluation, wrong API key. The error attribute `ruleEngineCode` holds the code, e.g. `NO_ACTIVE_VERSION`. |
| `rule:engine:mapping` | No decision selected, an input has no field, or a result does not fit its field. |

### 4. Create a DMN from your process data

1. Select **New DMN...** in the dropdown **DMN decision**.
2. In the group **New DMN**, fill in **New DMN name** (required, and not the name of another `.dmn` file), and
   **Input fields** and **Output fields** with field paths of your process data. An empty or a taken name throws
   `rule:engine:mapping` when the process passes the element, and the Runtime Log says why. The element refuses a selection that the mapping by name would not find again: the same
   name as input and output, two input or two output fields with the same name, or a field that another one of
   its name hides.
3. Open the inscription mask of the element again: the file `dmn/<name>.dmn` is generated and selected.
   Without that step the file is generated when the element is executed for the first time.
4. Open the file in the DMN editor and write your rules above the placeholder rule.

The generated model has one input node per field, named like the field, and one decision table with a
column per input and output. Enums become strings with allowed values, nested data classes become
structures, lists become collections. A file that exists is never overwritten.

## Edit rules in the browser

The decision runtime serves a DMN editor at `/editor/`, e.g. `http://localhost:8090/editor/`. It is the DMN
editor of Apache KIE, the one of the KIE extension for VS Code, and needs nothing but a browser. The inscription
mask of the Business Rule Task shows its address, taken from `RuleEngine.Url`.

Open the `.dmn` file of a project with **Open .dmn file…**, select the decision in the diagram and click **Edit**
to change its table. **Check** lets the DMN engine of the runtime look at it, **Save file** writes it back (Chrome and
Edge write the file, other browsers download it). The Business Rule Task stores the changed file as a version with
its next evaluation. If the runtime has an API key, the page asks for it once per browser tab.

The page edits files only. The versions are kept by the Ivy application: change, activate or roll them back in the
Portal, see below.

## Rule Management in the Axon Ivy Portal

If the Axon Ivy Portal runs in the same security context as the rule engine, its **Admin Settings** have a tab
**Rule Management** for Portal administrators (role `AXONIVY_PORTAL_ADMIN`). Without the rule engine the tab is
not there and the Portal works as before.

- The table lists the stored models with their active version, and the `.dmn` files of the projects that are not
  stored yet.
- **Edit** opens the active version in the DMN editor of Apache KIE, inside the Portal. **Check** lets the DMN
  engine check the model, **Save as draft** and **Save and activate** store a new version with the source
  *portal* and the Portal user as author. A project deployment never replaces it.
- **Show versions** lists all versions: open any of them in the editor, activate it, or roll back.

A rule owner may add inputs to a model in the Portal. The Business Rule Task sends what the active version needs:
an input is read from the process data field of the same name. The process does not change.

The browser talks only to the Axon Ivy Engine: the engine loads the editor from the runtime (`/editor/`) and
makes every call with the API key. So the runtime does not have to be reachable from the browsers of the users.

## Rule versions

Every change of a model is a new version. At most one version of a model is active.

The versions are files of the application, in its file area under the data directory of the engine: a folder
`rule-engine/<model>` with the `.dmn` file of every version and `versions.json`, which describes them. Back them up
with the data of the engine. In a cluster, the data directory has to be on shared storage.

| Status | Meaning |
| --- | --- |
| draft | Stored, not released. Can be evaluated explicitly for a test run. |
| approved | Released. With an effective date it becomes active at that time. |
| active | Answers the evaluations of the model. |
| retired | Was replaced or withdrawn. Stays available for a rollback. |

**Source of truth.** The Ivy project provides the initial version, the application holds the live versions.

- When a project starts and whenever a `.dmn` file has changed, the runtime checks the file and it is stored as a
  version with the source *project*. Content that is already stored does not create another version.
- A version from the project becomes active if no version is active yet, or if the active version also
  came from a project. So during development a saved file is live with the next evaluation.
- A version that was activated from the Portal or through the API is never replaced by a deployment.
  The version of the project is stored as draft and can be activated on purpose.
- To bring the live rules back into the project, export the version (`RuleEngine.export`).

Set `RuleEngine.AutoDeploy` to `false` if versions should only come from the Portal or the Java API.

**Java API for a Rules UI.**

```java
ModelVersion draft = RuleEngine.register("invoice-discount", dmnXml, "rule.editor", "Silver customers get 3%");
RuleEngine.approve("invoice-discount", draft.version());
RuleEngine.activate("invoice-discount", draft.version(), "rule.approver");            // live at once
RuleEngine.activate("invoice-discount", draft.version(), "rule.approver", effective);  // live at a date
RuleEngine.rollback("invoice-discount", "rule.approver");                              // back to the previous version
List<ModelVersion> versions = RuleEngine.versions("invoice-discount");
```

## Migrate decision tables from XLS

1. Convert the XLS decision table into a `.dmn` file: one column per condition and action,
   one rule per row. An AI assistant does this well; review the result in the DMN editor.
2. Run the same test cases against the old XLS rules and the new model and compare the results.
   A draft can be evaluated before it is active: `RuleEngine.evaluate(model, version, decision, input)`.
3. Only identical results count as migrated.
