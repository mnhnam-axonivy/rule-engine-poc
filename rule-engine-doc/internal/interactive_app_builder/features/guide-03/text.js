/**
 * Guide 03 — texts of the walkthrough. tour.chapter.<id> are the headings of the tutorial
 * page, word for word.
 */
import { ABOUT_RULES } from '../shared/project.js';

export const strings = {
  en: {
    'lang': 'en',
    'tour.intro.title': 'Your first decision',
    'tour.intro.text': 'Watch the demo process price its first invoice: what goes to the decision runtime, which rules decide and how the results get into the process data. Then what the runtime holds afterwards, and what happens when it does not answer.',
    'tour.intro.duration': 'about 3 min',
    'tour.chapter.run': 'Step 3: Run your first decision',
    'tour.chapter.holds': 'Step 4: Look at what the runtime holds',
    'tour.chapter.fails': 'If it does not work',

    'tour.called.title': 'The page calls the process',
    'tour.called.text': 'Apply rules on the page of the demo calls this callable sub process, `price(SalesInvoice)`, with the invoice: a Silver customer in Switzerland, five lines. The start copies it into `in.invoice`.',
    'tour.prepare.title': 'A Script prepares a value',
    'tour.prepare.text': 'It trims the promo code and writes it in capitals, so that `winter26` finds the rule for `WINTER26`. In your own process the values come from a form, a service or a Script like this one.',
    'tour.task.title': 'Business Rule Task',
    'tour.task.text': 'The element asks the decision runtime to price the invoice: every decision of `dmn/invoice-pricing.dmn` at once. You write no code and no REST call for it.',
    'tour.request.title': 'The inputs',
    'tour.request.text': 'The model needs seven inputs. The element reads each from the process data field with the same name, the list of lines too, and sends them to the runtime.',
    'tour.registered.title': 'The first call loads the model',
    'tour.registered.text': 'The runtime starts empty. The element sends `dmn/invoice-pricing.dmn` of the project along, and the runtime stores it as version 1.',
    'tour.response.title': 'The rules decide',
    'tour.response.text': 'The runtime evaluates the model. The table of line discounts runs once per line, and the first rule that matches decides each one:',
    'tour.mapped.title': 'The results',
    'tour.mapped.text': 'Each result goes to the field with the name of its decision, in `in.pricing`: the priced lines, the totals, who approves.',
    'tour.log.title': 'Log the result',
    'tour.log.text': 'The next script logs the total and the approver. In the Designer you find the line in the Axon Ivy Runtime Log.',
    'tour.where.title': 'Where it happened',
    'tour.where.text': 'The Business Rule Task belongs to the connector. It asked the REST API of the container, and the DMN engine there decided. The tab Architecture shows these parts and their state at any time.',

    'tour.versionOne.title': 'One model, one version',
    'tour.versionOne.text': 'The runtime holds the model `invoice-pricing` now: version 1, active, from the source project. `curl http://localhost:8090/api/models/invoice-pricing/versions` answers the same.',
    'tour.lastCall.title': 'The last call',
    'tour.lastCall.text': 'Each evaluation with its inputs, its results, the rules that decided and the version. The editor page of the runtime, `/editor/`, shows the models in the browser.',
    'tour.stored.title': 'In the database of the container',
    'tour.stored.text': 'The version lies in the database of the container, on the volume `rule-engine-data`. It is still there after a restart.',

    'tour.stopped.title': 'The runtime is down',
    'tour.stopped.text': 'The container is stopped, as if `docker compose up` had not run.',
    'tour.unreachable.title': 'rule:engine:unreachable',
    'tour.unreachable.text': 'The element cannot reach the runtime and throws `rule:engine:unreachable`: the first line of the table on this page.',
    'tour.caught.title': 'Caught',
    'tour.caught.text': 'The demo process catches every error of the rule engine with an Error Boundary Event, and returns the error to the page.',
    'tour.notPriced.title': 'Not priced',
    'tour.notPriced.text': 'The process ends at "not priced", and the page shows the error in red. Start the container, and the next Apply prices the invoice again. How a process reacts to errors is the page Handle errors.',

    'about.rules': ABOUT_RULES
  }
};
