/**
 * Guide 03 — texts of the walkthrough. tour.chapter.<id> are the headings of the tutorial
 * page, word for word.
 */
import { ABOUT_RULES } from '../shared/project.js';

export const strings = {
  en: {
    'lang': 'en',
    'tour.intro.title': 'Your first decision',
    'tour.intro.text': 'Watch the demo process decide the discount of its first invoice: what goes to the decision runtime, which rule decides and how the result gets into the process data. Then what the runtime holds afterwards, and what happens when it does not answer.',
    'tour.intro.duration': 'about 3 min',
    'tour.chapter.run': 'Step 3: Run your first decision',
    'tour.chapter.holds': 'Step 4: Look at the versions and the runtime',
    'tour.chapter.fails': 'If it does not work',

    'tour.called.title': 'The page calls the process',
    'tour.called.text': 'Apply rules on the page of the demo calls this callable sub process, `price(Invoice)`, with the invoice: a Silver customer, five lines. The start copies it into `in.invoice`.',
    'tour.sum.title': 'A Script prepares a value',
    'tour.sum.text': 'The rules need the order amount, the page does not send one. This Script works out the amount of each line and their sum, `in.invoice.orderAmount`. In your own process the values come from a form, a service or a Script like this one.',
    'tour.task.title': 'Business Rule Task',
    'tour.task.text': 'The element asks the decision runtime for the decision `discount` of `dmn/invoice-discount.dmn`. You write no code and no REST call for it.',
    'tour.request.title': 'The inputs',
    'tour.request.text': 'The model needs two inputs. The element reads each from the process data field with the same name and sends them to the runtime.',
    'tour.registered.title': 'The first call stores the model',
    'tour.registered.text': 'There is no version yet. The runtime checks `dmn/invoice-discount.dmn` of the project, the connector stores it as version 1 and loads it into the runtime.',
    'tour.response.title': 'A rule decides',
    'tour.response.text': 'The runtime evaluates the table from the top. The first rule that matches decides: a Silver customer, whatever the amount.',
    'tour.mapped.title': 'The result',
    'tour.mapped.text': 'The two outputs go to the fields with their names, `in.invoice.discountPercent` and `in.invoice.discountReason`.',
    'tour.applied.title': 'Apply the discount',
    'tour.applied.text': 'A last Script works out the discount amount and the total, and writes them to the invoice the page shows:',
    'tour.log.title': 'In the Runtime Log',
    'tour.log.text': 'The same Script logs the discount and the total. In the Designer you find the line in the Axon Ivy Runtime Log.',
    'tour.where.title': 'Where it happened',
    'tour.where.text': 'The Business Rule Task belongs to the connector. It asked the REST API of the container, and the DMN engine there decided. The tab Architecture shows these parts and their state at any time.',

    'tour.versionOne.title': 'One model, one version',
    'tour.versionOne.text': 'The application holds the model `invoice-discount` now: version 1, active, from the source project. Its files are `rule-engine/invoice-discount/1.dmn` and `versions.json`.',
    'tour.lastCall.title': 'The last call',
    'tour.lastCall.text': 'Each evaluation with its inputs, its results, the rules that decided and the version. The editor page of the runtime, `/editor/`, shows a `.dmn` file in the browser.',
    'tour.stored.title': 'In the files of the application',
    'tour.stored.text': 'The version lies in the files of the application, on the Axon Ivy Engine. The container only holds it in memory: after a restart the engine loads it again.',

    'tour.stopped.title': 'The runtime is down',
    'tour.stopped.text': 'The container is stopped, as if `docker run` had not run.',
    'tour.unreachable.title': 'rule:engine:unreachable',
    'tour.unreachable.text': 'The element cannot reach the runtime and throws `rule:engine:unreachable`: the first line of the table on this page.',
    'tour.caught.title': 'Caught',
    'tour.caught.text': 'The demo process catches every error of the rule engine with an Error Boundary Event, and returns the error to the page.',
    'tour.notPriced.title': 'Not priced',
    'tour.notPriced.text': 'The process ends at "not priced", and the page shows the error in red. Start the container, and the next Apply decides the discount again. How a process reacts to errors is the page Handle errors.',

    'about.rules': ABOUT_RULES
  }
};
