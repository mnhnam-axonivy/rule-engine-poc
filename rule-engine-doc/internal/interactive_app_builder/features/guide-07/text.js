/** tour.chapter.<id> are the headings of the tutorial page, word for word. */
import { LAST_WORD_STRINGS } from '../shared/portal-chapters.js';

const ABOUT_RULES = [
  'It starts empty. A .dmn file of the project becomes a version when a Business Rule Task evaluates it for the first time.',
  'The tab Portal stands for the Axon Ivy Portal at Admin Settings › Rule Management. Its editor shows the decision tables and formulas of the DMN editor of Apache KIE; the real one also has the diagram and the data types.',
  'A version saved in the Portal has the source portal. It is never replaced by the project, and the Business Rule Task sends the inputs it needs, also ones the file of the project does not have.',
  'It evaluates decision tables with the hit policies FIRST, UNIQUE and COLLECT, FEEL literal expressions and the functions of a model, as the DMN engine does.',
  'The API key, the Portal login and effective dates are not simulated.'
].join('|');

export const strings = {
  en: {
    'lang': 'en',
    'tour.intro.title': 'Edit rules in the Portal',
    'tour.intro.text': 'The rules of the demo do not know who the customer is. A rule owner adds the input `customer` and a key account discount in the Axon Ivy Portal. The same process then gives the same invoice a higher discount, without a deployment, and a changed file of the project does not take the place of the rule owner.',
    'tour.intro.duration': 'about 4 min',
    'tour.chapter.before': 'Before: no rule knows the customer',
    'tour.chapter.portal': 'Rule Management in the Portal',
    'tour.chapter.edit': 'Add an input and a rule',
    'tour.chapter.after': 'After: the same invoice costs less',
    'tour.chapter.versions': 'Versions and roll back',
    'tour.chapter.lastWord': 'Who has the last word',

    'tour.noKeyAccount.title': 'A Silver customer',
    'tour.noKeyAccount.text': 'The sample invoice gets 2 percent: rule 6, "Silver customer". Version 1 is the `.dmn` file of the project, registered with this first run.',
    'tour.table.title': 'What the table reads',
    'tour.table.text': '`discount` reads the customer tier and the order amount. The first rule that matches decides: hit policy FIRST.',
    'tour.customer.title': 'The invoice knows more',
    'tour.customer.text': '`in.invoice` also has the customer, Bluebird Architects. No rule reads it yet.',

    'tour.bridge.title': 'The Portal finds the rule engine',
    'tour.bridge.text': 'The Portal has no dependency on the rule engine. It looks for the callable sub process `Portal/RuleManagement` of the connector in the security context, the way it finds Smart Workflow. Only if it is there, the Admin Settings have the tab Rule Management.',
    'tour.tab.title': 'Admin Settings › Rule Management',
    'tour.tab.text': 'Portal administrators find the tab beside Role Management. The browser only talks to the Portal: the engine calls the runtime, with the API key.',
    'tour.row.title': 'The rules',
    'tour.row.text': 'The models of the runtime with their active version, and the `.dmn` files of the projects that the runtime does not know yet. `invoice-discount` is at version 1, from the project.',
    'tour.editor.title': 'The DMN editor',
    'tour.editor.text': 'Edit opens the active version in the DMN editor of Apache KIE, inside the Portal. It is the editor of the Designer and of VS Code, here in the browser of the rule owner.',

    'tour.goal.title': 'What the business wants',
    'tour.goal.text': 'Bluebird Architects signed a framework contract: 10 percent off every invoice, whatever the tier.',
    'tour.inputs.title': 'One more input',
    'tour.inputs.text': '`customer` becomes an input of the model and a new column of `discount`. The invoice has a field with this name, `in.invoice.customer`, so the Business Rule Task finds it. Nothing in the process changes.',
    'tour.newRule.title': 'A new rule, at the top',
    'tour.newRule.text': 'Insert above rule 1: the new rule becomes the first one. Every rule below it moves down by one.',
    'tour.keyAccount.title': 'The key account',
    'tour.keyAccount.text': 'For the customer "Bluebird Architects", whatever the tier or the amount: 10 percent, with the reason "Key account: framework contract".',
    'tour.first.title': 'Why at the top',
    'tour.first.text': 'Hit policy FIRST: the first rule that matches decides. Below "Silver customer" the key account rule would never be reached for this Silver customer.',
    'tour.checked.title': 'Check',
    'tour.checked.text': 'The DMN engine checks the model before anything is stored. A model with errors would be refused with its findings.',
    'tour.saved.title': 'Saved and active',
    'tour.saved.text': 'Version 2, from the source `portal`, by `admin`, with what changed. It is live on the runtime at once, and a deployment of the project never replaces it.',

    'tour.moreInputs.title': 'Three inputs now',
    'tour.moreInputs.text': 'The process is the same. The Business Rule Task sends what the active version needs: the two inputs of the file, and the customer it finds in the process data by name.',
    'tour.applied.title': 'The key account discount',
    'tour.applied.text': 'Version 2 from the Portal answers: its first rule matches.',
    'tour.lower.title': 'A lower total',
    'tour.lower.text': 'The process applies the discount as always: the same invoice costs 8,280.00 instead of 9,016.00.',
    'tour.untouched.title': 'The project is untouched',
    'tour.untouched.text': 'The file of the project still has the table without the customer. The runtime holds the version of the rule owner. `RuleEngine.export` brings it into the project, if you want it there.',

    'tour.versionList.title': 'Every version is kept',
    'tour.versionList.text': 'Version 2 from the Portal is active, version 1 from the project is retired. Any version can be opened in the editor or activated again.',
    'tour.rolledBack.title': 'Rolled back',
    'tour.rolledBack.text': 'One click, and version 1 is active again. A change is undone as fast as it was made.',
    'tour.backAgain.title': 'The old discount',
    'tour.backAgain.text': 'Version 1 decides again: 2 percent for the Silver customer, no key account discount.',

    ...LAST_WORD_STRINGS,

    'about.rules': ABOUT_RULES
  }
};
