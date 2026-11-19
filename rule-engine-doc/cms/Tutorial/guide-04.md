# Use an existing DMN decision

The **Business Rule Task** is the process element that evaluates a DMN decision with your process data. When your project already has a `.dmn` file, you select one of its decisions, or all of them, and that is the whole configuration: inputs and results find their process data fields by name. What the decision should get, you set in the process data before the element, for example in a Script.

> **Walkthrough:** the button next to the title plays this page in a simulated Designer, one chapter per section: the one field to fill in, how a field is found by its name, what an empty field sends, what happens without a field, and how a Script prepares a value. Afterwards you can change the data, the rules and the element yourself.

**Example used on this page: the invoice pricing of the demo.** The process is the callable sub process `InvoicePricing`, start `price(SalesInvoice)`, the one the page of [Set up and run your first decision] calls with **Apply rules**. Its process data has two fields: `invoice`, a `SalesInvoice` with `customerTier`, `country`, `paymentTerms`, `promoCode`, `expressDelivery`, `invoiceDate` and the list `lines`, and `pricing`, a `PricingResult` for the results. The rules are `dmn/invoice-pricing.dmn` from [What is DMN?].

The process has three steps:

| Element | What it does |
| --- | --- |
| **Prepare the promo code** | A Script that trims the promo code and writes it in capitals. The invoice itself comes from the page; in your own process the values come from a form, a service or a Script. |
| **Business Rule Task invoice-pricing** | Sends the inputs of the model to the runtime and writes every result back to the process data. |
| **Log the result** | Writes the total and the approver to the Runtime Log. |

Your project has no `.dmn` file yet? Start on the page [Create a new DMN from scratch].

---

## Which fields do I have to fill in?

Only **DMN decision**. Open the Business Rule Task and go to the tab **Configuration**:

| Group | Field | What it is for |
| --- | --- | --- |
| Decision | DMN decision | The decision to evaluate. The one field every Business Rule Task needs. |
| New DMN | New DMN name, Input fields, Output fields | Only for a new file, see [Create a new DMN from scratch]. With an existing decision they are not used. |

The dropdown **DMN decision** lists every `.dmn` file of the project and of the projects it requires. The name of a model is the name of its file without `.dmn`. Each entry says what its decision needs and gives, with the types:

| Entry | Meaning |
| --- | --- |
| `invoice-pricing › all decisions` | Every decision of the model at once. The demo selects this one: one call prices the whole invoice. |
| `invoice-pricing › shipping` | Only the decision `shipping`: `out: fee (number), reason (string)`. The decisions it depends on are evaluated with it. |
| `New DMN...` | Generates a new file, see [Create a new DMN from scratch]. |

The demo process is configured exactly like that: only the decision is selected. It runs, and the invoice is priced.

---

## How does the element find a field by name?

In short: for every input of the model, the element looks for a field of the process data with the same name. It starts at `in` and goes down one level at a time. The first field it finds is the one it uses. The model itself never sees the process data; it only gets the values the element sends.

```text
the model "invoice-pricing" needs:   customerTier  country  paymentTerms  promoCode  expressDelivery  invoiceDate  lines

in   (InvoicePricingData)                    level 0: no field with one of the names
 ├─ invoice   (SalesInvoice)
 │   ├─ number
 │   ├─ customer
 │   ├─ customerTier     ◄── customerTier    level 1: found
 │   ├─ country          ◄── country         level 1: found
 │   ├─ paymentTerms     ◄── paymentTerms    …
 │   ├─ promoCode        ◄── promoCode
 │   ├─ expressDelivery  ◄── expressDelivery
 │   ├─ invoiceDate      ◄── invoiceDate
 │   └─ lines            ◄── lines           a list of InvoiceLine: sent as a list of structures
 └─ pricing   (PricingResult)
     ├─ pricedLines      ◄── the result "pricedLines" goes back here
     ├─ orderDiscounts   ◄── "orderDiscounts"
     ├─ shipping, totals, approval  ◄── the results with these names
     └─ errorCode, errorMessage

sent to the model:   { "customerTier": "Silver", "country": "CH", …,
                       "lines": [ { "product": "Laptop 14\"", "category": "Hardware", "quantity": 10, "unitPrice": 1200.00 }, … ] }
```

Step by step:

1. **The names come from the model.** The `.dmn` file of the project says which inputs a decision needs; for all decisions, every input of the model. If a version activated in the Portal needs more inputs, they are looked up the same way.
2. **The search goes level by level.** First the fields of `in` itself. Then the fields one level down, inside each data class of `in`, in the order the data class declares them. Then one more level, and so on, up to 4 levels below `in`. A list is not searched inside; a list field is only found by its own name.
3. **The exact name comes first.** Only if no field anywhere has exactly the name does the element search again, ignoring case, blanks and separators. Then an input `Customer Tier` finds the field `customerTier`.
4. **The first match wins.** A field closer to `in` beats a deeper one: `in.country` wins over `in.invoice.country`.
5. **The value is sent as it is.** A field that is not set, or whose parent object is not set, is sent as `null`.
6. **No match is an error.** If no field has the name, the element throws `rule:engine:mapping` and the model is not evaluated. See *What happens when an input has no field?* below.

