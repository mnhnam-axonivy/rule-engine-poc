# What is DMN?

Many processes contain decisions: how much discount a customer gets, who approves an invoice, which risk a claim carries. **DMN**, the [Decision Model and Notation](https://www.omg.org/dmn/), is a standard for writing such decisions down, so that the people who own them can read them and an engine can evaluate them.

BPMN describes a process: what happens in which order. DMN describes a decision: which answer belongs to which facts. A process asks, the decision answers. That is why the two live apart, and why a rule can change without the process changing.

> **Walkthrough:** the button next to the title plays this page on a decision table, without any process: you read the table, try other inputs, see which rule wins, and add a rule of your own.

**Example used on this page: the discount of an invoice.** A wholesaler of office and IT equipment gives its customers a discount by their tier and by the size of the order. The rule is in the file `dmn/invoice-discount.dmn` of the demo project.

---

## Read a decision table

Most decisions are **decision tables**. This is the table `discount` of the example:

| | customerTier | orderAmount | discountPercent | discountReason |
| --- | --- | --- | --- | --- |
| 1 | `"Platinum"` | `>= 10000` | 12 | Platinum, order of 10,000 or more |
| 2 | `"Platinum"` | `-` | 8 | Platinum customer |
| 3 | `"Gold"` | `>= 10000` | 8 | Gold, order of 10,000 or more |
| 4 | `"Gold"` | `-` | 5 | Gold customer |
| 5 | `"Silver"` | `>= 10000` | 4 | Silver, order of 10,000 or more |
| 6 | `"Silver"` | `-` | 2 | Silver customer |
| 7 | `-` | `-` | 0 | No discount |

- The **inputs** are the facts the table reads, one column each: the `customerTier` and the `orderAmount` of an invoice.
- The **outputs** are the answer, here two columns: `discountPercent`, a number, and `discountReason`, a text that says why.
- Each **row is a rule**. A cell of an input column is a **condition**. A rule matches when all its conditions hold, and then its outputs are the answer.
- `-` is a condition that matches everything: rule 6 does not care about the order amount.

---

## Try other inputs

The same table gives different answers for different facts:

| customerTier | orderAmount | Rule | discountPercent |
| --- | --- | --- | --- |
| `"Silver"` | 9200 | 6 | 2 |
| `"Gold"` | 9200 | 4 | 5 |
| `"Gold"` | 10400 | 3 | 8 |
| `"Platinum"` | 9200 | 2 | 8 |
| `"Standard"` | 12000 | 7 | 0 |

Nothing in the table knows about a process or an invoice record. It only sees the two values it gets, and answers.

---

## First match wins

A Silver customer with an order of 12,000 matches rule 5, but also rule 6 and rule 7. Which one decides is the **hit policy** of the table. Here it is **FIRST**: the rules are tried from the top, and the first one that matches decides. So rule 5 decides: 4 percent.

Two habits follow from it:

- Put the more specific rules above the more general ones. That is why rule 5, for large orders, stands above rule 6. The other way round, a Silver customer would get 2 percent whatever the amount.
- End with a rule of only `-`. It matches every case no other rule takes, so the table always has an answer.

DMN knows other hit policies. **UNIQUE**: exactly one rule may match, two matching rules are an error. **COLLECT**: the outputs of every rule that matches come back as a list, for example discounts that add up. The tables the Business Rule Task generates use FIRST.

---

## Conditions

A condition is written in **FEEL**, the expression language of DMN:

| Condition | Matches |
| --- | --- |
| `-` | everything |
| `"Silver"` | exactly this text |
| `"Gold", "Platinum"` | one of these |
| `>= 10000` | a number of at least 10,000 |
| `< 5000` | a number below 5,000 |
| `[5000..10000)` | from 5,000 up to, but not including, 10,000 |
| `not("Standard")` | everything but this |
| `true` | the boolean true |
| `[date("2026-11-01")..date("2027-01-31")]` | a date between the 1st of November 2026 and the 31st of January 2027 |

Texts are in double quotes, numbers are not. A condition such as `"Silver"` or `>= 10000` does not match an empty value; only `-` does.

`>=` includes the number itself: an order of 9,999 is decided by rule 6, an order of 10,000 by rule 5.

---

## Add a rule

A table changes by adding, removing or changing rules. Say Standard customers should get 1 percent on orders of 10,000 or more, where today they get nothing. **Add rule** in the editor puts the new rule above the rule that catches everything, otherwise it could never decide:

| | customerTier | orderAmount | discountPercent | discountReason |
| --- | --- | --- | --- | --- |
| … | | | | |
| 6 | `"Silver"` | `-` | 2 | Silver customer |
| 7 | `"Standard"` | `>= 10000` | 1 | Standard, order of 10,000 or more |
| 8 | `-` | `-` | 0 | No discount |

A Standard customer with an order of 12,000 now gets 1 percent. Every other invoice gets what it got before: the rules above the new one still win where they match.

Where a rule goes matters. Should every order of 50,000 or more get 15 percent, whatever the tier, that rule has to go **above** rule 1, which takes every large Platinum order.

Where you change a table depends on who you are: a developer edits the `.dmn` file of the project in a DMN editor, a rule owner changes the rule in the Axon Ivy Portal. Both are explained later: [Change rules without deployment] and [Edit rules in the Portal].

---

## Several decisions in one model

A `.dmn` file is a **model**. Besides its decision tables it declares the **inputs** the decisions read, here `customerTier` and `orderAmount`, with their types.

The model of the demo has one decision. A model can hold several, and one decision can use the answer of another, for example an approval that reads the discount:

```text
 customerTier ─┐
               ├─► discount ─► approval
 orderAmount ──┘
```

This picture is the **decision requirements diagram** of a model. A decision can also be a formula instead of a table, a **literal expression** such as `orderAmount * discountPercent / 100`. [Several decisions and the Java API] shows how a process evaluates such a model at once.

---

## What comes next

- [How the rule engine works]: where the tables live and who evaluates them.
- [Set up and run your first decision]: the first decision of a real process.
