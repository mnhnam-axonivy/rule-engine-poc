/**
 * Guide 11, part 3 — the walkthrough of the questions about rules that change. Five chapters, one per
 * question, each starting from the project as the tutorial ships it; the notes are in text.js. See
 * runtime/ui/tour.js for what the director `d` can do, and runtime/ui/portal.js for the parts of the
 * Portal tab. The runs play at the normal speed: they are what the answers are about.
 */
import { ELEMENTS, MODEL, laptopText, discountsText, errorText } from '../shared/project.js';
import { seedPortalVersion, raiseHardware, lastWord } from '../shared/portal-chapters.js';

const { RULE_TASK, BOUNDARY, NOT_PRICED } = ELEMENTS;

/** Do I have to change the process when a rule changes? */
async function noChange(d) {
  await d.run({ on: { 'rule:mapped': ({ call }) => d.note('before', { target: 'el:' + RULE_TASK, quote: laptopText(call) }) } });
  await raiseHardware(d, 'ruleChanged');
  await d.run({
    on: {
      'rule:registered': () => d.note('newVersion'),
      'rule:mapped': ({ call }) => d.note('after', { target: 'el:' + RULE_TASK, quote: laptopText(call) })
    }
  });
  await d.note('sameProcess', { target: 'el:' + RULE_TASK });
}

/** A rule owner added an input in the Portal. Does the process need a change? */
async function portalInput(d) {
  seedPortalVersion(d.app);
  await d.tab('portal');
  await d.note('portalVersion', { target: 'portal@row:' + MODEL, extra: 600 });
  await d.tab('process');
  await d.run({
    on: {
      'rule:request': async () => {
        await d.panel('runtime');
        await d.note('inputsSent', { target: 'runtime:call', extra: 1200 });
        await d.panel(null);
      },
      'rule:mapped': ({ call }) => d.note('portalDecides', { target: 'el:' + RULE_TASK, quote: discountsText(call) })
    }
  });
  await d.note('unchanged', { target: 'el:' + RULE_TASK });
  await d.note('withoutField');
}

/** How do I go back to the version before? */
async function rollBack(d) {
  seedPortalVersion(d.app);
  await d.tab('portal');
  await d.click('portal@versions:' + MODEL);
  await d.note('versionList', { target: 'portal@dialog', extra: 600 });
  await d.note('rollbackButton', { target: 'portal@rollback' });
  await d.click('portal@rollback');
  await d.note('rolledBack', { target: 'portal@v1' });
  await d.click('portal@close');
  await d.tab('process');
  await d.run({ on: { 'rule:mapped': ({ call }) => d.note('backAgain', { target: 'el:' + RULE_TASK, quote: discountsText(call) }) } });
}

/** What happens when the decision runtime is down? */
async function down(d) {
  await d.panel('runtime');
  await d.runtime('stopped');
  await d.note('stopped', { target: 'runtime:state' });
  await d.panel(null);
  await d.run({
    on: {
      'rule:failed': ({ call }) => d.note('unreachable', { quote: errorText(call) }),
      ['enter:' + BOUNDARY]: () => d.note('boundary', { target: 'el:' + BOUNDARY }),
      ['enter:' + NOT_PRICED]: () => d.note('notPriced', { target: 'el:' + NOT_PRICED })
    }
  });
}

export const tour = {
  chapters: [
    { id: 'noChange', play: noChange },
    { id: 'portalInput', play: portalInput },
    // I changed the .dmn file, but the old result comes back. Why?
    { id: 'oldResult', play: lastWord },
    { id: 'rollBack', play: rollBack },
    { id: 'down', play: down }
  ]
};
