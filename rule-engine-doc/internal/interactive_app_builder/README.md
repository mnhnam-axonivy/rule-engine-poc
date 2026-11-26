# Interactive walkthroughs of the Rule Engine Tutorial

The tutorial (project `rule-engine-doc`, process start **Rule Engine Tutorial**) is a path of eleven pages, from
what DMN is to running it in production. Pages 01 to 08 have a **Walkthrough** button next to their title, and the
questions and answers (page 11) one next to their group "When rules change". A walkthrough opens a simulated Process
Designer that plays the process of `rule-engine-demo`, explains what happens, and can be edited afterwards. The
walkthrough of page 01 plays on a decision table only, with "Try it" beside it; the one of page 02 on the tab
**Architecture**: a live block diagram of the engine with its projects and the container with what is in its image.
Pages 09 and 10 are for reading. This folder builds those simulations.

Nothing here is part of the Maven build, and the iar of `rule-engine-doc` leaves this folder out. The built
files are stored in the project next to the tutorial, so it works without Node.js. `node build.mjs --check`
fails if they are not what the sources produce.

| Source | Built file in `rule-engine-doc/webContent/resources/js` |
| --- | --- |
| `runtime/` | `interactive-runtime.min.js`: the simulator (canvas, inscription masks, DMN table editor, panels, walkthrough player). three.js is bundled, nothing is loaded from the network. |
| `features/guide-NN/` | `guide-NN.js`: one lesson, the walkthrough of tutorial page NN. |
| `features/guide-NN-<n>/` | `guide-NN-<n>.js`: the walkthrough of the n-th `##` part of page NN, e.g. `guide-11-3` for the third group of the questions and answers. |

## Commands

Node.js 22 or newer. Run them in this folder.

| Command | What it does |
| --- | --- |
| `npm install` | Once. Installs esbuild, three.js and puppeteer-core. |
| `npm run build` | Builds the files above. `npm run watch` rebuilds on change. |
| `npm run preview` | Serves the real tutorial page without an Axon Ivy engine on `http://127.0.0.1:4310/`. Add `?renderer=canvas` to force the 2D fallback. |
| `npm test` | Unit tests of the model: DMN parsing, FEEL conditions, the simulated runtime, mapping, the DMN generator, the process runner. |
| `npm run smoke` | Opens the preview in headless Chrome or Edge, reads all seven pages, plays every walkthrough to its end and uses the simulator like a learner. Screenshots go to `.preview/`. Set `CHROME_PATH` if no browser is found. |

After a change: `npm run build`, `npm test`, `npm run smoke`.

The preview stands in for the engine: it resolves the few JSF parts of `Tutorial.xhtml` itself and has no
Axon Ivy theme. To run the same browser checks against the page a real engine serves:

```
# in rule-engine-doc: starts a test engine, runs TutorialWebIT, keeps the engine up for 7 minutes
mvn verify -Pweb.test -Dtutorial.hold.seconds=420
# in this folder, with the link the build prints as "Tutorial: ..."
npm run smoke -- --url=http://localhost:8081/ruleenginedoc/pro/rule-engine-doc/19A8D1C0FFEE0003/tutorial.ivp
```

The link of the process start in a running Designer works as well. Screenshots of that run go to
`.preview/engine/`.

## How the tutorial page uses it

- `rule-engine-doc/dialog/.../Tutorial/Tutorial.xhtml` loads the runtime and the lessons.
- The texts of the pages are Markdown in the CMS of `rule-engine-doc`: `cms/Tutorial/guide-01.md` to `guide-11.md`.
- `webContent/resources/js/tutorial.js` renders a page and adds the Walkthrough button to every page that has a
  lesson with the id `guide-NN`. The button mounts the lesson with
  `window.reInteractive.mount(host, 'guide-NN', { autoplay: true })`; `{ chapter: n }` starts at its chapter n.
- The overview of the tutorial is the path: one row per page, grouped from easy to hard. A row lists the `##`
  sections of its page as links that open the page there, with **Play** and the duration of its walkthrough
  (`window.reInteractive.about(id)`). A section that is a chapter of the walkthrough gets a ▶ that plays from that
  chapter. A new lesson shows up there by itself.
- A part of a page can have a lesson of its own: `guide-NN-n` gets its button next to the n-th `##` heading of the
  page. Page 11, the questions and answers, is such a page: each `##` is a group, each `###` a question; its third
  group has `guide-11-3`, one chapter per question, its `tour.chapter.<id>` the question word for word. Its contents
  list the questions below their group (`tocDepth: 3` in `PAGES`).

## A lesson

A lesson is a folder in `features/` with three files:

