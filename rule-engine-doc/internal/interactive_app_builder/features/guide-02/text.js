/**
 * Guide 02 — texts of the walkthrough. tour.chapter.<id> are the headings of the tutorial
 * page, word for word.
 */
import { ABOUT_RULES } from '../shared/project.js';

export const strings = {
  en: {
    'lang': 'en',
    'tour.intro.title': 'How the parts fit together',
    'tour.intro.text': 'See the Rules Engine block by block: the Axon Ivy Engine with its three projects, the container with what is in its image, and the way one decision takes between them. Afterwards the diagram stays in the tab Architecture.',
    'tour.intro.duration': 'about 3 min',
    'tour.chapter.parts': 'How the parts fit together',
    'tour.chapter.projects': 'The Ivy projects',
    'tour.chapter.image': 'The decision runtime container',
    'tour.chapter.flow': 'A decision, block by block',

    'tour.ivy.title': 'Your processes run here',
    'tour.ivy.text': 'The Axon Ivy Engine: the Designer on your computer, later an engine on a server. It runs the processes, but it does not evaluate rules itself.',
    'tour.container.title': 'The rules are decided here',
    'tour.container.text': 'A Docker container next to the engine: the decision runtime. It holds every rule and answers what a process asks.',
    'tour.http.title': 'One connection',
    'tour.http.text': 'The two talk over HTTP. The engine sends the inputs of a decision, the runtime answers with the result. The variable `RuleEngine.Url` says where the runtime is.',
    'tour.why.title': 'Why two parts',
    'tour.why.text': 'Rules change more often than processes. The runtime takes a new version of a rule while the engine and its processes keep running.',

    'tour.demo.title': 'Your project',
    'tour.demo.text': '`rule-engine-demo` stands for your own project. It has the processes, the process data and the rules: one `.dmn` file per model, in the folder `dmn`.',
    'tour.connector.title': 'The connector',
    'tour.connector.text': '`rule-engine` brings the Business Rule Task. It reads the inputs from the process data, calls the runtime and writes the result back.',
    'tour.core.title': 'The shared code',
    'tour.core.text': '`rule-engine-core` holds the requests and responses of the runtime, and the generator that creates a `.dmn` file from a data class.',
    'tour.shared.title': 'On both sides',
    'tour.shared.text': 'The same code is compiled into the image of the runtime. Connector and runtime use the same classes, so they cannot drift apart.',
    'tour.requires.title': 'What you add',
    'tour.requires.text': 'Your project requires `rule-engine`, nothing else. Maven brings `rule-engine-core` along.',
    'tour.portal.title': 'The Portal, if you use it',
    'tour.portal.text': 'Rule owners change rules in the Axon Ivy Portal, at Admin Settings › Rule Management. The Portal does not depend on the rule engine: it finds `Portal/RuleManagement` of the connector in the security context. Page 06 shows it.',

    'tour.image.title': 'One image',
    'tour.image.text': 'The container runs the image `axonivy/rule-engine-runtime`. `docker compose up -d --build` builds it and starts it. There is no Axon Ivy in it, and none of your rules.',
    'tour.api.title': 'REST API',
    'tour.api.text': 'The door of the container: store a version, evaluate a decision, activate, roll back. Port 8090 of your computer leads to it.',
    'tour.engine.title': 'DMN engine',
    'tour.engine.text': 'Apache KIE, the engine of Drools and Kogito. It checks a `.dmn` file and evaluates its decision tables, formulas and functions.',
    'tour.db.title': 'Database',
    'tour.db.text': 'Every version of every model. Its file lies on a Docker volume, so the versions are still there after the container was replaced.',
    'tour.editor.title': 'DMN editor page',
    'tour.editor.text': 'The container also serves a page: `http://localhost:8090/editor/`. A rule can be changed there in a browser, without a Designer. Rule owners usually use the Portal instead, which needs no access to the runtime.',

    'tour.flowProcess.title': 'The process asks',
    'tour.flowProcess.text': 'The page of the demo called the process with an invoice. A Script summed its lines, and the process reaches its Business Rule Task. This is in `in.invoice`:',
    'tour.flowConnector.title': 'The connector prepares the call',
    'tour.flowConnector.text': 'The model `invoice-discount` needs two inputs. The connector finds each in the process data by its name.',
    'tour.flowRegister.title': 'First call: the file',
    'tour.flowRegister.text': 'The runtime does not know `dmn/invoice-discount.dmn` yet, so the connector sends the file.',
    'tour.flowStored.title': 'Version 1',
    'tour.flowStored.text': 'The DMN engine checks the file, the database stores it as version 1, and it is active. This happens once, until the file changes.',
    'tour.flowEvaluate.title': 'Second call: the question',
    'tour.flowEvaluate.text': 'Now the connector sends the two inputs and asks for the decision `discount`.',
    'tour.flowDecide.title': 'The engine decides',
    'tour.flowDecide.text': 'It evaluates the active version of the model. The rules are tried from the top; rule 6 is the first that matches:',
    'tour.flowResult.title': 'The answer',
    'tour.flowResult.text': 'The result goes back as JSON, with the number of the version that decided.',
    'tour.flowWritten.title': 'Back in the process',
    'tour.flowWritten.text': 'The connector writes each output to the field with its name, in `in.invoice`, and the version to the case.',
    'tour.designer.title': 'The same in the Designer',
    'tour.designer.text': 'This is how the next pages show that round trip: the Business Rule Task on the canvas, and the runtime above it.',

    'about.rules': ABOUT_RULES
  }
};
