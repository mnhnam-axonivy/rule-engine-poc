# Set up and run your first decision

This page gets you from nothing to your first decision: start the decision runtime, check the connection, price the invoice of the demo and look at what the runtime holds. It takes about ten minutes.

> **Walkthrough:** the button next to the title plays steps 3 and 4 in a simulated Designer, and a runtime that does not answer. Steps 1 and 2 happen on your own machine.

---

## Prerequisites

| You need | Why | Check |
| --- | --- | --- |
| Axon Ivy Designer 14.0.1 or newer | The projects are built for Axon Ivy 14. | You are reading this page in it. |
| Docker with Docker Compose | The decision runtime runs as a container. Docker Desktop and Rancher Desktop (dockerd) both work. | `docker compose version` |
| The repository `rule-engine` on your disk | It contains the Docker Compose file and the source of the runtime image. | The folder with `docker-compose.yml` |
| Port 8090 free | The runtime listens there by default. | `docker compose up` complains if it is taken. |

Optional, but recommended once you write your own rules: the [Apache KIE DMN editor for VS Code](https://marketplace.visualstudio.com/items?itemName=kie-group.vscode-extension-kogito-bundle). It shows a `.dmn` file as decision tables and a diagram instead of XML.

You do **not** need Java or Maven for this tutorial. The runtime image is built inside Docker.

---

## Step 1: Start the decision runtime

Open a terminal in the folder of the repository, the one with `docker-compose.yml`, and run:

```bash
docker compose up -d --build
```

The first run builds the image and takes a few minutes. After that the container starts in seconds.

Check that it is ready:

```bash
curl http://localhost:8090/q/health/ready
```

The answer contains `"status": "UP"` and `"activeModels": 0`. The runtime starts **empty**. It gets its models from your project, as you will see in step 3.

> The container holds the rule engine and its database. Every version of every model is stored in the Docker volume `rule-engine-data` and is loaded again after a restart. `docker compose down` keeps the volume, `docker compose down -v` deletes it.

---

## Step 2: Check the connection

The connector reads where the runtime is from the variables of your application. The defaults fit the Docker Compose file, so for this tutorial there is nothing to change.

```yaml
Variables:
  RuleEngine:
    # URL of the decision runtime, the container
    Url: http://localhost:8090
    # Shared secret of the runtime, empty for local development
    ApiKey: ""
    # Seconds to wait for a decision
    TimeoutSeconds: "10"
    # Register the .dmn files of the project on the runtime
    AutoDeploy: "true"
```

| Variable | Meaning |
| --- | --- |
| `RuleEngine.Url` | Where the runtime listens. Change it if you run the container on another host or port. |
| `RuleEngine.ApiKey` | Must equal `RULE_ENGINE_API_KEY` of the container. Empty means the API is open, which is fine on your machine and nowhere else. |
| `RuleEngine.TimeoutSeconds` | How long a Business Rule Task waits for the result. |
| `RuleEngine.AutoDeploy` | `true`: the `.dmn` files of a project are sent to the runtime by the project itself. |

To change a value, set it in `config/variables.yaml` of your own project, or in the Engine Cockpit for a deployed application.

---

## Step 3: Run your first decision

1. In the Designer, open the list of process starts.
2. Start **Rule Engine Demo: invoice pricing** (`InvoicePricingDemo/start.ivp`).
3. A page opens with an invoice of five lines, already priced:

| Line | Category | Qty × price | Line discount | Reason | VAT |
| --- | --- | --- | --- | --- | --- |
| Laptop 14" | Hardware | 10 × 1,200 | 6 % | Hardware: 10 units or more | 8.1 % |
| Office suite licence | Software | 25 × 120 | 15 % | Volume licence: 20 seats or more | 8.1 % |
| Installation and setup | Services | 8 × 150 | 0 % | Services are not discounted | 8.1 % |
| A4 paper | Consumables | 60 × 25 | 8 % | Consumables: 50 or more | 8.1 % |
| Project management handbook | Books | 12 × 45 | 5 % | Books: 10 copies or more | 2.6 % |

The total is CHF 18,265.55, and a Sales manager approves the invoice, because one line has a discount of 15 percent. In the **Runtime Log** you find this line:

```text
Invoice INV-2026-0042: total 18265.55, Sales manager
```

That is all it takes. Here is what happened:

1. The page called the callable sub process `InvoicePricing` with the invoice. A Script trimmed the promo code and wrote it in capitals.
2. The Business Rule Task found that the runtime did not know the model `invoice-pricing` yet, and sent it `dmn/invoice-pricing.dmn` of the project. The runtime stored it as **version 1**.
3. The element sent the seven inputs of the model, the list of lines among them, and got back the results of every decision in one answer. Each line was decided by a rule of the table on [What is DMN?].
4. Each result was written to the field with the name of its decision, in `in.pricing`. The page shows them, and the version that decided.

Now change the invoice and click **Apply rules**: country **DE**, **50** laptops, or tier **Platinum** with **Prepaid** and the promo code **WINTER26**. Every Apply asks the runtime again.

How the element is configured, and how it finds its inputs, is on the page [Use an existing DMN decision].

---

## Step 4: Look at what the runtime holds

The runtime has a REST API. Ask it for the versions of the model:

```bash
curl http://localhost:8090/api/models/invoice-pricing/versions
```

```json
[ { "model": "invoice-pricing", "version": 1, "status": "ACTIVE", "source": "PROJECT", "author": "com.axonivy.utils.ruleengine:rule-engine-demo:14.0.1-SNAPSHOT" } ]
```

You can also evaluate a decision without Axon Ivy. The order discounts need four inputs:

```bash
curl -X POST -H "Content-Type: application/json" \
     -d '{"decision":"orderDiscounts","input":{"customerTier":"Gold","paymentTerms":"Prepaid","promoCode":"WINTER26","invoiceDate":"2026-11-19"}}' \
     http://localhost:8090/api/models/invoice-pricing/evaluate
```

```json
{ "decision": "orderDiscounts", "model": "invoice-pricing", "version": 1,
  "result": [ { "percent": 2, "reason": "Gold customer bonus" },
              { "percent": 2, "reason": "Early payment: prepaid" },
              { "percent": 5, "reason": "Promotion WINTER26" } ],
  "results": { "orderDiscounts": [ … ], "pricedLines": null, … } }
```

Every input a decision needs must be in `input`, `null` if it has no value. The whole API is documented at `http://localhost:8090/q/swagger-ui`.

Or look at it in the browser: `http://localhost:8090/editor/` is the DMN editor of the runtime. It lists the models with their versions and shows a model as diagram and decision tables.

---

## If it does not work

| What you see | Cause | What to do |
| --- | --- | --- |
| The page says `rule:engine:unreachable` | The container is not running, or `RuleEngine.Url` points somewhere else. | `docker compose ps`, then `docker compose up -d`. Compare the port with `RuleEngine.Url`. |
| `docker compose up` fails with "port is already allocated" | Something else uses port 8090. | Create a file `.env` next to `docker-compose.yml` with the line `RULE_ENGINE_PORT=8091`, run `docker compose up -d` again and set `RuleEngine.Url` to `http://localhost:8091`. |
| The page says `rule:engine:dmn` and "A valid API key is required" | The container was started with `RULE_ENGINE_API_KEY`, the engine has no or another key. | Set `RuleEngine.ApiKey` to the same value. |
| The health check is not `UP` after a minute | The container is still starting or has stopped. | `docker compose logs rule-engine` shows why. |

The callable of the demo catches these errors, ends at **not priced** and returns the error, which the page shows in red. How a process reacts to them is the topic of [Handle errors].

---

## What comes next

- [Use an existing DMN decision]: use the Business Rule Task in your own process.
- [Create a new DMN from scratch]: let the element generate the decision table for you.
