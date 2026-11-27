# Set up and run your first decision

This page gets you from nothing to your first decision: start the decision runtime, check the connection, let the rules decide the discount of the demo invoice and look at what the runtime holds. It takes about ten minutes.

> **Walkthrough:** the button next to the title plays steps 3 and 4 in a simulated Designer, and a runtime that does not answer. Steps 1 and 2 happen on your own machine.

---

## Prerequisites

| You need | Why | Check |
| --- | --- | --- |
| Axon Ivy Designer 14.0.1 or newer | The projects are built for Axon Ivy 14. | You are reading this page in it. |
| Docker | The decision runtime runs as a container. Docker Desktop and Rancher Desktop (dockerd) both work. | `docker version` |
| The repository `rule-engine` on your disk | It contains the project `rule-engine-runtime`, the source of the runtime image. | The folder with `rule-engine-runtime` |
| Port 8090 free | The runtime listens there by default. | `docker run` complains if it is taken. |

Optional, but recommended once you write your own rules: the [Apache KIE DMN editor for VS Code](https://marketplace.visualstudio.com/items?itemName=kie-group.vscode-extension-kogito-bundle). It shows a `.dmn` file as decision tables and a diagram instead of XML.

You do **not** need Java or Maven for this tutorial. The runtime image is built inside Docker.

---

## Step 1: Start the decision runtime

Open a terminal in the folder of the repository, the one with `rule-engine-runtime`, and build the image:

```bash
docker build -t axonivy/rule-engine-runtime:dev rule-engine-runtime
```

The first build takes a few minutes. Then start the container:

```bash
docker run -d --name axonivy-rule-engine -p 8090:8080 --restart unless-stopped axonivy/rule-engine-runtime:dev
```

It starts in seconds. Port 8090 of your computer leads to port 8080 of the container.

Check that it is ready:

```bash
curl http://localhost:8090/q/health/ready
```

The answer contains `"status": "UP"` and `"loadedVersions": 0`. The runtime starts **empty**. It gets its models from your application, as you will see in step 3.

> The container holds the rule engine, not your rules. The versions of the models are files of your Ivy application, and the engine loads them into the runtime again after a restart. `docker stop axonivy-rule-engine` stops the container, `docker start axonivy-rule-engine` starts it again. `docker rm -f axonivy-rule-engine` removes it; nothing is lost.

---

## Step 2: Check the connection

The connector reads where the runtime is from the variables of your application. The defaults fit the container of step 1, so for this tutorial there is nothing to change.

```yaml
Variables:
  RuleEngine:
    # URL of the decision runtime, the container
    Url: http://localhost:8090
    # Shared secret of the runtime, empty for local development
    ApiKey: ""
    # Seconds to wait for a decision
    TimeoutSeconds: "10"
    # Store the .dmn files of the project as versions
    AutoDeploy: "true"
```

| Variable | Meaning |
| --- | --- |
| `RuleEngine.Url` | Where the runtime listens. Change it if you run the container on another host or port. |
| `RuleEngine.ApiKey` | Must equal `RULE_ENGINE_API_KEY` of the container. Empty means the API is open, which is fine on your machine and nowhere else. |
| `RuleEngine.TimeoutSeconds` | How long a Business Rule Task waits for the result. |
| `RuleEngine.AutoDeploy` | `true`: a project stores its `.dmn` files as versions itself. |

To change a value, set it in `config/variables.yaml` of your own project, or in the Engine Cockpit for a deployed application.

---

## Step 3: Run your first decision

1. In the Designer, open the list of process starts.
2. Start **Rule Engine Demo: invoice discount** (`InvoicePricingDemo/start.ivp`).
3. A page opens with an invoice of five lines for the customer Bluebird Architects, tier **Silver**, already decided:

| | |
| --- | --- |
| Order amount | 9,200.00 |
| Discount | 2 %, "Silver customer" |
| Total | CHF 9,016.00 |

Below it, the page shows the table `discount` of [What is DMN?], with rule 6 marked: the rule that decided. In the **Runtime Log** you find this line:

```text
Invoice INV-2026-0042: 2% (Silver customer), total 9016.00
```

That is all it takes. Here is what happened:

1. The page called the callable sub process `InvoicePricing` with the invoice. A Script worked out the amount of each line and their sum, `orderAmount`.
2. The Business Rule Task found no version of the model `invoice-discount` yet. The runtime checked `dmn/invoice-discount.dmn` of the project, and the connector stored it as **version 1**, in the files of the application. Then it loaded version 1 into the runtime.
3. The element sent the two inputs of the decision, `customerTier` and `orderAmount`, and got back its two outputs. Rule 6 decided.
4. The outputs were written to the fields with their names, `in.invoice.discountPercent` and `in.invoice.discountReason`. A last Script applied the discount. The page shows the result, and the version that decided.

Now change the invoice and click **Apply rules**: tier **Gold**, then **5** laptops instead of 4, for an order of 10,400. Every Apply asks the runtime again.

How the element is configured, and how it finds its inputs, is on the page [Use an existing DMN decision].

---

## Step 4: Look at the versions and the runtime

The versions are files of the application, in the file area of the engine, the folder where IvyScript `File` writes too. The model `invoice-discount` has a folder `rule-engine/invoice-discount` there, with the DMN file of each version, `1.dmn`, and `versions.json`, which describes them:

```json
{ "model" : "invoice-discount",
  "versions" : [ { "version" : 1, "status" : "ACTIVE", "source" : "PROJECT",
                   "author" : "<application> v1",
                   "description" : "Deployed with project rule-engine-demo 14.0.1-SNAPSHOT", ... } ] }
```

The runtime has a REST API. Ask it which versions it holds in memory:

```bash
curl http://localhost:8090/api/models
```

It holds version 1 of `invoice-discount`, with its decision and the inputs it needs. You can also evaluate it without Axon Ivy:

```bash
curl -X POST -H "Content-Type: application/json"      -d '{"decision":"discount","input":{"customerTier":"Gold","orderAmount":12000}}'      http://localhost:8090/api/models/invoice-discount/versions/1/evaluate
```

```json
{ "decision": "discount", "model": "invoice-discount", "version": 1,
  "result": { "discountPercent": 8, "discountReason": "Gold, order of 10,000 or more" },
  "results": { "discount": { "discountPercent": 8, "discountReason": "Gold, order of 10,000 or more" } } }
```

Every input a decision needs must be in `input`, `null` if it has no value. The whole API is documented at `http://localhost:8090/q/swagger-ui`.

Or look at the rules in the browser: `http://localhost:8090/editor/` is the DMN editor of the runtime. **Open .dmn file…** shows `dmn/invoice-discount.dmn` as diagram and decision table.

---

## If it does not work

| What you see | Cause | What to do |
| --- | --- | --- |
| The page says `rule:engine:unreachable` | The container is not running, or `RuleEngine.Url` points somewhere else. | `docker ps -a --filter name=axonivy-rule-engine`, then `docker start axonivy-rule-engine`. Compare the port with `RuleEngine.Url`. |
| `docker run` fails with "port is already allocated" | Something else uses port 8090. | Remove the container it created, `docker rm axonivy-rule-engine`, run `docker run` again with `-p 8091:8080` instead of `-p 8090:8080`, and set `RuleEngine.Url` to `http://localhost:8091`. |
| `docker run` fails with "The container name ... is already in use" | The container exists from an earlier run. | `docker start axonivy-rule-engine` starts it. To start it from a new image, `docker rm -f axonivy-rule-engine` first; nothing is lost. |
| The page says `rule:engine:dmn` and "A valid API key is required" | The container was started with `RULE_ENGINE_API_KEY`, the engine has no or another key. | Set `RuleEngine.ApiKey` to the same value. |
| The health check is not `UP` after a minute | The container is still starting or has stopped. | `docker logs axonivy-rule-engine` shows why. |

The callable of the demo catches these errors, ends at **not priced** and returns the error, which the page shows in red. How a process reacts to them is the topic of [Handle errors].

---

## What comes next

- [Use an existing DMN decision]: use the Business Rule Task in your own process.
- [Create a new DMN from scratch]: let the element generate the decision table for you.
