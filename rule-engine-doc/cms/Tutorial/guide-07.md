# Edit rules in the Portal

The people who own a rule should be able to change it without a Designer and without a deployment. With the Axon Ivy Portal they do it in the browser: **Admin Settings › Rule Management** lists the rules, opens them in the DMN editor and saves every change as a new version on the decision runtime.

This page follows one change from the business to the invoice. The rules of the demo do not know who the customer is. A key account signs a framework contract, and the rule owner adds the customer as an input and a discount for it in the Portal. The same process prices the same invoice lower.

> **Walkthrough:** the button next to the title plays this page in a simulated Designer and Portal: the invoice gets no order discount, the rule owner adds the key account in the Portal, the same invoice costs less, and a changed file of the project does not take the rule owner's place.

---

## What you need

| | |
| --- | --- |
| The decision runtime | Running, as on [Set up and run your first decision]. |
| The Axon Ivy Portal | In the same security context as your project, in a version with Rule Management. In the Designer: import the projects `portal-components` and `portal` next to the rule engine projects. |
| A Portal administrator | A user with the role `AXONIVY_PORTAL_ADMIN`. In the Designer the Portal brings the user `admin`, password `admin`. |

The Portal does not depend on the rule engine. It shows the tab only if it finds the rule engine in the security context.

---

## Before: no rule knows the customer

Start **Rule Engine Demo: invoice pricing** (`InvoicePricingDemo/start.ivp`). The invoice of the page is for the customer **Bluebird Architects**, Silver, paying in 30 days, without a promo code.

The order discounts come from the table `orderDiscounts`. Its hit policy is **COLLECT**: every rule that matches counts, and the discounts add up.

| # | customerTier | paymentTerms | promoCode | invoiceDate | percent | reason |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `"Gold"` | `-` | `-` | `-` | 2 | Gold customer bonus |
| 2 | `"Platinum"` | `-` | `-` | `-` | 4 | Platinum customer bonus |
| 3 | `-` | `"Prepaid"` | `-` | `-` | 2 | Early payment: prepaid |
| 4 | `-` | `-` | `"WINTER26"` | `[date("2026-11-01")..date("2027-01-31")]` | 5 | Promotion WINTER26 |
| 5 | `-` | `-` | `"WINTER26"` | `not([date("2026-11-01")..date("2027-01-31")])` | 0 | WINTER26 is only valid from 1 Nov 2026 to 31 Jan 2027 |
| 6 | `-` | `-` | `not(null, "", "WINTER26")` | `-` | 0 | Unknown promo code |

None of them matches the invoice: no order discount, total CHF 18,265.55. The invoice also has the field `customer`, but no rule reads it yet.

---

## Rule Management in the Portal

1. Open the Portal and log in as Portal administrator.
2. Open **Admin settings** from the user menu and select the tab **Rule Management**.

The table lists the models of the decision runtime with their active version, and the `.dmn` files of the projects that the runtime does not know yet. `invoice-pricing` is at version 1, from the project: the first Apply registered it.

| Action | What it does |
| --- | --- |
| **Edit** | Opens the active version in the DMN editor of Apache KIE, inside the Portal. A file that is not on the runtime yet is opened from its project. |
| **Show versions** | Lists every version with status, source, author and what changed. Open, activate, roll back. |
| **Refresh** | Reads the runtime again. |

The DMN editor is the one of the Designer and of VS Code. The engine fetches it from the runtime, so the browser of the rule owner never talks to the runtime, and the API key stays on the server.

---

## Add an input and a rule

What the business wants: Bluebird Architects signed a framework contract and gets 3 percent off every invoice.

**One more input.** In the diagram of the editor, add an **Input Data** node `customer` of type `string` and connect it to the decision `orderDiscounts`. Select the decision, click **Edit**, and add a column for the input.

The process data already has a field with this name, `in.invoice.customer`. The Business Rule Task finds it by name, so the process does not change.

**One more rule**, at the end of the table:

| # | customerTier | paymentTerms | promoCode | invoiceDate | customer | percent | reason |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 to 6 | … | … | … | … | `-` | … | … |
| 7 | `-` | `-` | `-` | `-` | `"Bluebird Architects"` | 3 | Key account |

The existing rules get `-` for the new column, so they decide as before. With COLLECT the place of the new rule does not matter: it adds to the others. A Gold key account who pays in advance gets 2 + 2 + 3 percent; the decision `orderDiscountPercent` caps the sum at 10.

Then:

1. **Check** lets the DMN engine check the model. Nothing is stored.
2. Fill in **What changed?**, e.g. "Key account discount for Bluebird Architects".
3. **Save and activate**. The runtime checks the model again and refuses it with the findings of the DMN engine if a rule is not valid.

