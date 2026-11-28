# How the rule engine works

The **Axon Ivy Rules Engine** lets a process ask for a business decision instead of hard-coding it. The decisions are DMN models, as on [What is DMN?]. They live as `.dmn` files in your Ivy project and are evaluated by the **DMN engine** of Apache KIE, a container that runs next to the Axon Ivy Engine. A process uses them through one element, the **Business Rule Task**.

This page shows the parts and how a decision travels between them. There is nothing to install yet: that is the next page.

> **Walkthrough:** the button next to the title shows the architecture block by block: the Axon Ivy Engine with its projects, the container with what is in its image, and the way a decision takes between them.

---

## How the parts fit together

Two things run side by side and talk over HTTP:

```text
 Axon Ivy Engine (Designer or server)           Docker container axonivy-rule-engine
 ┌──────────────────────────────────┐          ┌──────────────────────────────────────────┐
 │ Axon Ivy Portal    optional      │          │ image apache/incubator-kie-kogito-       │
 │   Admin Settings › Rule Mgmt     │          │       jit-runner, from Apache KIE        │
 │   ┆ calls Portal/RuleManagement  │          │                                          │
 │ rule-engine-demo   your project  │          │                                          │
 │   page, process, invoice-discount│ validate │  DMN engine of Apache KIE                │
 │       │ requires                 │ ───────► │    HTTP /jitdmn/validate                 │
 │ rule-engine        connector     │ evaluate │         /jitdmn/dmnresult                │
 │   Business Rule Task, client     │ ◄─────── │    compiles the file of each call        │
 │   mapping, DMN generator         │   JSON   │    and keeps nothing                     │
 │   DMN editor  ◄── browser        │          │                                          │
 │ Files of the application         │          │                                          │
 │   rule-engine/: every version    │          │                                          │
 └──────────────────────────────────┘          └──────────────────────────────────────────┘
```

- The **Axon Ivy Engine** runs your processes: the Designer on your computer, later an engine on a server. It does not evaluate rules itself.
- The **DMN engine** is a Docker container next to it, the JIT runner of Apache KIE. It checks and evaluates the rules a process asks for. It keeps no data: every call carries the `.dmn` file of the version.
- The **versions** of the rules are files of your application, in the file area of the Axon Ivy Engine.
- The only connection is HTTP. The engine sends the file and the inputs of a decision, the DMN engine answers with the result. The variable `RuleEngine.Url` tells the engine where the DMN engine is.
- The **Axon Ivy Portal** is optional. If it runs in the same security context, rule owners change rules in its Admin Settings, at **Rule Management**. It reaches the DMN engine through the connector, never directly.

Rules change more often than processes. That is why they live apart from them: a new version of a rule goes live while the engine and its processes keep running.

---

## The Ivy projects

| Project | Role | What is in it |
| --- | --- | --- |
| `rule-engine-demo` | Stands for your own project. | The page of the invoice, the process with a Business Rule Task that decides its discount, the process data, and the rules: one `.dmn` file per model, in the folder `dmn`, here `dmn/invoice-discount.dmn`. |
| `rule-engine` | The connector. Your project requires it. | The element **Business Rule Task**, the versions of the models and their life cycle, the mapping between process data and decisions, the client of the DMN engine, the generator that creates a `.dmn` file from a data class, and the variables `RuleEngine.*`. |
| `rule-engine-editor` | The DMN editor. It requires the connector. | The dialog `RuleEditor` with the DMN editor of Apache KIE, for the Portal and any process: check a rule, save it as a new version. A project of its own because the KIE editor is big. |

In your own project you add one dependency, `rule-engine`. Where rules are changed in the browser, deploy `rule-engine-editor` beside it.

The container has no project: it runs the image of Apache KIE as it is published.

The **Axon Ivy Portal** is not one of these projects and does not require any of them. It looks for the callable sub process `Portal/RuleManagement` of `rule-engine` in the security context, the way it finds Smart Workflow, and only then shows the tab Rule Management. [Edit rules in the Portal] shows it.

---

## The DMN engine container

The container `axonivy-rule-engine` runs the image `apache/incubator-kie-kogito-jit-runner` of Apache KIE. It is one generic image: there is no Axon Ivy in it, and none of your rules. Every call brings the `.dmn` file it is about.

| In the image | What it does |
| --- | --- |
| DMN engine, `/jitdmn` | Apache KIE, the engine of Drools and Kogito. It answers HTTP itself: `POST /jitdmn/validate` checks a `.dmn` file, `POST /jitdmn/dmnresult` evaluates its decisions with the inputs of the call. It compiles the file of a call, evaluates its decision tables, formulas and functions, and forgets it again. |

| Around the container | What it is for |
| --- | --- |
| Port 8090 | Port 8090 of your computer leads to port 8080 of the container. |

---

## A decision, block by block

When a process reaches a Business Rule Task, this happens:

1. **Your project** has the inputs in its process data. In the demo the page passes the invoice, and a Script sums its lines: `customerTier = "Silver"`, `orderAmount = 9200`.
2. If `dmn/invoice-discount.dmn` changed since the connector stored it last, the **DMN engine** checks it, and the connector stores it as a new version in the **files of the application**.
3. **The connector** reads which inputs the active version needs from its file, and finds them in the process data by their names.
4. It sends the file of the active version and the inputs to the **DMN engine**: `POST /jitdmn/dmnresult`.
5. The DMN engine compiles the file and evaluates it. The rules are tried from the top; the first one that matches decides, here rule 6: 2 percent.
6. The result goes back as JSON. The connector keeps the result of the decision `discount` and the number of the version that decided.
7. It writes each output to the field with its name, and the process goes on.

---

## What comes next

- [Set up and run your first decision]: start the DMN engine and see this happen.
