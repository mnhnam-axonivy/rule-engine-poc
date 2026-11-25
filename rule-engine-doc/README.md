# rule-engine-doc

The tutorial of the Axon Ivy Rules Engine with its interactive walkthroughs, and the requirements the
implementation started from. Kept apart from the product: nothing outside this folder refers to it, so the
repository builds, tests and ships without it.

| Folder | Content |
| --- | --- |
| `process/`, `dialog/`, `dataclass/`, `src/`, `cms/`, `webContent/` | The Ivy project `rule-engine-doc`: the process start **Rule Engine Tutorial** (`RuleEngineTutorial/tutorial.ivp`), its dialog, the eleven pages of the path, from what DMN is to the questions and answers, as Markdown in the CMS (`cms/Tutorial/guide-01.md` to `guide-11.md`), and the built walkthroughs. |
| `src_test/` | `TestTutorial` (process start, pages, scripts) and `TutorialWebIT` (the page on a real engine). |
| [internal/interactive_app_builder](internal/interactive_app_builder) | Builds the walkthroughs (Node.js). See its [README](internal/interactive_app_builder/README.md). |
| `doc/` | Requirements: the concept and MARP-4893 to MARP-4896. |

The project requires `rule-engine-demo`, the project the tutorial explains, and through it `rule-engine`.
The iar leaves out `internal/` and `doc/`.

## Use it in the Designer

Import `rule-engine`, `rule-engine-demo` and `rule-engine-doc`, then start
**Rule Engine Tutorial**. Without a Designer: `npm run preview` in `internal/interactive_app_builder` serves the
same page on `http://127.0.0.1:4310/`.

## Build and test

The build takes the other projects from the local Maven repository. Put them there first, in the root of the
repository:

```
mvn install -pl rule-engine-demo -am -DskipTests
```

Then, in this folder:

| Command | What runs |
| --- | --- |
| `mvn verify` | `TestTutorial` in the Ivy test engine. |
| `mvn verify -Pweb.test` | `TutorialWebIT`: starts an Axon Ivy Engine of its own, deploys this project with the ones it requires and opens the tutorial over HTTP. `-Dtutorial.hold.seconds=420` keeps the engine up afterwards and prints the link. In PowerShell quote the profile: `'-Pweb.test'`. In Git Bash set `NO_COLOR=1`, otherwise the build plugin cannot read the coloured engine state and reports that the engine did not stop. |
| `npm test` and `npm run smoke` in `internal/interactive_app_builder` | The walkthroughs: unit tests of the simulation, and every walkthrough played in headless Chrome or Edge. |

After a change to the demo process, the connector or the runtime that a walkthrough shows: rebuild the
walkthroughs (`npm run build`) and run both kinds of tests.

## Keep it local

In a Git clone, list the folder in `.git/info/exclude` instead of `.gitignore`, so that no tracked file
mentions it. The folder `internal` of the repository root is local as well (see its README):

```
internal/
rule-engine-doc/
```