| File | Content |
| --- | --- |
| `index.js` | The definition: an id, the project to simulate, the texts and the walkthrough. |
| `text.js` | All texts of the walkthrough. `tour.chapter.<id>` is the heading of the section of the tutorial page that the chapter belongs to, word for word. |
| `tour.js` | The walkthrough: chapters, each an async function that directs the simulator. |

Every lesson simulates the demo project, `features/shared/project.js`. It imports the real files of
`rule-engine-demo` at build time: the callable sub process `InvoicePricing.p.json`, its data classes and
`dmn/invoice-discount.dmn`. A callable gets its data from its caller: the definition says what the page of the demo
passes (`params`, the sample invoice of `features/shared/invoice.js`, which is `InvoiceSamples.standard()`).
`tryTable` names the table of "Try it", `portalTable` the table a new input of the Portal goes into. Lessons 07 and 11-3 have the tab Portal (`portalProject`, `portal: true`); the key account rule the rule owner
makes (`shared/portal-rules.js`) and the chapter "Who has the last word" (`shared/portal-chapters.js`) are shared by
both, and the same version is decided by the real runtime in `RuleEngineContainerTest`. Lesson 05 simulates the
callable extended by a second Business Rule Task with New DMN... (`shared/approver.js`, built from the real files).
`features/shared/invoice.js` and `approver.js` are plain JavaScript, so the unit tests use them as the lessons do. Their
runs play at the normal speed, without `fast`: the runs are what the answers show. A change to the demo
process shows up in the walkthroughs with the next build, and the element ids in `ELEMENTS` have to follow it.

A chapter directs the simulator through the director `d` (see `runtime/ui/tour.js`):

```js
async function unreachable(d) {
  await d.panel('runtime');                                  // open the panel "Decision runtime"
  await d.note('running', { target: 'runtime:state' });      // text tour.running.* next to the state switch
  await d.runtime('stopped');                                // docker stop axonivy-rule-engine
  await d.panel(null);
  await d.run({                                              // run the process, stop at these moments
    on: {
      'rule:failed': ({ call }) => d.note('thrown', { quote: call.errorCode }),
      'enter:f8': () => d.note('caught', { target: 'el:f8' })
    }
  });
}
```

| The director can | With |
| --- | --- |
| show a note next to something | `d.note(key, { target, quote })` |
| run the process | `d.run({ fast, start, on })`, hooks `enter:<id>`, `after:<id>`, `rule:<phase>` |
| open an inscription mask, a tab, a panel | `d.open(id, tab)`, `d.close()`, `d.tab(name)`, `d.panel('log' \| 'runtime' \| 'data' \| null)` |
| type into a field, a table cell, a formula or `variables.yaml` | `d.edit('f2.code', fn)`, `d.edit('dmn:invoice-discount:discount.r6.o1', '3')`, `d.edit('dmn:<model>:<function>.expr', text)`, `d.edit('vars', fn)` |
| act on the runtime | `d.runtime('running' \| 'slow' \| 'stopped')`, `d.activate(model, version)`, `d.rollback(model)`, `d.owner(model)`, `d.ownerActivate()` |
| light the architecture | `d.tab('arch')`, then `d.arch({ lit: ['connector', 'api'], flow: ['http>'], label: { http: 'POST …' } })`; `d.arch(null)` ends it |
| use the Portal | `d.tab('portal')`, `d.click('portal@edit:invoice-discount')`, `d.portalInput('customer', 'string')`, `d.click('portal@discount/above1')` (or `/addRule`), `d.edit('portal:discount.r1.i3', '"Bluebird Architects"')`, `d.edit('portal:description', text)`, `d.click('portal@check')`, `d.click('portal@activate')`, `d.click('portal@versions:invoice-discount')`, `d.click('portal@rollback')` |

Targets of a note: `el:<id>` an element, `field:<id>.<key>` a field of a mask, `dmn:<model>@<table>/rule6` or
`@<table>/r6.o1` a rule or a cell of a table of the model (a decision or a function; `/head` its head, `/policy` its hit
policy, `/expr` a formula), `dmn:<model>@inputs`, `@try`, `@tryResult`, `@side` the parts of the editor,
`runtime:<call|matched|versions|v2|state|rollback|owner>` a part of the runtime panel, `vars@<text>` a line of
`variables.yaml`, `log:last`, `data:<field>`, `arch:<block or link>` a part of the architecture, `portal@<part>` a
part of the Portal tab (`discount/rule1`, `discount/r1.i3`, `discount/above1` for its tables).

Every chapter starts from the project as the tutorial ships it, so a learner can jump to any chapter.

## The tab Architecture

`runtime/ui/architecture.js` draws the parts as blocks and what connects them. Every lesson has the tab.

