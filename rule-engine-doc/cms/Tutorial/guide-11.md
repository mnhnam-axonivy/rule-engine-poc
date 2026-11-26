# Frequently asked questions

Short answers, grouped like the tutorial. Each answer links to the page that explains it in full. The group *When rules change* has a walkthrough of its own: the button next to its heading plays it, one chapter per question.

---

## Setup

### Do I need Java or Maven to try the rule engine?

No. The decision runtime is built inside Docker, and the projects run in the Designer. You need Docker and a free port 8090, see [Set up and run your first decision].

### The process ends at "not priced" and the log says `rule:engine:unreachable`. What is wrong?

The Business Rule Task cannot reach the decision runtime: the container is not running, or `RuleEngine.Url` points to another host or port. Check with `docker ps -a --filter name=axonivy-rule-engine` and compare the port. The table *If it does not work* on [Set up and run your first decision] lists the usual causes.

### Where are the versions of my rules stored? Are they lost when the container stops?

In the database of the runtime, in the Docker volume `rule-engine-data`. Removing the container, `docker rm -f axonivy-rule-engine`, keeps the volume; `docker volume rm rule-engine-data` deletes it. In production, mount and back up a volume for `/data`, see [Run it in production].

---

## Mapping and fields

### How does the Business Rule Task know which field to read?

By the name. For every input of the decision it looks for a field with the same name, starting at `in` and going down level by level; the first field it finds wins. There is nothing to configure, see [Use an existing DMN decision].

### My field has another name than the input. What do I do?

Set a field with the name of the input in a Script before the Business Rule Task, for example `in.invoice.customerTier = in.customer.tier;`. The same Script is where a value is calculated or cleaned first, as the demo does with the order amount.

### What are Input fields and Output fields for?

Only for **New DMN...**: they say which fields the new file is generated from. With an existing decision they are not used. See [Create a new DMN from scratch].

### I asked for a new DMN and got `rule:engine:mapping`. Why?

The name of the new DMN is empty, or another `.dmn` file has it already. The Runtime Log says which. Give the new DMN a name of its own; the file the element generated itself is reused and never overwritten.

---

## When rules change

### Do I have to change the process when a rule changes?

No. The process refers to the decision by its name, not to its rules. Change a rule in the `.dmn` file and save it: the next evaluation registers the changed file as a new version and uses it. A rule owner can also change the rule on the runtime, in the Portal, without a Designer.

| What changes | Does the process change? |
| --- | --- |
| A condition or a result of a rule, a new rule | No |
| A new input | Not if the process data has a field with its name, set before the element |
| An input or a result gets another name | Only if the process data has no field with the new name |

### A rule owner added an input in the Portal. Does the process need a change?

Not if the process data has a field with the name of the new input. The Business Rule Task sends the inputs that the answering version needs, also those that the `.dmn` file of the project does not have. In the demo the version from the Portal reads `customer` for a key account discount, and the element finds it in `in.invoice.customer`.

Without such a field the element throws `rule:engine:mapping`. Then add a field with the name of the input and set it before the Business Rule Task. The whole story is on the page [Edit rules in the Portal].

### I changed the .dmn file, but the old result comes back. Why?

A rule owner has activated a version on the runtime, in the Portal or through the API. Such a version stays active: a project never replaces it. Your changed file is registered too, but as a draft beside it.

To make your file live, activate its version on purpose, in the Portal or in the editor of the runtime. The rule is explained on the page [Edit rules in the Portal], under *Who has the last word*.

### How do I go back to the version before?

In the Portal: **Admin Settings › Rule Management**, **Show versions** of the model, then **Roll back**. The version before is active again, and the next evaluation uses it. Any other version of the list can be activated the same way. In Java: `RuleEngine.rollback("invoice-discount", "rule.approver")`.

### What happens when the decision runtime is down?

The element throws `rule:engine:unreachable`. Catch it with an Error Boundary Event on the element: the error code `rule:engine` catches every error of the rule engine. The demo then takes the path *not priced*, and its page shows the error. More on the page [Handle errors].

---

## Errors

### Which error code do I catch?

`rule:engine` on an Error Boundary Event catches every error of the Business Rule Task. Catch a longer code, such as `rule:engine:timeout`, on a second boundary event to treat it differently. The codes are listed on [Handle errors].

### What is the difference between `rule:engine:dmn` and `rule:engine:mapping`?

`rule:engine:dmn` comes from the runtime: it refused the call, for example an unknown model, an invalid file or a wrong API key. `rule:engine:mapping` comes from the element: it does not fit the decision, for example no decision selected or an input without a field. Retrying helps with neither; fix the model, the data or the process.

### Why did my process stop at the Business Rule Task?

The error was not caught: there is no Error Boundary Event with a fitting code on the element, and no Error Start Event for it in the project. Add one, as the demo process does.