The results go back the same way: the result `pricedLines` goes to the field named `pricedLines`, here `in.pricing.pricedLines`, a list of the data class `PricedLine`. A structured result without a field of its own is split up: each of its entries goes to the field with the name of the entry. A result that has no field at all, such as `approvalFlags`, is not stored.

> **Watch out: the same name twice on one level**
>
> With a field `country` in two data classes on the same level, say `in.invoice.country` and `in.customer.country`, the element takes the one whose parent comes first in the data class, here `in.invoice.country`. It does not warn. To send the other one, give it a field of its own closer to `in` and set it in a Script before the element: `in.country = in.customer.country;`.

The values are sent as JSON, with the type the decision expects:

| Process data | DMN | Example |
| --- | --- | --- |
| String | string | `"Silver"` |
| Number, Integer, Double, BigDecimal | number | `1200.00` |
| Boolean | boolean | `false` |
| Date, LocalDate | date | `"2026-11-19"` |
| DateTime | date and time | `"2026-11-19T10:30:00"` |
| Time, Duration | time, days and time duration | `"08:00:00"`, `"PT36H"` |
| Enum | string | `"GOLD"` |
| Data class | structure | `{ "fee": 0, "reason": "…" }` |
| List | collection | `[ { "product": "Laptop 14\"", … }, … ]` |

---

## What is sent when a field is empty?

`null`. The model still gets the input, without a value. What happens then depends on its type:

- A type with **allowed values** takes `null` only if it lists it. The demo model lists `"Standard", "Silver", "Gold", "Platinum"` for `customerTier`, without `null`: an invoice without a tier is refused with `rule:engine:dmn`, *the value 'null' of 'customerTier' is not allowed by the declared type*. The models that New DMN... generates list `null` for every enum.
- Any other type takes `null`. Without a promo code, `null` is sent, and every rule whose condition for the promo code is `-` still matches: the invoice is priced as before.

A condition such as `"WINTER26"` or `>= 50` does not match `null`; only `-` does. If no rule of a table matches at all, its result is `null`, for COLLECT an empty list, and the field of the result is cleared.

---

## What happens when an input has no field?

The element throws `rule:engine:mapping`, and the model is not evaluated. The message names the input:

```text
No process data field found for the input(s) [creditLimit] of the model 'invoice-pricing'.
The process data needs a field named like each input, e.g. in.creditLimit, set before the Business Rule Task.
```

There are two ways out: add a field named like the input to the data class and set it before the element, for example in a Script. Or the rule author names the input like a field the process already has. Catch the error like any other error of the element, see [Handle errors].

---

## How do I prepare a value for the decision?

In a Script before the Business Rule Task. The element reads the fields when the token arrives, so whatever a Script sets there is what the decision gets. The demo does it with the promo code: people type it in any case and with blanks, the rule says `"WINTER26"`.

```java
if (in.invoice.promoCode != null) {
  in.invoice.promoCode = in.invoice.promoCode.trim().toUpperCase();
}
```

A customer who types ` winter26 ` gets the promotion: `"WINTER26"` is sent, and 5 percent come off the invoice.

| Case | In the Script |
| --- | --- |
| A value has to be calculated or cleaned first | `in.invoice.promoCode = in.invoice.promoCode.trim().toUpperCase();` |
| Your field has another name than the input | `in.invoice.customerTier = in.customer.tier;` |
| A result is needed in another field | after the element: `in.order.total = in.pricing.totals.total;` |

---

## Use it in your own process

1. Put your `.dmn` file in the folder `dmn` of your project.
2. In the process editor, open **Extensions › Program Elements** and add **BusinessRuleTask** to your process.
3. Open the element, go to **Configuration** and select the decision, or all decisions of the model.
4. Make sure the process data has fields named like the inputs and results of the decision, and set the inputs before the element, for example in a Script.
5. Attach an Error Boundary Event with the error code `rule:engine`, see [Handle errors].

Your project needs the connector as a dependency in its `pom.xml`:

```xml
<dependency>
  <groupId>com.axonivy.utils.ruleengine</groupId>
  <artifactId>rule-engine</artifactId>
  <version>14.0.1-SNAPSHOT</version>
  <type>iar</type>
</dependency>
```

---

## See also

- [Create a new DMN from scratch]
- [Change rules without deployment]
- [Frequently asked questions]
