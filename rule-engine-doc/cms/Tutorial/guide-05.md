# Create a new DMN from scratch

You do not have to start a decision table from an empty file. Tell the Business Rule Task which fields of your process data the decision reads and which field it fills, and the element generates the `.dmn` file. Its inputs and outputs are named like your fields, so the element finds them by name, as with any decision. You only write the rules.

> **Walkthrough:** the button next to the title plays this page in a simulated Designer: the fields are selected, the new DMN gets its name, the file is generated, and a first rule is added to the generated table.

**Example used on this page: who approves the invoice.** The demo decides the discount of an invoice; now it should also say who has to approve it. Extend the callable `InvoicePricing` of [Use an existing DMN decision] like this:

1. Add a field `approver`, a `String`, to the data class `Invoice`.
2. Add a second **BusinessRuleTask** after the Script **Apply the discount**.
3. Configure it as this page shows: a new DMN named `invoice-approver` from `in.invoice.total` and `in.invoice.discountPercent`, filling `in.invoice.approver`.

The walkthrough plays exactly this extended process.

Your project has a `.dmn` file already? Select its decision, as the page [Use an existing DMN decision] shows.

---

## Select the fields

Open the Business Rule Task, go to the tab **Configuration** and select **New DMN...** in the dropdown **DMN decision**. Then fill in the group **New DMN** below it. Its fields describe the file to build, and they are used for nothing else:

| Field | Value in the example | Meaning |
| --- | --- | --- |
| Input fields | `in.invoice.total, in.invoice.discountPercent` | The fields the decision reads. Each becomes an input named like its field, and a column of the table. |
| Output fields | `in.invoice.approver` | The field the decision fills. It becomes the output column. |

Fields are written as paths, separated by a comma or a line break. A path starts with `in.` and leads to a field of the process data, through nested data classes if needed.

Later the element finds these fields by their names, as with any decision. So it refuses a selection that the search by name would not find again, and the message says why:

- the same name as input and as output, for example `in.invoice.approver` in both lists,
- two input fields, or two output fields, with the same name, such as `in.invoice.total` and `in.order.total`,
- a field that another field of the same name hides, because that one comes first.

---

## Name the new DMN

**New DMN name**, at the top of the group **New DMN**, names the file: `invoice-approver` gives `dmn/invoice-approver.dmn`. The name is required, and it has to be one of its own:

| New DMN name | What happens |
| --- | --- |
| Empty | No file is created. When the process passes the element, it throws `rule:engine:mapping`, and the Runtime Log says that the name is empty. |
| The name of another `.dmn` file, such as `invoice-discount` | That file is not touched and not used. The element throws `rule:engine:mapping` and names the file. |
| A name of its own, such as `invoice-approver` | The file is generated. |

The generated file says in its description which fields it was generated from. That is how the element knows its own file in later runs: it uses it and **never** overwrites it, so your rules are safe. Any other file of that name is an error.

---

## Generate the file

The file is generated at the next of these two moments:

- you open the inscription mask of a Business Rule Task of the project again, or
- the element is executed, for example when you click **Apply rules** on the page of the demo.

The inscription mask has no button yet, which is why selecting the entry does not generate the file at that very moment.

The result is `dmn/invoice-approver.dmn`:

| | total | discountPercent | approver |
| --- | --- | --- | --- |
| 1 | `-` | `-` | `""` |

- One **input** per selected input field, named exactly like the field: `total`, `discountPercent`.
- One **decision** with a decision table: a column per input, a column per output, and the hit policy FIRST.
- One **placeholder rule** that matches everything and returns a neutral value: `0` for a number, `""` for a text, `false` for a boolean. The model is valid and can be evaluated right away.
- A **diagram layout**, so the file opens cleanly in the DMN editor.

With a single output field the decision is named like that field, here `approver`. Its result is therefore written to `in.invoice.approver` by name. With several output fields the decision is named like the model and returns a structure with one entry per field.

Once the file exists, the element refers to it like to any other: the dropdown shows `invoice-approver › approver`, and from then on the mapping by name works for the new file too. An existing file is **never** overwritten, so your rules are safe.

### How types are mapped

| Field type | In the DMN | In the table |
| --- | --- | --- |
| String | string | one column |
| Number, Integer, Double, BigDecimal | number | one column |
| Boolean | boolean | one column |
| Date, DateTime, Time, Duration | date, date and time, time, days and time duration | one column |
| Enum | string with the constants as allowed values, and `null` | one column |
| Data class | structure | one column per simple field, for example `invoice.total` |
| List | collection | one column for the whole list |

Field names that are words of the FEEL language, such as `in` or `function`, get an underscore: `function_`. The element still finds the field `function` by that name.

---

## Fill in the rules

Open `dmn/invoice-approver.dmn` in a DMN editor and add your rules **above** the placeholder rule. With the hit policy FIRST a rule below it would never be reached.

Any DMN editor that edits files works, for example the Apache KIE DMN editor for VS Code: open the file, select the decision, **Edit**, change the table, save. The editor dialog of the connector is the same editor, but it stores a version of the application instead of changing the file, see [Edit rules in the Portal].

| | total | discountPercent | approver |
| --- | --- | --- | --- |
| 1 | `> 20000` | `-` | `"Finance director"` |
| 2 | `>= 5000` | `-` | `"Sales manager"` |
| 3 | `-` | `>= 8` | `"Sales manager"` |
| 4 | `-` | `-` | `""` |

How to read and write the conditions is on [What is DMN?]. An empty approver means that nobody has to approve. The walkthrough adds rule 2 only; the sample invoice comes to 9,016.00, so a sales manager approves it.

Save the file and run the process again. The saved file is live with the next evaluation, as [Change rules without deployment] explains. From here on the decision is an existing one: what it sends, and how a Script prepares its values, is on the page [Use an existing DMN decision].

---

## See also

- [Use an existing DMN decision]
- [Change rules without deployment]
