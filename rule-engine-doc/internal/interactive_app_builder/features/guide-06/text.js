/** tour.chapter.<id> are the headings of the tutorial page, word for word. */
import { ABOUT_RULES } from '../shared/project.js';

export const strings = {
  en: {
    'lang': 'en',
    'tour.intro.title': 'How a rule changes without a deployment',
    'tour.intro.text': 'Watch a rule being edited and going live with the next run, the versions the runtime keeps, how the case knows which one decided, and a version being rolled back.',
    'tour.intro.duration': 'about 4 min',
    'tour.chapter.save': 'A saved file is live',
    'tour.chapter.versions': 'Versions on the runtime',
    'tour.chapter.version': 'Which version decided?',
    'tour.chapter.rollback': 'Roll back',

    'tour.before.title': 'Where we start',
    'tour.before.text': 'The first run registers `dmn/invoice-discount.dmn` as version 1. Rule 6, "Silver customer", gives the sample invoice 2 percent.',
    'tour.edited.title': 'A rule is edited',
    'tour.edited.text': 'Rule 6, "Silver customer", now gives 3 instead of 2. In the Designer you would do this in the DMN editor and save the file.',
    'tour.pending.title': 'Not on the runtime yet',
    'tour.pending.text': 'The file is saved, but the runtime still has version 1. Nothing has to be built or deployed: the next evaluation takes care of it.',
    'tour.registered.title': 'A new version',
    'tour.registered.text': 'The Business Rule Task sees that the file has changed and sends it along. The runtime stores it as version 2 and makes it active.',
    'tour.live.title': 'Live',
    'tour.live.text': 'The same invoice, the same process, a new rule: 3 percent, decided by version 2. The total is 8,924.00 instead of 9,016.00.',

    'tour.list.title': 'Every version is kept',
    'tour.list.text': 'The runtime stores each version of a model with its status, where it came from and who brought it.',
    'tour.active.title': 'Exactly one is active',
    'tour.active.text': 'Version 2 answers the evaluations. It came from the project, so its author is the project.',
    'tour.retired.title': 'Retired, not gone',
    'tour.retired.text': 'Version 1 was replaced. It stays on the runtime, ready to be activated again.',

    'tour.rollbackButton.title': 'Roll back',
    'tour.rollbackButton.text': 'One call to the runtime: `POST /api/models/invoice-discount/rollback`. No file is touched.',
    'tour.rolledBack.title': 'Version 1 is active again',
    'tour.rolledBack.text': 'The version that was active before took over at once. Version 2 is retired.',
    'tour.rollbackResult.title': 'The old rule decides',
    'tour.rollbackResult.text': 'The process was not touched and nothing was deployed. The Silver customer gets 2 percent again.',
    'tour.rollbackFile.title': 'The file still says 3',
    'tour.rollbackFile.text': 'A rollback changes the runtime, not your project. Bring the file in line with it, or it will go live again the next time the project registers its file, for example after an engine restart.',

    'tour.answerNames.title': 'The answer names it',
    'tour.answerNames.text': 'Every answer of the runtime says which version decided, here version 1.',
    'tour.activeOne.title': 'Always the active one',
    'tour.activeOne.text': 'You do not choose a version. A Business Rule Task always evaluates the active version of the model; the runtime keeps the others for a roll back.',
    'tour.onTheCase.title': 'Stored on the case',
    'tour.onTheCase.text': 'The connector writes the version to the case, as custom field `ruleVersion_invoice-discount`. That is why the element needs no field for it. The page of the demo shows it next to the result.',

    'about.rules': ABOUT_RULES
  }
};
