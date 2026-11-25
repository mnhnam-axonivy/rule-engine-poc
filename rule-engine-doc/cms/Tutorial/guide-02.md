# How the rule engine works

The **Axon Ivy Rules Engine** lets a process ask for a business decision instead of hard-coding it. The decisions are DMN models, as on [What is DMN?]. They live as `.dmn` files in your Ivy project and are evaluated by a **decision runtime**, a container that runs next to the Axon Ivy Engine. A process uses them through one element, the **Business Rule Task**.

This page shows the parts and how a decision travels between them. There is nothing to install yet: that is the next page.

> **Walkthrough:** the button next to the title shows the architecture block by block: the Axon Ivy Engine with its projects, the container with what is in its image, and the way a decision takes between them.

---

## How the parts fit together

Two things run side by side and talk over HTTP:

```text
 Axon Ivy Engine (Designer or server)           Docker container axonivy-rule-engine
 ┌──────────────────────────────────┐          ┌──────────────────────────────────────────┐
 │ Axon Ivy Portal    optional      │          │                                          │
 │   Admin Settings › Rule Mgmt     │          │                                          │
 │   ┆ calls Portal/RuleManagement  │          │                                          │
 │ rule-engine-demo   your project  │          │ image axonivy/rule-engine-runtime        │
 │   page, process, invoice-discount│          │                                          │
 │       │ requires                 │ register │  REST API /api      DMN editor /editor/  │ ◄── browser
 │ rule-engine        connector     │ ───────► │                                          │
 │   Business Rule Task, client     │ evaluate │  DMN engine         Database             │ ──► volume
 │   mapping, DMN generator         │ ◄─────── │  (Apache KIE)       (every version)      │
 │                                  │   JSON   │                                          │
 └──────────────────────────────────┘          └──────────────────────────────────────────┘
```

- The **Axon Ivy Engine** runs your processes: the Designer on your computer, later an engine on a server. It does not evaluate rules itself.
- The **decision runtime** is a Docker container next to it. It holds every rule and answers what a process asks.
- The only connection is HTTP. The engine sends the inputs of a decision, the runtime answers with the result. The variable `RuleEngine.Url` tells the engine where the runtime is.
- The **Axon Ivy Portal** is optional. If it runs in the same security context, rule owners change rules in its Admin Settings, at **Rule Management**. It reaches the runtime through the connector, never directly.

Rules change more often than processes. That is why they live apart: the runtime takes a new version of a rule while the engine and its processes keep running.

---

## The Ivy projects

| Project | Role | What is in it |
| --- | --- | --- |
| `rule-engine-demo` | Stands for your own project. | The page of the invoice, the process with a Business Rule Task that decides its discount, the process data, and the rules: one `.dmn` file per model, in the folder `dmn`, here `dmn/invoice-discount.dmn`. |
| `rule-engine` | The connector. Your project requires it. | The element **Business Rule Task**, the mapping between process data and decisions, the client of the runtime, the generator that creates a `.dmn` file from a data class, and the variables `RuleEngine.*`. |

In your own project you add one dependency, `rule-engine`.

The **Axon Ivy Portal** is not one of these projects and does not require any of them. It looks for the callable sub process `Portal/RuleManagement` of `rule-engine` in the security context, the way it finds Smart Workflow, and only then shows the tab Rule Management. [Edit rules in the Portal] shows it.

---

## The decision runtime container

The container `axonivy-rule-engine` runs the image `axonivy/rule-engine-runtime`. It is one generic image: there is no Axon Ivy in it, and none of your rules. Rules are loaded while it runs.

| In the image | What it does |
| --- | --- |
| REST API, `/api` | Stores a `.dmn` file as a new version, evaluates a decision, activates a version, rolls back. |
| DMN engine | Apache KIE, the engine of Drools and Kogito. It checks a `.dmn` file and evaluates its decision tables, formulas and functions. |
| Database | Every version of every model, and which one is active. |
| DMN editor page, `/editor/` | Change a rule in a browser, without a Designer. |

| Around the container | What it is for |
| --- | --- |
| Port 8090 | Port 8090 of your computer leads to port 8080 of the container. |
| Volume `rule-engine-data` | Holds the file of the database. The versions are still there after the container was replaced. |
| Folder `rule-engine/runtime` | The image is built from it, by `docker compose up -d --build`. |

---

## A decision, block by block

When a process reaches a Business Rule Task, this happens:

1. **Your project** has the inputs in its process data. In the demo the page passes the invoice, and a Script sums its lines: `customerTier = "Silver"`, `orderAmount = 9200`.
2. **The connector** finds the inputs the model needs in the process data, by their names.
3. If the runtime does not have the content of `dmn/invoice-discount.dmn` yet, the connector sends the file to the **REST API**. The **DMN engine** checks it, and the **database** stores it as a new version.
4. The connector sends the inputs to the REST API and asks for the decision `discount`.
5. The DMN engine evaluates the active version. The rules are tried from the top; the first one that matches decides, here rule 6: 2 percent.
6. The result goes back as JSON, with the number of the version that decided.
7. The connector writes each output to the field with its name, and the process goes on.
---

## What comes next

- [Set up and run your first decision]: start the runtime and see this happen.
