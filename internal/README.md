# internal

Local tools around the Axon Ivy Rules Engine, kept out of the product like `rule-engine-doc`: nothing in the
repository refers to either folder. The repository builds, tests and ships without them.

## What is local, and where it came from

| Where | What it is | Was before |
| --- | --- | --- |
| `internal/editor-smoke` | Browser check of the DMN editor dialog `RuleEditor` of the project `rule-engine-editor`. | `internal/interactive_app_builder/tools` |
| `rule-engine-doc` | Ivy project with the tutorial: process start **Rule Engine Tutorial**, its dialog, the eleven pages of its path in the CMS, the built walkthroughs. Requires `rule-engine-demo`. | Part of `rule-engine-demo` |
| `rule-engine-doc/src_test` | `TestTutorial` and `TutorialWebIT`, with the Maven profile `web.test`. | `rule-engine-test` |
| `rule-engine-doc/internal/interactive_app_builder` | Builds the walkthroughs (Node.js). | `internal/interactive_app_builder` |
| `rule-engine-doc/doc` | Requirements: the concept and MARP-4893 to MARP-4896. | `doc` |

The Java package of the tutorial is `com.axonivy.utils.rule.engine.doc` (was `...engine.demo`), its link
`/pro/rule-engine-doc/19A8D1C0FFEE0003/tutorial.ivp`.

## The editor dialog check

`editor-smoke` uses the editor dialog of `rule-engine-editor` on a running Axon Ivy Engine, with its DMN engine
container running too, like a person would. It opens the process start `RuleEditor/editRule.ivp` for a model (default:
`discount` of `rule-engine-test`), changes a rule in the KIE editor, lets the DMN engine check it, saves it as a draft
(never live) and checks a model the DMN engine rejects. Then it opens the dialog in an iframe, as the Portal does,
and closes it: the parent page must hear of it. Every request that leaves the engine is blocked and counts as a
failure.

```
cd internal/editor-smoke
npm install                          # once
npm run smoke -- --url=http://localhost:8080/~Developer-<workspace>/Developer-<workspace>/ [--model=discount]
```

Screenshots land in `.preview/`. Needs Node.js 22 or newer and Chrome or Edge (set `CHROME_PATH` if neither is
found).

## Build the walkthroughs

The tutorial is a path of eleven pages, from what DMN is to production. Pages 01 to 08 have a **Walkthrough**
button, and the questions and answers one for their group "When rules change". It opens a simulated Process
Designer that plays the process of `rule-engine-demo`, explains what happens, and can be edited afterwards. The
walkthrough of page 01 plays on a decision table with "Try it", the one of page 02 shows the architecture block by
block. They are built in
`rule-engine-doc/internal/interactive_app_builder` (details, and how to write a walkthrough, in its
[README](../rule-engine-doc/internal/interactive_app_builder/README.md)).

Needs Node.js 22 or newer, and Chrome or Edge for `npm run smoke`.

```
cd rule-engine-doc/internal/interactive_app_builder
npm install          # once: esbuild, three.js, puppeteer-core
npm run build        # writes rule-engine-doc/webContent/resources/js: interactive-runtime.min.js, guide-01.js to guide-08.js, guide-11-3.js
npm test             # unit tests of the simulation
npm run smoke        # opens the tutorial in headless Chrome or Edge and plays every walkthrough; screenshots in .preview/
npm run preview      # the tutorial page without an engine: http://127.0.0.1:4310/
```

`npm run watch` rebuilds on every change, `node build.mjs --check` fails if the built files are not what the
sources produce. The build reads the real files of `rule-engine-demo` (`InvoicePricing.p.json`, its data
classes, `dmn/invoice-discount.dmn`): build again after the demo changes.

The built files are part of the Ivy project, so the tutorial runs without Node.js. To build and test the project:

```
mvn install -pl rule-engine-demo -am -DskipTests   # in the repository root: the projects rule-engine-doc requires
cd rule-engine-doc
mvn verify                                         # TestTutorial
mvn verify -Pweb.test                              # TutorialWebIT: the tutorial on an Axon Ivy Engine of its own
```

In PowerShell quote the profile, `mvn verify '-Pweb.test'`, otherwise it splits the argument at the dot. In Git
Bash set `NO_COLOR=1` for `web.test`, otherwise the build plugin cannot read the coloured engine state.

In the Designer: import `rule-engine`, `rule-engine-demo` and `rule-engine-doc`, then start
**Rule Engine Tutorial**.

## Keep it local

In a Git clone, list both folders in `.git/info/exclude`, so that no tracked file mentions them:

```
internal/
rule-engine-doc/
```
