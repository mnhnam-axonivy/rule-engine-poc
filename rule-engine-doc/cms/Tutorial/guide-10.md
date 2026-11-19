# Several decisions and the Java API

The first pages used one table at a time. Real models have more: decisions that build on each other, functions called once per line, formulas, results with several values or a whole list. And some applications want to manage the rules themselves, from Java. This page covers both.

> **Example used on this page: the invoice pricing of the demo**
>
> The callable `InvoicePricing`, start `price(SalesInvoice)`, with the model `dmn/invoice-pricing.dmn` from the end of [What is DMN?]. The page of the demo passes an invoice of five lines for a Silver customer in Switzerland, dated the 19th of November 2026.

---

## Evaluate a whole model

The model has seven decisions and three functions. A decision reads inputs, the results of other decisions, and calls functions:

| Decision | Kind | Reads | Answers |
| --- | --- | --- | --- |
| `orderDiscounts` | decision table, COLLECT | `customerTier`, `paymentTerms`, `promoCode`, `invoiceDate` | a list of discounts, each `percent` and `reason` |
| `orderDiscountPercent` | literal expression | `orderDiscounts` | their sum, at most 10 |
| `pricedLines` | literal expression | `lines`, `customerTier`, `country`, `orderDiscountPercent`; calls `lineDiscountTable`, `vatRateTable`, `taxFormula` | a list: every line with its discount, net, VAT rate, VAT and total |
| `shipping` | decision table, FIRST | `country`, `expressDelivery`, `sum(pricedLines.net)` | `fee`, `reason` |
| `totals` | literal expression, a context | `pricedLines`, `shipping` | `gross`, `lineDiscounts`, `subtotal`, `orderDiscount`, `net`, `vat`, `shippingFee`, `total` |
| `approvalFlags` | decision table, COLLECT | `max(pricedLines.discountPercent)`, `totals.total`, `customerTier`, `paymentTerms` | a list of reasons, each with the approver it asks for |
| `approval` | literal expression, a context | `approvalFlags` | `route`, the highest approver, and `reasons` |

The Business Rule Task of the demo selects the entry **invoice-pricing › all decisions** in the dropdown **DMN decision**. Then the runtime evaluates every decision of the model, each after the ones it reads, and answers all results at once:

| Result | Value | Written to |
| --- | --- | --- |
| `pricedLines` | five lines; the laptops: 6 %, net 11,280.00, VAT 913.68 | `in.pricing.pricedLines`, a list of `PricedLine` |
| `orderDiscounts` | `[]`: no rule matches | `in.pricing.orderDiscounts`, a list of `Discount` |
| `orderDiscountPercent` | `0` | `in.pricing.orderDiscountPercent` |
| `shipping` | `fee = 0`, `reason = "Free delivery in CH from 500"` | `in.pricing.shipping` |
| `totals` | `net = 16923.00`, `vat = 1342.55`, `total = 18265.55`, … | `in.pricing.totals` |
| `approval` | `route = "Sales manager"`, `reasons = ["A line discount of 15% or more"]` | `in.pricing.approval` |
| `approvalFlags` | the same reasons, with their approvers | not stored: the process data has no field of its name |

The element only needs the seven inputs of the model. It finds them by name in `in.invoice`, as on [Use an existing DMN decision]. The results of `orderDiscounts` or `pricedLines` that other decisions read never leave the runtime as separate calls: the engine passes them on inside.

To evaluate one decision of such a model, select it on its own, for example **invoice-pricing › shipping**. The decisions it reads are evaluated with it.

---

## Results with several values

A decision table with more than one output column answers a **structure**: one entry per column. A literal expression can answer a structure too, written as a FEEL context, or a list. The element writes them like this:

- If the process data has a field with the name of the decision, the result goes there. `shipping` fills `in.pricing.shipping`, a data class with the fields `fee` and `reason`.
- A list of structures fills a list of a data class: `pricedLines` becomes a list of `PricedLine`, one object per line.
- A structure without a field of its own is split up: each entry goes to the field with its own name. A list without a field is not stored.

Dates, times, lists and data classes travel as JSON and are converted to the type of the field, as in the table of [Use an existing DMN decision]. The `invoiceDate` of the invoice is a `LocalDate`; it travels as `"2026-11-19"` and is a FEEL date in the model, which the promotion checks with `[date("2026-11-01")..date("2027-01-31")]`.

---

## The Java API

The class `RuleEngine` of the connector calls the same runtime as the Business Rule Task, with the same variables, the same API key and the same `RuleEngine.ModelPrefix`. Use it in a script, a Java class, or a Rules UI of your own.

| Method | What it does |
| --- | --- |
| `evaluate(model, decision, input)` | Evaluates a decision with the active version. `decision` null: every decision of the model. |
| `versions(model)` | All versions of a model, newest first. |
| `register(model, dmn, author, description)` | Stores a `.dmn` content as a new draft, from the source portal. |
| `approve(model, version)` | Releases a draft. |
| `activate(model, version, user)` | Makes a version live at once. With an `OffsetDateTime` as fourth argument: at that time. |
| `rollback(model, user)` | Makes the version before active again. |
| `retire(model, version)` | Withdraws a version that is not active. |
| `deploy(project)` | Registers the `.dmn` files of a project, as a deployment does. |
| `export(model, version, project)` | Writes a version back into the `dmn` folder of a project. |

Evaluate without a Business Rule Task:

```java
EvaluateResponse response = RuleEngine.evaluate("invoice-pricing", "orderDiscounts",
    Map.of("customerTier", "Gold", "paymentTerms", "Prepaid", "promoCode", "WINTER26", "invoiceDate", "2026-11-19"));
Object discounts = response.results().get("orderDiscounts");   // [{percent=2, …}, {percent=2, …}, {percent=5, …}]
int version = response.version();                              // the version that decided
```

Change a rule the way a rule owner does in the Portal:

```java
ModelVersion draft = RuleEngine.register("invoice-pricing", dmnXml, "rule.editor", "Hardware discount raised");
RuleEngine.approve("invoice-pricing", draft.version());
RuleEngine.activate("invoice-pricing", draft.version(), "rule.approver");

// something is wrong: back to the version that was active before
RuleEngine.rollback("invoice-pricing", "rule.approver");
```

A version activated this way is one of the rule owner's: a project never replaces it, as [Edit rules in the Portal] explains under *Who has the last word*.

The page of the demo uses the client of the connector for its tax formula playground: it loads a copy of the active version with another `taxFormula` under a model name of its own, evaluates the invoice with it and deletes the copy again (`TaxFormula.tryFormula`). The live rules do not change.

---

## Test a draft before it goes live

A draft can be evaluated on purpose, whatever version is active. The REST API of the runtime does it per version:

```bash
curl -X POST -H "Content-Type: application/json" \
     -d '{"decision":"orderDiscounts","input":{"customerTier":"Gold","paymentTerms":"Prepaid","promoCode":null,"invoiceDate":null}}' \
     http://localhost:8090/api/models/invoice-pricing/versions/2/evaluate
```

The whole API, with every request and answer, is described at `http://localhost:8090/q/swagger-ui`.

---

## See also

- [Use an existing DMN decision]
- [Edit rules in the Portal]
- [Run it in production]
