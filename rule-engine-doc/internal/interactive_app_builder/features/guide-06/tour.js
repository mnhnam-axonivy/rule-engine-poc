/**
 * Guide 06 — the walkthrough. Four chapters, each starting from the project as the tutorial
 * ships it; the notes are in text.js. See runtime/ui/tour.js for what the director `d` can do.
 */
import { ELEMENTS, MODEL, DECISION, discountText } from '../shared/project.js';

const { RULE_TASK } = ELEMENTS;
const DMN = 'dmn:' + MODEL;
const AT = DMN + '@' + DECISION + '/';

const callOf = result => (result.ruleCalls || [])[0] || {};

/** The discount of rule 6 of the table, "Silver customer", as the model has it. */
const withSilver = (dmn, value) => {
  const copy = JSON.parse(JSON.stringify(dmn));
  copy.decisions.find(decision => decision.name === DECISION).table.rules[5].outputs[0] = String(value);
  return copy;
};

/**
 * Two versions from the project, as after "edit the file, run again": version 1 with the file
 * as shipped, version 2 with 3 percent for Silver customers, which is also what the file says now.
 * The engine remembers that it registered this file, as it does after a real run.
 */
function seedTwoVersions(app) {
  const file = app.dmnFiles.find(candidate => candidate.id === MODEL);
  const options = { source: 'PROJECT', author: 'rule-engine-demo' };
  app.runtime.register(MODEL, file.current, options);
  file.current = withSilver(file.current, 3);
  app.runtime.register(MODEL, file.current, options);
  app.runner.registered.set(MODEL, JSON.stringify(file.current));
  app.renderDmn();
  app.renderTabs();
  app.refreshRuntimeViews();
}

/** 1 — edit the file, run again: the new rule decides. */
async function save(d) {
  await d.run({ fast: true, on: { 'rule:mapped': ({ call }) => d.note('before', { quote: discountText(call) }) } });
  await d.tab(DMN);
  d.focus(AT + 'r6.o1');
  await d.wait(400);
  await d.edit(DMN + ':' + DECISION + '.r6.o1', '3');
  await d.note('edited', { target: AT + 'rule6', wait: 2000 });
  await d.note('pending', { target: DMN + '@side' });
  await d.tab('process');
  await d.run({
    fast: true,
    on: {
      'rule:registered': () => d.note('registered'),
      'rule:mapped': ({ call }) => d.note('live', { quote: discountText(call) })
    }
  });
}

/** 2 — what the runtime keeps. */
async function versions(d) {
  seedTwoVersions(d.app);
  await d.panel('runtime');
  await d.note('list', { target: 'runtime:versions' });
  await d.note('active', { target: 'runtime:v2' });
  await d.note('retired', { target: 'runtime:v1' });
  await d.panel(null);
}

/** 3 — which version decided: every answer names it, and the case keeps it. */
async function version(d) {
  await d.run();
  await d.panel('runtime');
  await d.note('answerNames', { target: 'runtime:call', extra: 800 });
  await d.note('activeOne', { target: 'runtime:versions', extra: 400 });
  await d.panel(null);
  await d.note('onTheCase');
}

/** 4 — one click back to the version before. */
async function rollback(d) {
  seedTwoVersions(d.app);
  await d.panel('runtime');
  await d.note('rollbackButton', { target: 'runtime:rollback' });
  await d.rollback(MODEL);
  await d.note('rolledBack', { target: 'runtime:versions' });
  await d.panel(null);
  const result = await d.run({ fast: true });
  await d.note('rollbackResult', { target: 'el:' + RULE_TASK, quote: discountText(callOf(result)) });
  await d.tab(DMN);
  await d.note('rollbackFile', { target: DMN + '@side', extra: 800 });
  await d.tab('process');
}

export const tour = {
  chapters: [
    { id: 'save', play: save },
    { id: 'versions', play: versions },
    { id: 'version', play: version },
    { id: 'rollback', play: rollback }
  ]
};
