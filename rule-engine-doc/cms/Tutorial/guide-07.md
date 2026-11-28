# Edit rules in the Portal

The people who own a rule should be able to change it without a Designer and without a deployment. With the Axon Ivy Portal they do it in the browser: **Admin Settings › Rule Management** lists the rules, opens them in the DMN editor and saves every change as a new version of the application.

This page follows one change from the business to the invoice. The rules of the demo do not know who the customer is. A key account signs a framework contract, and the rule owner adds the customer as an input and a discount for it in the Portal. The same process gives the same invoice a higher discount.

> **Walkthrough:** the button next to the title plays this page in a simulated Designer and Portal: the invoice gets the discount of its tier, the rule owner adds the key account in the Portal, the same invoice costs less, and a changed file of the project does not take the rule owner's place.

---

## What you need

| | |
| --- | --- |
| The DMN engine | Running, as on [Set up and run your first decision]. |
| The Axon Ivy Portal | In the same security context as your project, in a version with Rule Management. In the Designer: import the projects `portal-components` and `portal` next to the rule engine projects. |
| A Portal administrator | A user with the role `AXONIVY_PORTAL_ADMIN`. In the Designer the Portal brings the user `admin`, password `admin`. |

The Portal does not depend on the rule engine. It shows the tab only if it finds the rule engine in the security context.

---

## Before: no rule knows the customer

Start **Rule Engine Demo: invoice discount** (`InvoicePricingDemo/start.ivp`). The invoice of the page is for the customer **Bluebird Architects**, tier Silver, an order of 9,200.

The discount comes from the table `discount` of [What is DMN?]. It reads the customer tier and the order amount, and its hit policy is **FIRST**: the first rule that matches decides. For the invoice that is rule 6, "Silver customer": 2 percent, total CHF 9,016.00.

The invoice also has the field `customer`, but no rule reads it yet.

---

## Rule Management in the Portal

1. Open the Portal and log in as Portal administrator.
2. Open **Admin settings** from the user menu and select the tab **Rule Management**.

The table lists the stored models with their active version, and the `.dmn` files of the projects that are not stored yet. `invoice-discount` is at version 1, from the project: the first Apply stored it.

| Action | What it does |
| --- | --- |
| **Edit** | Opens the active version in the editor dialog of `rule-engine-editor`, inside the tab; the project must be in the application. A file that is not stored yet is opened from its project. **Close** brings the list back. |
| **Show versions** | Lists every version with status, source, author and what changed. Open, activate, roll back. |
| **Refresh** | Reads the versions again. |

The editor is the dialog `RuleEditor` of the project `rule-engine-editor`, with the DMN editor of Apache KIE, the one of VS Code. The Portal shows it in an iframe. The browser of the rule owner only talks to the Axon Ivy Engine, never to the DMN engine.

---

## Add an input and a rule

What the business wants: Bluebird Architects signed a framework contract and gets 10 percent off every invoice, whatever its tier.

**One more input.** In the diagram of the editor, add an **Input Data** node `customer` of type `string` and connect it to the decision `discount`. Select the decision, click **Edit**, and add a column for the input.

The process data already has a field with this name, `in.invoice.customer`. The Business Rule Task finds it by name, so the process does not change.

**One more rule**, at the top of the table: right-click rule 1 and choose **Insert above**.

| # | customerTier | orderAmount | customer | discountPercent | discountReason |
| --- | --- | --- | --- | --- | --- |
| 1 | `-` | `-` | `"Bluebird Architects"` | 10 | Key account: framework contract |
| 2 to 8 | … | … | `-` | … | … |

The existing rules get `-` for the new column, so they decide as before. With FIRST the place of the new rule matters: below "Silver customer" it would never be reached for this Silver customer. At the top it decides for the key account, whatever the tier or the amount, and every other customer goes on to the rules below.

Then:

1. **Check** lets the DMN engine check the model. Nothing is stored.
2. Fill in **What changed?**, e.g. "Key account discount for Bluebird Architects".
3. **Save and activate**. The DMN engine checks the model again and refuses it with the findings of the DMN engine if a rule is not valid.

The result is version 2, from the source **portal**, with the Portal user as author. It is live at once. **Save as draft** stores it without making it live.

---

## After: the same invoice costs less

Click **Apply rules** on the page again, without changing anything:

| | Before | After |
| --- | --- | --- |
| Rule | 6, Silver customer | 1, Key account: framework contract |
| Discount | 2 %, 184.00 | 10 %, 920.00 |
| Total | 9,016.00 | 8,280.00 |
| Decided by | version 1 | version 2 |

Nothing was changed in the project, and nothing was deployed. What happened:

- The Business Rule Task evaluates version 2, the active one, from the Portal.
- The Business Rule Task sends the inputs that this version needs: the two of the file, and `customer`, which it finds in the process data by name.
- The page reads the table of version 2 and marks its rule 1, with the new column.

