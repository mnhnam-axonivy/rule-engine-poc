/**
 * Guide 07 — the walkthrough. Six chapters, each starting from the project as the tutorial ships it;
 * the notes are in text.js. See runtime/ui/tour.js for what the director `d` can do, and
 * runtime/ui/portal.js for the parts of the Portal tab.
 */
import { ELEMENTS, MODEL, inputsText, discountText, invoiceText } from '../shared/project.js';
import { TABLE, NEW_INPUT, NEW_RULE, WHAT_CHANGED } from '../shared/portal-rules.js';
import { seedProjectVersion, seedPortalVersion, lastWord } from '../shared/portal-chapters.js';

const { RULE_TASK, APPLY } = ELEMENTS;
const DMN = 'dmn:' + MODEL;
/** The rule the rule owner adds: FIRST needs it at the top, above "Silver customer". */
const RULE = 1;

const key = column => TABLE + '.r' + RULE + '.' + column; // column: 'i3', 'o1' or 'n'

/** 1 — the rules of the project, which do not read the customer of the invoice. */
async function before(d) {
  await d.run({ fast: true, on: { 'rule:mapped': ({ call }) => d.note('noKeyAccount', { target: 'el:' + RULE_TASK, quote: discountText(call) }) } });
  await d.tab(DMN);
  await d.note('table', { target: DMN + '@' + TABLE + '/head' });
  await d.tab('process');
  await d.panel('data');
  await d.note('customer', { target: 'data:invoice', extra: 600 });
  await d.panel(null);
}

/** 2 — the tab in the Portal, and how the Portal finds the rule engine. */
async function portal(d) {
  seedProjectVersion(d.app);
  await d.tab('arch');
  d.arch({ lit: ['portal', 'connector'], flow: ['callable>'] });
  await d.note('bridge', { target: 'arch:portal', extra: 600 });
  d.arch(null);
  await d.tab('portal');
  await d.note('tab', { target: 'portal@tab' });
  await d.note('row', { target: 'portal@row:' + MODEL, extra: 400 });
  await d.click('portal@edit:' + MODEL);
  await d.note('editor', { target: 'portal@kie', extra: 400 });
}

/** 3 — one more input and one more rule, checked, saved and activated. */
async function edit(d) {
  seedProjectVersion(d.app);
  d.app.portal.open(MODEL);
  await d.tab('portal');
  await d.note('goal', { target: 'portal@kie' });
  await d.portalInput(NEW_INPUT[0], NEW_INPUT[1]);
  await d.note('inputs', { target: 'portal@inputs', extra: 600 });
  await d.click('portal@' + TABLE + '/above1');
  await d.note('newRule', { target: 'portal@' + TABLE + '/rule' + RULE });
  await d.edit('portal:' + key('i3'), NEW_RULE[0][2]);
  await d.edit('portal:' + key('o1'), NEW_RULE[1][0]);
  await d.edit('portal:' + key('o2'), NEW_RULE[1][1]);
  await d.edit('portal:' + key('n'), NEW_RULE[2]);
  await d.note('keyAccount', { target: 'portal@' + TABLE + '/rule' + RULE, extra: 600 });
  await d.note('first', { target: 'portal@' + TABLE + '/policy' });
  await d.click('portal@check');
  await d.note('checked', { target: 'portal@message' });
  await d.edit('portal:description', WHAT_CHANGED);
  await d.click('portal@activate');
  await d.note('saved', { target: 'portal@title', extra: 400 });
}

/** 4 — the same invoice, the same process, a lower price. */
async function after(d) {
  seedPortalVersion(d.app);
  await d.tab('process');
  await d.run({
    fast: true,
    on: {
      'rule:request': ({ call }) => d.note('moreInputs', { target: 'el:' + RULE_TASK, quote: inputsText(call) }),
      'rule:mapped': ({ call }) => d.note('applied', { target: 'el:' + RULE_TASK, quote: discountText(call) }),
      ['after:' + APPLY]: () => d.note('lower', { target: 'el:' + APPLY, quote: invoiceText(d.app.data.invoice) })
    }
  });
  await d.tab(DMN);
  await d.note('untouched', { target: DMN + '@' + TABLE + '/head' });
  await d.tab('process');
}

/** 5 — every version is kept; one click back. */
async function versions(d) {
  seedPortalVersion(d.app);
  await d.tab('portal');
  await d.click('portal@versions:' + MODEL);
  await d.note('versionList', { target: 'portal@dialog', extra: 600 });
  await d.click('portal@rollback');
  await d.note('rolledBack', { target: 'portal@v1' });
  await d.click('portal@close');
  await d.tab('process');
  await d.run({ fast: true, on: { 'rule:mapped': ({ call }) => d.note('backAgain', { target: 'el:' + RULE_TASK, quote: discountText(call) }) } });
}

export const tour = {
  chapters: [
    { id: 'before', play: before },
    { id: 'portal', play: portal },
    { id: 'edit', play: edit },
    { id: 'after', play: after },
    { id: 'versions', play: versions },
    // 6 — a changed file of the project does not take the rule owner's place
    { id: 'lastWord', play: lastWord }
  ]
};
