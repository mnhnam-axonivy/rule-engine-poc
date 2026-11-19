# Axon Ivy Rules Engine

Use business rules in your processes without writing code. Rules are modelled as
[DMN](https://www.omg.org/dmn/) decision tables, live as `.dmn` files in your Ivy project and are evaluated
by a decision runtime that runs in a container next to the Axon Ivy Engine.

The Axon Ivy Rules Engine is based on Kogito and Drools from the open source project Apache KIE.

- **Business Rule Task**: a process element that evaluates a decision with your process data.
  Inputs and results are mapped by name, there is nothing to map by hand.
- **Rules change without deployment**: a new version of a model is live on the runtime within seconds.
  Versions can be activated at an effective date and rolled back.
- **Start from your process data**: generate a ready-to-edit `.dmn` file from selected fields
  and only fill in the rules.
- **One generic container image**: models are loaded at runtime, you never build an image.

## Demo

Start `InvoicePricingDemo/start.ivp`: a sales invoice with five lines, priced by the rules of
`dmn/invoice-pricing.dmn`. Change the invoice, click **Apply rules**, and see what every rule decided.

| Part | What it shows |
| --- | --- |
| Dialog `InvoicePricingDialog` | The header of the invoice (customer tier, country, payment terms, promo code, express delivery, date) and its five lines. **Apply rules** prices it: a discount with its reason and the VAT for every line, the order discounts, shipping, the totals, who approves the invoice and why, and the version of the model that decided. |
| Callable `InvoicePricing`, `price(SalesInvoice)` | The Business Rule Task evaluates all decisions of the model at once. Its inputs are read by name from `in.invoice`, the list `lines` included; every result goes to the field of its name in `in.pricing`, the list `pricedLines` included. An error comes back in `errorCode` and `errorMessage`. |
| `dmn/invoice-pricing.dmn` | Decision tables with the hit policies FIRST, UNIQUE and COLLECT, ranges, lists and dates in conditions, functions (business knowledge models) called once per line, FEEL formulas and decisions that build on each other. |
| Tax formula | The page shows `taxFormula(net, rate)` of the version that decided, with the values of every line. A developer types another FEEL formula and tries it: it runs on a copy of the model that is deleted right after, the live rules do not change. |

Try this on the page:

1. Country **DE** instead of CH: VAT of 19 % and 7 %, no discount on books (fixed book price), and the total is above
   20,000: the Finance director approves.
2. **50** laptops instead of 10: the volume discount of 12 %.
3. Tier **Platinum**, **Prepaid** and the promo code **WINTER26**: the order discounts add up to 11 % and are capped at 10 %.
4. In the Portal (Rule Management) or the DMN editor, raise "Hardware: 10 units or more" from 6 to 8 %, save and activate,
   then **Apply rules** again: the same invoice gets another price, without a deployment. Roll back and it is as before.
5. Try the tax formula `round half up(net * rate / 100 * 20, 0) / 20`, Swiss rounding to 5 centimes.

Edit the `.dmn` files in the browser with the DMN editor of the decision runtime, `http://localhost:8090/editor/`
(see [Edit rules in the browser](#edit-rules-in-the-browser)), or in VS Code with the
[Apache KIE DMN editor for VS Code](https://marketplace.visualstudio.com/items?itemName=kie-group.vscode-extension-kogito-bundle).

## Setup

### 1. Start the decision runtime

The runtime is a container that holds the rule engine and its database. All versions of all models are
stored in the volume and are loaded again after a restart.

```yaml
@docker-compose.yml@
```

```
docker compose up -d
```

Check it: `http://localhost:8090/q/health/ready`. The API is described at `http://localhost:8090/q/swagger-ui`,
the DMN editor is at `http://localhost:8090/editor/`.

### 2. Configure the connector

Set the URL of the runtime in the variables of your application. For production set an API key on both sides:
`RULE_ENGINE_API_KEY` of the container and `RuleEngine.ApiKey` of the engine.

```yaml
@variables.yaml@
```

TLS: terminate it at a reverse proxy in front of the runtime, or give the runtime a certificate
(see the comments in the Docker Compose file). Trust settings of the engine side belong to the REST client
`ruleEngine` in `rest-clients.yaml`.

### 3. Use the Business Rule Task

1. Put your `.dmn` file in the folder `dmn` of your project.
2. Add the element **BusinessRuleTask** from *Extensions › Program Elements* to your process.
3. Select the decision in the dropdown **DMN decision**. It lists every `.dmn` file of the project and its decisions.

That is all: the process data needs fields that are named like the inputs and results of the decision.
Set the inputs before the element, for example in a Script; a value that has to be calculated, or a field
with another name, is prepared there too (`in.invoice.promoCode = in.invoice.promoCode.trim().toUpperCase();`).

| Group | Field | Meaning |
| --- | --- | --- |
| Decision | DMN decision | The decision to evaluate: `model › decision`, `model › all decisions`, or *New DMN...*. Each entry lists the inputs and results of its decision, with their types. |
| New DMN | New DMN name | Only for *New DMN...*: the file name, required. The name of another `.dmn` file is an error. |
| New DMN | Input fields | Only for *New DMN...*: the fields the new decision reads, like `in.invoice.country, in.invoice.expressDelivery`. Each becomes an input named like its field. |
| New DMN | Output fields | Only for *New DMN...*: the fields the new decision fills, like `in.deliveryDays`. Each becomes an output named like its field. |

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
`ivy.case.customFields().stringField("ruleVersion_invoice-pricing").getOrNull()`.

**Errors.** Catch them with an Error Boundary Event or an Error Start Event. The error code `rule:engine`
catches all of them.

| BPMN error | Raised when |
| --- | --- |
| `rule:engine:unreachable` | The runtime cannot be reached. |
| `rule:engine:timeout` | The runtime did not answer within `RuleEngine.TimeoutSeconds`. |
| `rule:engine:dmn` | The runtime rejected the call: unknown model or decision, invalid DMN file, failed evaluation, wrong API key. The error attribute `ruleEngineCode` holds the code of the runtime. |
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

| What you edit | How | Where the change goes |
| --- | --- | --- |
| A model on the runtime | Select it on the left. Select the decision in the diagram and click **Edit** to change its table. **Save and activate**, or **Save as draft**. | A new version from the source *portal*, with your name and a description. A project deployment never replaces it. |
| The `.dmn` file of a project | **Open .dmn file…**, change it, **Save file**. | The file itself (Chrome and Edge write it back, other browsers download it). The Business Rule Task registers the changed file with its next evaluation. |

The list of versions activates any version and rolls back. Before a version is stored the runtime checks the
model; a model with errors is refused with the findings of the DMN engine. If the runtime has an API key,
the page asks for it once per browser tab.

## Rule Management in the Axon Ivy Portal

If the Axon Ivy Portal runs in the same security context as the rule engine, its **Admin Settings** have a tab
**Rule Management** for Portal administrators (role `AXONIVY_PORTAL_ADMIN`). Without the rule engine the tab is
not there and the Portal works as before.

- The table lists the models of the decision runtime with their active version, and the `.dmn` files of the
  projects that the runtime does not know yet.
- **Edit** opens the active version in the DMN editor of Apache KIE, inside the Portal. **Check** lets the DMN
  engine check the model, **Save as draft** and **Save and activate** store a new version with the source
  *portal* and the Portal user as author. A project deployment never replaces it.
- **Show versions** lists all versions: open any of them in the editor, activate it, or roll back.

A rule owner may add inputs to a model in the Portal. The Business Rule Task sends what the active version needs:
an input is read from the process data field of the same name. The process does not change.

The browser talks only to the Axon Ivy Engine: the engine loads the editor from the runtime (`/editor/`) and
makes every call with the API key. So the runtime does not have to be reachable from the browsers of the users.

## Rule versions

Every change of a model is a new version on the runtime. Exactly one version of a model is active.

| Status | Meaning |
| --- | --- |
| draft | Stored, not released. Can be evaluated explicitly for a test run. |
| approved | Released. With an effective date it becomes active at that time. |
| active | Answers the evaluations of the model. |
| retired | Was replaced or withdrawn. Stays available for a rollback. |

**Source of truth.** The Ivy project provides the initial version, the runtime holds the live versions.

- When a project starts and whenever a `.dmn` file has changed, the file is registered as a version with
  the source *project*. Content that is already stored does not create another version.
- A version from the project becomes active if no version is active yet, or if the active version also
  came from a project. So during development a saved file is live with the next evaluation.
- A version that was activated from the Portal or through the API is never replaced by a deployment.
  The version of the project is stored as draft and can be activated on purpose.
- To bring the live rules back into the project, export the version (`RuleEngine.export`).

Set `RuleEngine.AutoDeploy` to `false` if models should only be loaded through the API.

**Java API for a Rules UI.**

```java
ModelVersion draft = RuleEngine.register("invoice-pricing", dmnXml, "rule.editor", "Hardware discount raised");
RuleEngine.approve("invoice-pricing", draft.version());
RuleEngine.activate("invoice-pricing", draft.version(), "rule.approver");            // live at once
RuleEngine.activate("invoice-pricing", draft.version(), "rule.approver", effective);  // live at a date
RuleEngine.rollback("invoice-pricing", "rule.approver");                              // back to the previous version
List<ModelVersion> versions = RuleEngine.versions("invoice-pricing");
```

## Migrate decision tables from XLS

1. Convert the XLS decision table into a `.dmn` file: one column per condition and action,
   one rule per row. An AI assistant does this well; review the result in the DMN editor.
2. Run the same test cases against the old XLS rules and the new model and compare the results.
   A draft can be evaluated on the runtime before it is active: `POST /api/models/{model}/versions/{version}/evaluate`.
3. Only identical results count as migrated.
