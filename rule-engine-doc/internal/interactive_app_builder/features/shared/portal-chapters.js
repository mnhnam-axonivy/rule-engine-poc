/**
 * Starting points and a chapter that two lessons on the Portal share: the page Edit rules in the Portal (guide-07)
 * and the questions about rules that change (guide-11-3).
 */
import { ELEMENTS, MODEL, laptopText, discountsText } from './project.js';
import { WHAT_CHANGED, withKeyAccount } from './portal-rules.js';

const { RULE_TASK } = ELEMENTS;
const DMN = 'dmn:' + MODEL;
/** The discount of rule 6 of lineDiscountTable in the project file: "Hardware: 10 units or more". */
export const HARDWARE_RULE = DMN + ':lineDiscountTable.r6.o1';

/** The first run of the process: the file of the project is version 1 on the runtime. */
export function seedProjectVersion(app) {
  const file = app.dmnFiles.find(candidate => candidate.id === MODEL);
  app.runtime.register(MODEL, file.current, { source: 'PROJECT', author: 'rule-engine-demo' });
  app.runner.registered.set(MODEL, JSON.stringify(file.current));
  app.refreshRuntimeViews();
  return file;
}

/** What the rule owner leaves behind in the Portal: version 2, with the key account rule, is active. */
export function seedPortalVersion(app) {
  const file = seedProjectVersion(app);
  const { entry } = app.runtime.register(MODEL, withKeyAccount(file.current), { source: 'PORTAL', author: 'admin', activate: true });
  entry.description = WHAT_CHANGED;
  app.refreshRuntimeViews();
}

/** A developer raises the discount for 10 laptops or more in the file of the project, from 6 to 8 percent. */
export async function raiseHardware(d, note) {
  await d.tab(DMN);
  d.focus(DMN + '@lineDiscountTable/r6.o1');
  await d.wait(400);
  await d.edit(HARDWARE_RULE, '8');
  await d.note(note, { target: DMN + '@lineDiscountTable/rule6', wait: 1800 });
  await d.tab('process');
}

/**
 * Who has the last word: version 2 of the Portal is active, a developer changes the file of the project. The file
 * is registered as a draft, and the rule owner's version still decides. Its notes are LAST_WORD_STRINGS.
 */
export async function lastWord(d) {
  seedPortalVersion(d.app);
  await d.panel('runtime');
  await d.note('portalActive', { target: 'runtime:versions', extra: 600 });
  await d.panel(null);
  await raiseHardware(d, 'fileChanged');
  await d.run({
    on: {
      'rule:registered': () => d.note('asDraft'),
      'rule:mapped': ({ call }) => d.note('stillPortal', { target: 'el:' + RULE_TASK, quote: laptopText(call) + '\n' + discountsText(call) })
    }
  });
  await d.panel('runtime');
  await d.note('draftBeside', { target: 'runtime:v3', extra: 600 });
  await d.panel(null);
}

/** The notes of lastWord. */
export const LAST_WORD_STRINGS = {
  'tour.portalActive.title': 'Active: the version of the Portal',
  'tour.portalActive.text': 'Version 2 came from the Portal and is active: it has the key account rule. Version 1 is the file of the project.',
  'tour.fileChanged.title': 'The file changes',
  'tour.fileChanged.text': 'A developer raises the discount for 10 laptops or more in the project file, from 6 to 8 percent.',
  'tour.asDraft.title': 'Stored as a draft',
  'tour.asDraft.text': 'The changed file is registered, but as a draft. A version activated by a rule owner is never replaced by a project.',
  'tour.stillPortal.title': 'The rule owner keeps the last word',
  'tour.stillPortal.text': 'Version 2 of the Portal still decides: the laptops get 6 percent, and the key account discount stays.',
  'tour.draftBeside.title': 'Your file, as version 3',
  'tour.draftBeside.text': 'It waits beside the active version. To make it live, activate it on purpose, in the Portal or in the editor of the runtime.'
};
