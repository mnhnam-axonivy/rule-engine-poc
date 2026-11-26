import { ELEMENTS, MODEL, DECISION, discountText } from './project.js';
import { WHAT_CHANGED, withKeyAccount } from './portal-rules.js';

const { RULE_TASK } = ELEMENTS;
const DMN = 'dmn:' + MODEL;
/** The discount of rule 6 of the table in the project file: "Silver customer". */
export const SILVER_RULE = DMN + ':' + DECISION + '.r6.o1';

export function seedProjectVersion(app) {
  const file = app.dmnFiles.find(candidate => candidate.id === MODEL);
  app.runtime.register(MODEL, file.current, { source: 'PROJECT', author: 'rule-engine-demo' });
  app.runner.registered.set(MODEL, JSON.stringify(file.current));
  app.refreshRuntimeViews();
  return file;
}

export function seedPortalVersion(app) {
  const file = seedProjectVersion(app);
  const { entry } = app.runtime.register(MODEL, withKeyAccount(file.current), { source: 'PORTAL', author: 'admin', activate: true });
  entry.description = WHAT_CHANGED;
  app.refreshRuntimeViews();
}

export async function raiseSilver(d, note) {
  await d.tab(DMN);
  d.focus(DMN + '@' + DECISION + '/r6.o1');
  await d.wait(400);
  await d.edit(SILVER_RULE, '3');
  await d.note(note, { target: DMN + '@' + DECISION + '/rule6', wait: 1800 });
  await d.tab('process');
}

export async function lastWord(d) {
  seedPortalVersion(d.app);
  await d.panel('runtime');
  await d.note('portalActive', { target: 'runtime:versions', extra: 600 });
  await d.panel(null);
  await raiseSilver(d, 'fileChanged');
  await d.run({
    on: {
      'rule:registered': () => d.note('asDraft'),
      'rule:mapped': ({ call }) => d.note('stillPortal', { target: 'el:' + RULE_TASK, quote: discountText(call) })
    }
  });
  await d.panel('runtime');
  await d.note('draftBeside', { target: 'runtime:v3', extra: 600 });
  await d.panel(null);
}

export const LAST_WORD_STRINGS = {
  'tour.portalActive.title': 'Active: the version of the Portal',
  'tour.portalActive.text': 'Version 2 came from the Portal and is active: it has the key account rule. Version 1 is the file of the project.',
  'tour.fileChanged.title': 'The file changes',
  'tour.fileChanged.text': 'A developer raises the discount of Silver customers in the project file, from 2 to 3 percent.',
  'tour.asDraft.title': 'Stored as a draft',
  'tour.asDraft.text': 'The changed file is registered, but as a draft. A version activated by a rule owner is never replaced by a project.',
  'tour.stillPortal.title': 'The rule owner keeps the last word',
  'tour.stillPortal.text': 'Version 2 of the Portal still decides: the key account discount stays, 10 percent.',
  'tour.draftBeside.title': 'Your file, as version 3',
  'tour.draftBeside.text': 'It waits beside the active version. To make it live, activate it on purpose, in the Portal or in the editor of the runtime.'
};
