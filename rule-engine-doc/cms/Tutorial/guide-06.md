# Change rules without deployment

A rule is data, not code. When a rule changes, the decision runtime gets a new **version** of the model and uses it from the next evaluation on. Nothing is built, nothing is deployed, nothing is restarted.

This page is the developer's side: the `.dmn` file of the project changes. How the owner of a rule changes it in the Portal is the next page, [Edit rules in the Portal].

> **Walkthrough:** the button next to the title plays this page in a simulated Designer: a rule is edited and is live with the next run, the runtime keeps every version, the case knows which one decided, and a version is rolled back.

---

## A saved file is live

During development the `.dmn` file of your project is what counts. Try it:

1. Start **Rule Engine Demo: invoice pricing**. The 10 laptops get 6 percent, decided by version 1 of the model; the page says which version decided.
2. Open `dmn/invoice-pricing.dmn` and change the result of rule 6 of `lineDiscountTable`, "Hardware: 10 units or more", from `6` to `8`. Save the file.
3. Click **Apply rules** again. The laptops get 8 percent. The changed file became version 2.

Before every evaluation the Business Rule Task compares the file with what it registered last. A changed file is sent to the runtime, becomes a new version and goes live. An unchanged file is not sent again.

Content that the runtime already has never becomes a second version. If you change the 8 back to 6, the runtime recognises version 1 and makes it active again.

---

## Versions on the runtime

The runtime keeps every version of a model. Exactly one is **active** and answers the evaluations.

| Status | Meaning |
| --- | --- |
| draft | Stored, not released. It can be evaluated on purpose, for a test run. |
| approved | Released. With an effective date it becomes active at that time. |
| active | Answers the evaluations of the model. |
| retired | Was replaced or withdrawn. It stays available for a rollback. |

Every version also knows where it came from:

| Source | Meaning |
| --- | --- |
| project | Registered from the `.dmn` file of an Ivy project. |
| portal | Changed by a rule owner, in the Portal or in the editor page of the runtime. |
| api | Loaded by any other client of the REST API. |

List the versions of a model:

```bash
curl http://localhost:8090/api/models/invoice-pricing/versions
```

---

## Which version decided?

Rules change over time, so "why did these laptops get 6 percent?" needs to know which rules were in force. You do not choose a version: a Business Rule Task always evaluates the active version of the model, and every answer of the runtime names that version.

The connector writes it to the case, as custom field `ruleVersion_<model>`, here `ruleVersion_invoice-pricing`. There is nothing to configure, which is why the element has no field for it. You can search and report on it like on any other custom field, and read it in a script; the page of the demo shows it next to the result:

```java
ivy.case.customFields().stringField("ruleVersion_invoice-pricing").getOrNull()
```

---

## Roll back

A rule that turns out to be wrong is taken back in one call. The version that was active before becomes active again:

```bash
curl -X POST http://localhost:8090/api/models/invoice-pricing/rollback
```

A second rollback goes one step further back. Any version can also be activated directly:

```bash
curl -X POST -H "Content-Type: application/json" -d '{"user":"rule.approver"}' \
     http://localhost:8090/api/models/invoice-pricing/versions/1/activate
```

The change is live at once. Evaluations that are running at that moment finish with the version they started with.

> **A rollback changes the runtime, not your project.** The `.dmn` file in the project still contains the rules you rolled back from. The running engine does not send an unchanged file again, so the rollback holds. After a restart of the engine or a new deployment the project registers its file again, and because the active version came from a project too, the file wins and its rules are live again. So after a rollback, also correct the file in the project.

---

## See also

- [Edit rules in the Portal]: a rule owner changes a rule, and who has the last word.
- [Several decisions and the Java API]: the same actions from Java.
- [Handle errors]
