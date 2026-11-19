/**
 * Guide 11, part 3 — texts of the walkthrough. tour.chapter.<id> are the questions of the part
 * "When rules change" of the page Frequently asked questions, word for word.
 */
import { LAST_WORD_STRINGS } from '../shared/portal-chapters.js';

const ABOUT_RULES = [
  'It starts empty. A .dmn file of the project becomes a version when a Business Rule Task evaluates it for the first time, and again whenever the file has changed.',
  'The tab Portal stands for the Axon Ivy Portal at Admin Settings › Rule Management. Its editor shows the decision tables and formulas of the DMN editor of Apache KIE; the real one also has the diagram and the data types.',
  'A version saved in the Portal has the source portal. It is never replaced by the project, and the Business Rule Task sends the inputs it needs, also ones the file of the project does not have.',
  'It listens on http://localhost:8090. Stopped or slow, the Business Rule Task throws the same BPMN errors as with the real container.',
  'The API key, the Portal login and effective dates are not simulated.'
].join('|');

export const strings = {
  en: {
    'lang': 'en',
    'tour.intro.title': 'When rules change',
    'tour.intro.text': 'Five questions about rules that change, each answered on the invoice pricing of the demo project: whether the process has to change, an input added in the Portal, a file that does not decide, a roll back, and a runtime that is down.',
    'tour.intro.duration': 'about 4 min',
    'tour.chapter.noChange': 'Do I have to change the process when a rule changes?',
    'tour.chapter.portalInput': 'A rule owner added an input in the Portal. Does the process need a change?',
    'tour.chapter.oldResult': 'I changed the .dmn file, but the old result comes back. Why?',
    'tour.chapter.rollBack': 'How do I go back to the version before?',
    'tour.chapter.down': 'What happens when the decision runtime is down?',

    'tour.before.title': 'Before',
    'tour.before.text': 'The 10 laptops get 6 percent: rule 6 of `lineDiscountTable` decides.',
    'tour.ruleChanged.title': 'A rule changes',
    'tour.ruleChanged.text': 'Hardware from 10 units on gets 8 percent now. Only the table changes, the process is not touched.',
    'tour.newVersion.title': 'A new version',
    'tour.newVersion.text': 'The next evaluation registers the changed file on the runtime, as version 2.',
    'tour.after.title': 'After',
    'tour.after.text': 'The same invoice: 8 percent on the laptops now.',
    'tour.sameProcess.title': 'The same process',
    'tour.sameProcess.text': 'The process refers to the model by its name, not to its rules, so nothing in it had to change.',

    'tour.portalVersion.title': 'A version from the Portal',
    'tour.portalVersion.text': 'A rule owner added a key account discount in the Portal. Its version 2 is active and also reads `customer`, which the .dmn file of the project does not have.',
    'tour.inputsSent.title': 'Sent all the same',
    'tour.inputsSent.text': 'The element sends what the answering version needs: `customer` is found by name, in `in.invoice`.',
    'tour.portalDecides.title': 'The rule of the Portal decides',
    'tour.portalDecides.text': 'Bluebird Architects is a key account: 3 percent off the whole invoice.',
    'tour.unchanged.title': 'No change in the process',
    'tour.unchanged.text': 'The process was not touched. The invoice had a field with the name of the new input already.',
    'tour.withoutField.title': 'Without such a field',
    'tour.withoutField.text': 'Had the invoice no field `customer`, the element would throw `rule:engine:mapping`. Then add a field with the name of the input and set it before the Business Rule Task.',

    ...LAST_WORD_STRINGS,

    'tour.versionList.title': 'Show versions',
    'tour.versionList.text': 'Every version of the model, with its source, its author and what changed. Version 2 from the Portal is active.',
    'tour.rollbackButton.title': 'Roll back',
    'tour.rollbackButton.text': 'One click makes the version before active again. Any other version of the list can be activated as well.',
    'tour.rolledBack.title': 'Back to version 1',
    'tour.rolledBack.text': 'Version 1 is active again. Version 2 is kept, for another day.',
    'tour.backAgain.title': 'With the next evaluation',
    'tour.backAgain.text': 'No key account discount any more, decided by version 1. Nothing was deployed.',

    'tour.stopped.title': 'The runtime is down',
    'tour.stopped.text': 'The container is stopped, for example for an update.',
    'tour.unreachable.title': 'rule:engine:unreachable',
    'tour.unreachable.text': 'The element cannot reach the runtime and throws `rule:engine:unreachable`.',
    'tour.boundary.title': 'Caught on the element',
    'tour.boundary.text': 'The Error Boundary Event with the error code `rule:engine` catches every error of the rule engine.',
    'tour.notPriced.title': 'Another path',
    'tour.notPriced.text': 'The process goes on along "not priced" and returns the error; the page of the demo shows it. A process of your own could hand the invoice to a person instead.',

    'about.rules': ABOUT_RULES
  }
};
