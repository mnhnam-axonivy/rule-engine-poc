# What is DMN?

Many processes contain decisions: how much discount a line of an invoice gets, which VAT rate applies, who approves an invoice. **DMN**, the [Decision Model and Notation](https://www.omg.org/dmn/), is a standard for writing such decisions down, so that the people who own them can read them and an engine can evaluate them.

BPMN describes a process: what happens in which order. DMN describes a decision: which answer belongs to which facts. A process asks, the decision answers. That is why the two live apart, and why a rule can change without the process changing.

> **Walkthrough:** the button next to the title plays this page on a decision table, without any process: you read the table, try other inputs, see which rule wins, and add a rule of your own.

**Example used on this page: an invoice, priced by rules.** A wholesaler of office and IT equipment prices its invoices with rules: a discount per line, the VAT, order discounts, shipping, and who has to approve. The rules are in the file `dmn/invoice-pricing.dmn` of the demo project. This page reads one table of it, the discount of a line.

---

## Read a decision table

Most decisions are **decision tables**. This is the table `lineDiscountTable` of the example:

| | category | quantity | customerTier | country | percent | reason |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `"Books"` | `-` | `-` | `"DE", "AT"` | 0 | Fixed book price in DE and AT: no discount |
| 2 | `"Books"` | `>= 10` | `-` | `-` | 5 | Books: 10 copies or more |
| 3 | `"Services"` | `-` | `-` | `-` | 0 | Services are not discounted |
| 4 | `"Hardware"` | `>= 50` | `-` | `-` | 12 | Hardware: 50 units or more |
| 5 | `"Hardware"` | `[10..50)` | `"Gold", "Platinum"` | `-` | 10 | Hardware: 10 units or more, Gold and Platinum |
| 6 | `"Hardware"` | `[10..50)` | `-` | `-` | 6 | Hardware: 10 units or more |
| 7 | `"Software"` | `>= 20` | `-` | `-` | 15 | Volume licence: 20 seats or more |
| 8 | `"Software"` | `[5..20)` | `-` | `-` | 5 | Software: 5 seats or more |
| 9 | `"Consumables"` | `>= 50` | `-` | `-` | 8 | Consumables: 50 or more |
| 10 | `-` | `-` | `-` | `-` | 0 | No line discount |

- The **inputs** are the facts the table reads, one column each: the `category` and the `quantity` of a line, the `customerTier` and the `country` of the invoice.
- The **outputs** are the answer, here two columns: `percent`, a number, and `reason`, a text that says why.
- Each **row is a rule**. A cell of an input column is a **condition**. A rule matches when all its conditions hold, and then its outputs are the answer.
- `-` is a condition that matches everything: rule 6 does not care about the customer tier or the country.

The model calls this table once for every line of an invoice. Such a table is a **function** of the model, in DMN a *business knowledge model*; more on that at the end of the page.

---

## Try other inputs

The same table gives different answers for different facts:

| category | quantity | customerTier | country | Rule | percent |
| --- | --- | --- | --- | --- | --- |
| `"Hardware"` | 10 | `"Silver"` | `"CH"` | 6 | 6 |
| `"Hardware"` | 10 | `"Gold"` | `"CH"` | 5 | 10 |
| `"Hardware"` | 60 | `"Silver"` | `"CH"` | 4 | 12 |
| `"Software"` | 25 | `"Silver"` | `"CH"` | 7 | 15 |
| `"Books"` | 12 | `"Silver"` | `"CH"` | 2 | 5 |
| `"Books"` | 12 | `"Silver"` | `"DE"` | 1 | 0 |
| `"Services"` | 8 | `"Gold"` | `"CH"` | 3 | 0 |

Nothing in the table knows about a process or an invoice record. It only sees the four values it gets, and answers.

---

## First match wins

12 books for a customer in Germany match rule 1, but also rule 2 and rule 10. Which one decides is the **hit policy** of the table. Here it is **FIRST**: the rules are tried from the top, and the first one that matches decides. So rule 1 decides: 0 percent.

Two habits follow from it:

- Put the more specific rules above the more general ones. Books have a fixed price in Germany and Austria; that is why rule 1 stands above rule 2. The other way round, German books would get 5 percent.
- End with a rule of only `-`. It matches every case no other rule takes, so the table always has an answer.

DMN knows other hit policies. The demo model uses two more: **UNIQUE** for its VAT rates, where exactly one rule may match, and **COLLECT** for its order discounts, which returns the outputs of every rule that matches, so that discounts add up. The tables the Business Rule Task generates use FIRST.

---

## Conditions

A condition is written in **FEEL**, the expression language of DMN:

| Condition | Matches |
| --- | --- |
| `-` | everything |
| `"Books"` | exactly this text |
| `"DE", "AT"` | one of these |
| `>= 50` | a number of at least 50 |
| `[10..50)` | from 10 up to, but not including, 50 |
| `< 500` | a number below 500 |
| `not("Books")` | everything but this |
| `true` | the boolean true |
| `[date("2026-11-01")..date("2027-01-31")]` | a date between the 1st of November 2026 and the 31st of January 2027 |

Texts are in double quotes, numbers are not. A condition such as `"CH"` or `>= 50` does not match an empty value; only `-` does.

Ranges that meet leave no gap: 19 seats are in `[5..20)` of rule 8, 20 seats in `>= 20` of rule 7.

---

## Add a rule

A table changes by adding, removing or changing rules. Say consumables should get 4 percent from 20 boxes on, where today they get nothing below 50. **Add rule** in the editor puts the new rule above the rule that catches everything, otherwise it could never decide:

| | category | quantity | customerTier | country | percent | reason |
| --- | --- | --- | --- | --- | --- | --- |
| … | | | | | | |
| 9 | `"Consumables"` | `>= 50` | `-` | `-` | 8 | Consumables: 50 or more |
| 10 | `"Consumables"` | `[20..50)` | `-` | `-` | 4 | Consumables: 20 or more |
| 11 | `-` | `-` | `-` | `-` | 0 | No line discount |

30 boxes of paper now get 4 percent. Every other line gets what it got before: the rules above the new one still win where they match.

Where a rule goes matters. Should Platinum customers get 15 percent on hardware from 50 units on, that rule has to go **above** rule 4, which takes every hardware line of 50 units or more.

Where you change a table depends on who you are: a developer edits the `.dmn` file of the project in a DMN editor, a rule owner changes the rule in the Axon Ivy Portal. Both are explained later: [Change rules without deployment] and [Edit rules in the Portal].

---

## Several decisions in one model

A `.dmn` file is a **model**. It can hold several decisions, and one decision can use the answer of another. The demo file `dmn/invoice-pricing.dmn` prices a whole invoice in one call:

```text
 customerTier, paymentTerms,
 promoCode, invoiceDate ──────► orderDiscounts ──► orderDiscountPercent ─┐
                                                                         │
 lines, customerTier, country ───────────────────────────────────────────┼─► pricedLines ─┬─► shipping ─┐
    calls for every line: lineDiscountTable, vatRateTable, taxFormula ───┘                │             ├─► totals ─► approvalFlags ─► approval
 country, expressDelivery ────────────────────────────────────────────────────────────────┘─────────────┘
```

| Part | Kind | What it shows |
| --- | --- | --- |
| `lineDiscountTable` | function: decision table, FIRST | The table of this page, called once per line. |
| `vatRateTable` | function: decision table, UNIQUE | The VAT rate by country and category; exactly one rule matches. |
| `taxFormula` | function: literal expression | `round half up(net * rate / 100, 2)`: the VAT of a line. |
| `orderDiscounts` | decision table, COLLECT | Discounts on the whole invoice; every rule that matches counts. |
| `orderDiscountPercent` | literal expression | `min(10, sum(…))`: the discounts add up, to at most 10 percent. |
| `pricedLines` | literal expression | `for line in lines return { … }`: every line priced with the three functions. |
| `shipping` | decision table, FIRST | The fee, from the country, the sum of the lines and express delivery. |
| `totals` | literal expression, a context | The sums of the priced lines, plus shipping. |
| `approvalFlags`, `approval` | decision table, COLLECT; context | Every reason for an approval, and the highest approver they ask for. |

This picture is the **decision requirements diagram** of the model. [Several decisions and the Java API] shows how a process evaluates such a model at once.

---

## What comes next

- [How the rule engine works]: where the tables live and who evaluates them.
- [Set up and run your first decision]: the first decision of a real process.
