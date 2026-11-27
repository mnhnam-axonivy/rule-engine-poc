/**
 * Guide 02 — texts of the walkthrough. tour.chapter.<id> are the headings of the tutorial
 * page, word for word.
 */
import { ABOUT_RULES } from '../shared/project.js';

export const strings = {
  en: {
    'lang': 'en',
    'tour.intro.title': 'How the parts fit together',
    'tour.intro.text': 'See the Rules Engine block by block: the Axon Ivy Engine with its two projects, the container with what is in its image, and the way one decision takes between them. Afterwards the diagram stays in the tab Architecture.',
    'tour.intro.duration': 'about 3 min',
    'tour.chapter.parts': 'How the parts fit together',
    'tour.chapter.projects': 'The Ivy projects',
    'tour.chapter.image': 'The decision runtime container',
    'tour.chapter.flow': 'A decision, block by block',

    'tour.ivy.title': 'Your processes run here',
    'tour.ivy.text': 'The Axon Ivy Engine: the Designer on your computer, later an engine on a server. It runs the processes, but it does not evaluate rules itself.',
    'tour.container.title': 'The rules are decided here',
    'tour.container.text': 'A Docker container next to the engine: the decision runtime. It evaluates the rules and answers what a process asks. It keeps no data of its own.',
    'tour.http.title': 'One connection',
    'tour.http.text': 'The two talk over HTTP. The engine sends the inputs of a decision, the runtime answers with the result. The variable `RuleEngine.Url` says where the runtime is.',
    'tour.why.title': 'Why two parts',
    'tour.why.text': 'Rules change more often than processes. A new version of a rule goes live while the engine and its processes keep running.',

    'tour.demo.title': 'Your project',
    'tour.demo.text': '`rule-engine-demo` stands for your own project. It has the processes, the process data and the rules: one `.dmn` file per model, in the folder `dmn`.',
    'tour.connector.title': 'The connector',
    'tour.connector.text': '`rule-engine` brings the Business Rule Task. It reads the inputs from the process data, calls the runtime and writes the result back. It keeps the versions of the rules, as files of the application, and can create a `.dmn` file from your process data.',
    'tour.requires.title': 'What you add',
    'tour.requires.text': 'Your project requires `rule-engine`, nothing else.',
    'tour.portal.title': 'The Portal, if you use it',
    'tour.portal.text': 'Rule owners change rules in the Axon Ivy Portal, at Admin Settings › Rule Management. The Portal does not depend on the rule engine: it finds `Portal/RuleManagement` of the connector in the security context. Page 06 shows it.',

    'tour.image.title': 'One image',
    'tour.image.text': 'The container runs the image `axonivy/rule-engine-runtime`. There is no Axon Ivy in it, and none of your rules. It is built from the project `rule-engine-runtime`, which is not an Ivy project: `docker build` builds it, `docker run` starts it.',
    'tour.api.title': 'REST API',
    'tour.api.text': 'The door of the container: check a `.dmn` file, take a version into memory, evaluate a decision with it. Port 8090 of your computer leads to it.',
    'tour.engine.title': 'DMN engine',
    'tour.engine.text': 'Apache KIE, the engine of Drools and Kogito. It checks a `.dmn` file and evaluates its decision tables, formulas and functions.',
    'tour.memory.title': 'Versions in memory',
    'tour.memory.text': 'The versions the engine loaded, compiled and ready. Nothing is stored here: after a restart the engine loads them again, so the container needs no volume.',
    'tour.editor.title': 'DMN editor page',
    'tour.editor.text': 'The container also serves a page: `http://localhost:8090/editor/`. The `.dmn` file of a project can be changed and checked there in a browser, without a Designer. Rule owners use the Portal instead, which needs no access to the runtime.',

    'tour.flowProcess.title': 'The process asks',
    'tour.flowProcess.text': 'The page of the demo called the process with an invoice. A Script summed its lines, and the process reaches its Business Rule Task. This is in `in.invoice`:',
    'tour.flowConnector.title': 'The connector prepares the call',
    'tour.flowConnector.text': 'The model `invoice-discount` needs two inputs. The connector finds each in the process data by its name.',
    'tour.flowRegister.title': 'First the file is checked',
    'tour.flowRegister.text': '`dmn/invoice-discount.dmn` was never stored, so the connector lets the DMN engine check it first.',
    'tour.flowStored.title': 'Version 1',
    'tour.flowStored.text': 'The connector stores the file as version 1, in the files of the application, and it is active. This happens once, until the file changes.',
    'tour.flowEvaluate.title': 'Then the question',
    'tour.flowEvaluate.text': 'The runtime does not hold version 1 yet, so the connector loads it into its memory. Then it sends the two inputs and asks for the decision `discount`.',
    'tour.flowDecide.title': 'The engine decides',
    'tour.flowDecide.text': 'It evaluates version 1, the active one. The rules are tried from the top; rule 6 is the first that matches:',
    'tour.flowResult.title': 'The answer',
    'tour.flowResult.text': 'The result goes back as JSON, with the number of the version that decided.',
    'tour.flowWritten.title': 'Back in the process',
    'tour.flowWritten.text': 'The connector writes each output to the field with its name, in `in.invoice`, and the version to the case.',
    'tour.designer.title': 'The same in the Designer',
    'tour.designer.text': 'This is how the next pages show that round trip: the Business Rule Task on the canvas, and the runtime above it.',

    'about.rules': ABOUT_RULES
  }
};
