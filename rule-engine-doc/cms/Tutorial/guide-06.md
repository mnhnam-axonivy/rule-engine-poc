# Change rules without deployment

A rule is data, not code. When a rule changes, the application stores a new **version** of the model and uses it from the next evaluation on. Nothing is built, nothing is deployed, nothing is restarted.

This page is the developer's side: the `.dmn` file of the project changes. How the owner of a rule changes it in the Portal is the next page, [Edit rules in the Portal].

> **Walkthrough:** the button next to the title plays this page in a simulated Designer: a rule is edited and is live with the next run, the application keeps every version, the case knows which one decided, and a version is rolled back.

---

## A saved file is live

During development the `.dmn` file of your project is what counts. Try it:

1. Start **Rule Engine Demo: invoice discount**. The Silver customer gets 2 percent, decided by version 1 of the model; the page says which version decided.
2. Open `dmn/invoice-discount.dmn` and change the result of rule 6, "Silver customer", from `2` to `3`. Save the file.
3. Click **Apply rules** again. The invoice gets 3 percent, the total is 8,924.00 instead of 9,016.00. The changed file became version 2, and the table on the page shows it.

Before every evaluation the Business Rule Task compares the file with what it stored last. The DMN engine checks a changed file, it is stored as a new version and goes live. An unchanged file is not stored again.

Content that is stored already never becomes a second version. If you change the 3 back to 2, version 1 is recognised and active again.

---

## Versions of the application

The application keeps every version of a model, as files in its file area: the folder `rule-engine/invoice-discount` holds `1.dmn`, `2.dmn` and `versions.json`, which says what each version is. At most one version is **active** and answers the evaluations.

| Status | Meaning |
| --- | --- |
| draft | Stored, not released. It can be evaluated on purpose, for a test run. |
| approved | Released. With an effective date it becomes active at that time. |
| active | Answers the evaluations of the model. |
| retired | Was replaced or withdrawn. It stays available for a rollback. |

Every version also knows where it came from:

| Source | Meaning |
| --- | --- |
| project | Stored from the `.dmn` file of an Ivy project. |
| portal | Changed by a rule owner in the Portal, or stored with `RuleEngine.register`. |
| api | Stored by other code. |

List the versions of a model in the Portal with **Show versions**, or in Java:

```java
List<ModelVersion> versions = RuleEngine.versions("invoice-discount");   // newest first
```

---

## Which version decided?

Rules change over time, so "why did this invoice get 2 percent?" needs to know which rules were in force. You do not choose a version: a Business Rule Task always evaluates the active version of the model, and the connector notes which version answered.

The connector writes it to the case, as custom field `ruleVersion_<model>`, here `ruleVersion_invoice-discount`. There is nothing to configure, which is why the element has no field for it. You can search and report on it like on any other custom field, and read it in a script; the page of the demo shows it next to the result:

```java
ivy.case.customFields().stringField("ruleVersion_invoice-discount").getOrNull()
```

---

## Roll back

A rule that turns out to be wrong is taken back in one call, in the Portal with **Roll back**, or in Java. The version that was active before becomes active again:

```java
RuleEngine.rollback("invoice-discount", "rule.approver");
```

A second rollback goes one step further back. Any version can also be activated directly:

```java
RuleEngine.activate("invoice-discount", 1, "rule.approver");
```

The change is live at once. Evaluations that are running at that moment finish with the version they started with.

> **A rollback changes the versions, not your project.** The `.dmn` file in the project still contains the rules you rolled back from. The running engine does not store an unchanged file again, so the rollback holds. After a restart of the engine or a new deployment the project stores its file again, and because the active version came from a project too, the file wins and its rules are live again. So after a rollback, also correct the file in the project.

---

## See also

- [Edit rules in the Portal]: a rule owner changes a rule, and who has the last word.
- [Several decisions and the Java API]: the same actions from Java.
- [Handle errors]
