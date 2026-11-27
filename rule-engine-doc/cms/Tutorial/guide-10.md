# Several decisions and the Java API

The first pages used one table. Real models often have more: decisions that build on each other, formulas, results with several values. And some applications want to manage the rules themselves, from Java. This page covers both.

> **Example used on this page: the invoice discount of the demo**
>
> The callable `InvoicePricing`, start `price(Invoice)`, with the model `dmn/invoice-discount.dmn` from [What is DMN?]: one decision table, `discount`, from `customerTier` and `orderAmount`.

---

## Evaluate a whole model

A model can hold several decisions, and a decision can read the results of others. Say the model of the demo gets a second decision, `approval`, that reads the discount:

| Decision | Kind | Reads | Answers |
| --- | --- | --- | --- |
| `discount` | decision table, FIRST | `customerTier`, `orderAmount` | `discountPercent`, `discountReason` |
| `approval` | decision table, FIRST | `orderAmount`, `discount.discountPercent` | `approver` |

The dropdown **DMN decision** of the Business Rule Task then has one more entry: **invoice-discount › all decisions**. With it, the runtime evaluates every decision of the model, each after the ones it reads, and answers all results at once:

| Result | Value, for example | Written to |
| --- | --- | --- |
| `discount` | `discountPercent = 2`, `discountReason = "Silver customer"` | `in.invoice.discountPercent`, `in.invoice.discountReason` |
| `approval` | `"Sales manager"` | `in.invoice.approval`, if the invoice has a field of that name |

The element only needs the inputs of the model. It finds them by name in the process data, as on [Use an existing DMN decision]. The result of `discount` that `approval` reads never leaves the runtime as a separate call: the engine passes it on inside.

To evaluate one decision of such a model, select it on its own, for example **invoice-discount › approval**. The decisions it reads are evaluated with it.

A decision does not have to be a table. A **literal expression** is a formula in FEEL, for example `orderAmount * discount.discountPercent / 100`, and a **business knowledge model** is a function that other decisions call, such as one table used for every line of an invoice. The runtime evaluates them all.

---

## Results with several values

A decision table with more than one output column answers a **structure**: one entry per column. The table `discount` of the demo answers `{ "discountPercent": 2, "discountReason": "Silver customer" }`. A literal expression can answer a structure too, written as a FEEL context, or a list. The element writes them like this:

- If the process data has a field with the name of the decision, the result goes there: with a field `discount` of a data class with the fields `discountPercent` and `discountReason`, the whole structure would go to it.
- A structure without a field of its own is split up: each entry goes to the field with its own name. The demo has no field `discount`, so the entries go to `in.invoice.discountPercent` and `in.invoice.discountReason`.
- A list of structures fills a list of a data class, one object per element. A list without a field is not stored.

Dates, times, lists and data classes travel as JSON and are converted to the type of the field, as in the table of [Use an existing DMN decision].

---

## The Java API

The class `RuleEngine` of the connector works on the same versions and calls the same runtime as the Business Rule Task, with the same variables, the same API key and the same `RuleEngine.ModelPrefix`. Use it in a script, a Java class, or a Rules UI of your own.

| Method | What it does |
| --- | --- |
| `evaluate(model, decision, input)` | Evaluates a decision with the active version. `decision` null: every decision of the model. |
| `evaluate(model, version, decision, input)` | The same with a given version, active or not, e.g. a draft. |
| `versions(model)` | All versions of a model, newest first. |
| `register(model, dmn, author, description)` | Stores a `.dmn` content as a new draft, from the source portal. |
| `approve(model, version)` | Releases a draft. |
| `activate(model, version, user)` | Makes a version live at once. With an `OffsetDateTime` as fourth argument: at that time. |
| `rollback(model, user)` | Makes the version before active again. |
| `retire(model, version)` | Withdraws a version that is not active. |
| `deploy(project)` | Stores the `.dmn` files of a project as versions, as a deployment does. |
| `export(model, version, project)` | Writes a version back into the `dmn` folder of a project. |

Evaluate without a Business Rule Task:

```java
EvaluateResponse response = RuleEngine.evaluate("invoice-discount", "discount",
    Map.of("customerTier", "Gold", "orderAmount", 12000));
Object discount = response.results().get("discount");   // {discountPercent=8, discountReason=Gold, order of 10,000 or more}
int version = response.version();                       // the version that decided
```

Change a rule the way a rule owner does in the Portal:

```java
ModelVersion draft = RuleEngine.register("invoice-discount", dmnXml, "rule.editor", "Silver customers get 3%");
RuleEngine.approve("invoice-discount", draft.version());
RuleEngine.activate("invoice-discount", draft.version(), "rule.approver");

// something is wrong: back to the version that was active before
RuleEngine.rollback("invoice-discount", "rule.approver");
```

A version activated this way is one of the rule owner's: a project never replaces it, as [Edit rules in the Portal] explains under *Who has the last word*.

The page of the demo shows the table that decided: it reads the version that the case names, `VersionStore.current().content("invoice-discount", version)`, and shows the rules of its table (`DiscountTable`). After a change in the Portal the page shows the new rules at the next Apply.

---

## Test a draft before it goes live

A draft can be evaluated on purpose, whatever version is active:

```java
EvaluateResponse response = RuleEngine.evaluate("invoice-discount", 2, "discount",
    Map.of("customerTier", "Silver", "orderAmount", 9200));
```

The connector loads version 2 into the runtime first, if the runtime does not hold it yet. The API of the runtime, with every request and answer, is described at `http://localhost:8090/q/swagger-ui`.

---

## See also

- [Use an existing DMN decision]
- [Edit rules in the Portal]
- [Run it in production]