The result is version 2, from the source **portal**, with the Portal user as author. It is live on the runtime at once. **Save as draft** stores it without making it live.

---

## After: the same invoice costs less

Click **Apply rules** on the page again, without changing anything:

| | Before | After |
| --- | --- | --- |
| Order discounts | none | Key account 3 % |
| Net | 16,923.00 | 16,415.31 |
| VAT | 1,342.55 | 1,302.27 |
| Total | 18,265.55 | 17,717.58 |
| Decided by | version 1 | version 2 |

Nothing was changed in the project, and nothing was deployed. What happened:

- The runtime answers with version 2, the one from the Portal.
- The Business Rule Task sends the inputs that this version needs: the seven of the file, and `customer`, which it finds in the process data by name.
- Each line gets its share of the 3 percent, and the VAT is computed on the lower amounts.

The `.dmn` file of the project still has the table without the customer. A version that was activated in the Portal is **never** replaced by the project: a later deployment stores the file as a draft beside it. To bring the version of the rule owner back into the project, export it with `RuleEngine.export("invoice-pricing", 2, project)`.

---

## Versions and roll back

**Show versions** lists every version of `invoice-pricing`:

| Version | Status | Source | Author | What changed |
| --- | --- | --- | --- | --- |
| 2 | active | portal | admin | Key account discount for Bluebird Architects |
| 1 | retired | project | rule-engine-demo | |

**Roll back** makes version 1 active again, at once. The next Apply prices the invoice without the key account discount. Any version can also be opened in the editor or activated on purpose.

---

## Who has the last word

Two places can change a rule: the project and the runtime. The rule that decides between them is simple.

> **The Ivy project provides the initial version. The runtime holds the live versions.**

- A version from the project goes live if no version is active yet, or if the active version also came from a project. This is what makes a saved file live during development, as on [Change rules without deployment].
- A version that was activated in the Portal or through the API is **never** replaced by a project. When the file of the project changes, or the project is deployed again, the file is stored as a draft beside it, and the log says so. The rule owner keeps the last word.
- To make the file of the project live anyway, activate its version on purpose, in **Show versions**.
- To bring the live rules back into the project, export the version: `RuleEngine.export("invoice-pricing", 2, project)` writes it to the `dmn` folder.

In the demo: with version 2 of the Portal active, a developer raises "Hardware: 10 units or more" in `dmn/invoice-pricing.dmn` from 6 to 8 percent. The next Apply registers the file as version 3, a draft, and version 2 still decides: the laptops get 6 percent, and the key account discount stays.

Set the variable `RuleEngine.AutoDeploy` to `false` if a project should never send its files, and models are loaded through the Portal or the API only.

---

## Without the Portal

The decision runtime has an editor page of its own, for rule owners without a Portal and for development: open `http://localhost:8090/editor/`.

1. Enter your name at the top right. It is stored with every version.
2. Select the model on the left, for example `invoice-pricing`. Its active version opens in the DMN editor of Apache KIE.
3. Select a decision or a function in the diagram, for example `lineDiscountTable`, and click **Edit** below it: the decision table opens.
4. Click a cell, for example the result of rule 6, type the new value and press Enter.
5. Click **Save and activate**. The runtime checks the model first and refuses it if a rule is not valid.

The list on the left now shows a new version from the source **portal**, active. **Activate** and **Roll back** in the same list do what their names say. **Save as draft** stores a version without making it live.

The editor also opens a file: **Open .dmn file…** loads the `.dmn` file of a project, and **Save file** writes it back (Chrome and Edge; other browsers download the changed file). That is the way to edit the rules of the project with a table instead of XML, and the change goes live through the project, like any saved file.

---

## What happens underneath

```text
 Browser ──► Portal (Admin Settings) ──► rule-engine: Portal/RuleManagement ──► decision runtime, REST API
                                         (callable sub process)                  (with the API key)
```

- The Portal finds the callable sub process `Portal/RuleManagement` of the connector by its signatures in the security context, the way it finds Smart Workflow. Without it, there is no tab and nothing else changes.
- Every call answers with JDK types only: the Portal needs no class of the rule engine.
- Only Portal administrators see the tab. The Portal checks the role again for every action.

---

## Good to know

- An input that the Portal version adds must exist in the process data: a field of the same name, set before the Business Rule Task. Otherwise the element throws `rule:engine:mapping`, as described in [Handle errors].
- The table shows the models of this application. Applications that share one runtime are separated by `RuleEngine.ModelPrefix`.

---

## See also

- [Change rules without deployment]
- [Handle errors]
- [Several decisions and the Java API]: the same actions from Java, for a Rules UI of your own.