| Blocks | Ids |
| --- | --- |
| Axon Ivy Engine and its projects | `ivy`, `portal`, `demo`, `connector` |
| Docker container and what is in its image | `container`, `api`, `editor`, `engine`, `db` |
| Around the container | `browser`, `volume` |
| Links | `http` (connector and REST API, both ways: `http>`, `http<`), `ui` (browser to editor page), `store` (database to volume), `callable` (the Portal calls `Portal/RuleManagement` of the connector: `callable>`) |

The diagram is live. It reads the simulation: the state of the container, the versions in the database, the
`.dmn` files of the project, `RuleEngine.Url`, and the last call of a Business Rule Task. That is why the tab
has no bottom panel (log, decision runtime, process data): it is hidden while the tab is open, so a walkthrough
cannot show `d.panel(...)` there. A walkthrough lights blocks with `d.arch`. While a note points at a block
(`arch:<id>`), the camera of the diagram (`frame()` in `architecture.js`) scales and moves it so that the block
and what is lit with it stand in the middle of one side of the tab, zoomed in up to 1.2 or out down to 0.5, and
the note stands on the other side, beside all of it. That way the walkthrough also works in a small frame, such
as the tutorial inside the Axon Ivy Portal. `d.arch(null)`, which ends every chapter on the tab, shows the
whole diagram again. The texts are the keys `arch.*` in `runtime/i18n.js`.

A learner selects a block, outside of a walkthrough, to read what is in it: the details open in a drawer on the
right. Selecting another block changes them. The close button, Escape, or a click beside the blocks closes them.
From 1180 px of width on, the drawer stands beside the diagram, which takes the room that is left; on a
narrower screen it lies over the right part of the diagram.

When a project or a part of the image is added or renamed, change the blocks here, the walkthrough of page 02
(`features/guide-02`) and the sections of `cms/Tutorial/guide-02.md` it is named after.

## The tab Portal

`runtime/ui/portal.js` stands for the Axon Ivy Portal at Admin Settings › Rule Management, in the light look of the
Portal, for lessons with `portal: true`. It works on the simulated runtime the way the real tab works on the real
one through `Portal/RuleManagement`: the rules with their active version and the files the runtime does not know
yet, the editor (the decision tables and formulas of the KIE editor, with **+ Input** for a new input data node and its
column in the table `portalTable` of the lesson),
**Check**, **Save as draft**, **Save and activate** (a version from the source `PORTAL`, author `admin`), and the
versions with activate and roll back. Its texts are the keys `portal.*` in `runtime/i18n.js`, worded like the real tab.

When the real tab changes, change it here too: `portal/dialog/com/axonivy/portal/component/RuleManagement`.

## What is simulated

The simulation has no engine and no container. It implements what a learner can see:

| Part | In `runtime/model/` | Mirrors |
| --- | --- | --- |
| Process run | `runner.js`, `process.js`, `ivyscript.js` | The demo process: the start of a callable sub process with its parameters, scripts, the Business Rule Task, the Error Boundary Event. A subset of IvyScript, with loops over a list and BigDecimal arithmetic for the Scripts of the demo; like IvyScript it gives a field it reads a default value. |
| Business Rule Task | `runner.js`, `mapping.js` | `RuleTaskExecutor` of the connector: mapping by name, field lists, result and version mapping, the BPMN errors `rule:engine:*`, and the inputs of a version from the Portal that the file does not have. |
| Decision runtime | `decision-runtime.js` | `VersionStore` of the runtime: versions, status, source, the source of truth rule, rollback. It can be running, slow or stopped. |
| DMN | `dmn.js`, `feel.js`, `decision-runtime.js` | Decision tables with the conditions the tutorial uses: `-`, literals, comparisons, ranges of numbers and dates, lists, `not()`; hit policies FIRST, UNIQUE (two matches are an error), ANY, PRIORITY, COLLECT with or without an aggregation, RULE ORDER. Literal expressions in a subset of FEEL: arithmetic rounded as with decimals, `if`, `for`, contexts, paths over lists, filters and functions such as `sum`, `min`, `round half up`, `decimal`, `list contains`, `date`. Business knowledge models as functions, called once per element of a list. The inputs are checked against their types, allowed values and `null` included, as the DMN engine does with `rule.engine.type-check`. For the demo model the simulation answers exactly what the real runtime answers (`test/runner.test.mjs`, `RuleEngineContainerTest`); the features the demo does not use are tested on a model with functions and every hit policy, `test/fixtures/invoice-pricing.dmn`. |
| New DMN... | `dmn-generator.js` | `DmnGenerator` of `rule-engine`: one input per field, one table, a placeholder rule. |

Not simulated: the API key, effective dates, boxed expressions other than tables and literal expressions, and FEEL
function definitions. The simulator says so itself
under "Simulation" in its top right corner.

When the connector or the runtime changes behaviour that a walkthrough shows, change it here too. The unit
tests in `test/` describe the behaviour the simulation promises.