The `.dmn` file of the project still has the table without the customer. A version that was activated in the Portal is **never** replaced by the project: a later deployment stores the file as a draft beside it. To bring the version of the rule owner back into the project, export it with `RuleEngine.export("invoice-discount", 2, project)`.

---

## Versions and roll back

**Show versions** lists every version of `invoice-discount`:

| Version | Status | Source | Author | What changed |
| --- | --- | --- | --- | --- |
| 2 | active | portal | admin | Key account discount for Bluebird Architects |
| 1 | retired | project | the application, e.g. `demo v1` | Deployed with project rule-engine-demo |

**Roll back** makes version 1 active again, at once. The next Apply gives the invoice 2 percent again, without the key account discount. Any version can also be opened in the editor or activated on purpose.

---

## Who has the last word

Two places can change a rule: the project and the Portal. The rule that decides between them is simple.

> **The Ivy project provides the initial version. The application holds the live versions.**

- A version from the project goes live if no version is active yet, or if the active version also came from a project. This is what makes a saved file live during development, as on [Change rules without deployment].
- A version that was activated in the Portal or through the API is **never** replaced by a project. When the file of the project changes, or the project is deployed again, the file is stored as a draft beside it, and the log says so. The rule owner keeps the last word.
- The Portal shows such a draft in the row of the rule: *The application provides a new version 3 (demo v2). It is not active yet: do you want to activate it?* **Activate version 3** makes it live. **Keep version 2** retires it, so it is not offered again; a later version of the application is offered anew. A version of the project is told by its source and author, the application and its version.
- To bring the live rules back into the project, export the version: `RuleEngine.export("invoice-discount", 2, project)` writes it to the `dmn` folder.

In the demo: with version 2 of the Portal active, a developer raises "Silver customer" in `dmn/invoice-discount.dmn` from 2 to 3 percent. The next Apply stores the file as version 3, a draft, and version 2 still decides: the key account discount of 10 percent stays. In the Portal, the row of `invoice-discount` asks whether version 3 should go live.

Set the variable `RuleEngine.AutoDeploy` to `false` if a project should never store its files, and versions come from the Portal or the Java API only.

---

## Without the Portal

The editor dialog is a project of its own, `rule-engine-editor`, so any process can open it, with or without the Portal:

| From | How |
| --- | --- |
| A process of a project that requires `rule-engine-editor` | A Dialog Call of `com.axonivy.utils.rule.engine.editor.RuleEditor`, `start(String model, Integer version)`. Version null opens the active one. |
| A link | The process start `RuleEditor/editRule.ivp` of `rule-engine-editor`, with the parameters `model` and `version` (optional). |

1. Open `RuleEditor/editRule.ivp?model=invoice-discount`. In the Designer: `http://localhost:8080/~Developer-<workspace>/Developer-<workspace>/pro/rule-engine-editor/023F72FE11C582F1/editRule.ivp?model=invoice-discount`.
2. Select the decision in the diagram, `discount`, and click **Edit** below it: the decision table opens.
3. Click a cell, for example the result of rule 6, "Silver customer", type the new value and press Enter.
4. **Check** lets the DMN engine look at the model. **Save as draft** or **Save and activate** stores a new version, as in the Portal.

For now anybody may open the process start; a permission comes later. Without a Portal, roll back versions with the Java API, see [Several decisions and the Java API].

---

## What happens underneath

```text
 Browser ──► Portal (Admin Settings) ──► rule-engine: Portal/RuleManagement ──► versions: files of the application
    │                                    (callable sub process)
    └──► iframe: rule-engine-editor: RuleEditor/editRule.ivp ──► dialog RuleEditor ──► rule-engine ──► versions
                                                                                                   └──► DMN engine: checks a model
```

- The Portal finds the callable sub process `Portal/RuleManagement` of the connector by its signatures in the security context, the way it finds Smart Workflow. Without it, there is no tab and nothing else changes.
- Every call answers with JDK types only: the Portal needs no class of the rule engine.
- **Edit** opens the process start of the editor dialog, which the connector names in `ruleManagementInfo()`. When the dialog closes it tells the Portal (`postMessage`), and the list comes back.
- Only Portal administrators see the tab. The Portal checks the role again for every action.

---

## Good to know

- An input that the Portal version adds must exist in the process data: a field of the same name, set before the Business Rule Task. Otherwise the element throws `rule:engine:mapping`, as described in [Handle errors].
- The table shows the models of this application: every application stores its own versions. Applications can share one DMN engine: it keeps nothing of them.

---

## See also

- [Change rules without deployment]
- [Handle errors]
- [Several decisions and the Java API]: the same actions from Java, for a Rules UI of your own.
