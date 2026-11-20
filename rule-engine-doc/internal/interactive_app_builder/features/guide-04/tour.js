/**
 * Guide 04 — the walkthrough. Five chapters, one per section of the page, each starting from the project as
 * the tutorial ships it; the notes are in text.js. See runtime/ui/tour.js for what the director `d` can do.
 * The runs play at the normal speed: they are what the answers are about.
 */
import { ELEMENTS, MODEL, DECISION, discountText, ruleText, errorText } from '../shared/project.js';
import { addInput } from '../../runtime/model/dmn.js';

const { SUM, RULE_TASK, BOUNDARY } = ELEMENTS;
const DMN = 'dmn:' + MODEL;

const field = (element, key) => 'field:' + element + '.' + key;

/** A rule author adds the input creditLimit to the table of the project file; the process data has no such field. */
function addCreditLimit(app) {
  const file = app.dmnFiles.find(candidate => candidate.id === MODEL);
  addInput(file.current, DECISION, { name: 'creditLimit', type: 'number' });
  app.renderDmn();
  app.renderTabs();
  app.inscription.validate();
}

/** Which fields do I have to fill in? Only the decision. */
async function decision(d) {
  await d.open(RULE_TASK, 'configuration');
  await d.note('decisionField', { target: field(RULE_TASK, 'decision'), extra: 600 });
  await d.note('fieldsEmpty', { target: field(RULE_TASK, 'newDmnGroup') });
  await d.close();
  await d.run({ on: { 'rule:mapped': ({ call }) => d.note('enough', { target: 'el:' + RULE_TASK, quote: discountText(call) }) } });
}

/**
 * How does the element find a field by name? The search, step by step: the names the model needs,
 * level 0 (the fields of in), level 1 (inside invoice), what is sent, and where the results go.
 */
async function byName(d) {
  await d.tab(DMN);
  await d.note('namesToFind', { target: DMN + '@inputs', extra: 600 });
  await d.tab('process');
  await d.run({
    on: {
      // the invoice is there, the element is about to look for the fields
      ['enter:' + RULE_TASK]: async () => {
        await d.panel('data');
        await d.note('levelZero', { target: 'data:invoice', extra: 800 });
        await d.note('levelOne', { target: 'data:invoice', extra: 800 });
        await d.panel(null);
      },
      'rule:request': async () => {
        await d.panel('runtime');
        await d.note('foundIn', { target: 'runtime:call', extra: 1200 });
        await d.panel(null);
      },
      'rule:mapped': async () => {
        await d.panel('data');
        await d.note('resultsBack', { target: 'data:invoice', extra: 800 });
        await d.panel(null);
      }
    }
  });
  await d.note('closestWins');
  await d.note('sameLevel', { extra: 800 });
  await d.note('caseAndBlanks');
}

/** What is sent when a field is empty? null, and only - matches it. */
async function nullValue(d) {
  const code = field(SUM, 'code');
  await d.open(SUM, 'output');
  d.focus(code + '@in.invoice.orderAmount = orderAmount');
  await d.wait(400);
  await d.edit(SUM + '.code', text => text + '\nin.invoice.customerTier = null;');
  await d.note('noTier', { target: code + '@customerTier', wait: 1500 });
  await d.close();
  await d.run({
    on: {
      'rule:request': async () => {
        await d.panel('runtime');
        await d.note('sentNull', { target: 'runtime:call', extra: 1200 });
        await d.panel(null);
      },
      'rule:mapped': ({ call }) => d.note('dashMatches', { target: 'el:' + RULE_TASK, quote: ruleText(call) + '\n' + discountText(call) })
    }
  });
  await d.note('noMatch');
  await d.note('allowedValues');
}

/** What happens when an input has no field? */
async function noField(d) {
  addCreditLimit(d.app);
  await d.tab(DMN);
  await d.note('newInput', { target: DMN + '@inputs', extra: 600 });
  await d.tab('process');
  await d.run({
    on: {
      'rule:failed': ({ call }) => d.note('mappingError', { quote: errorText(call) }),
      ['enter:' + BOUNDARY]: () => d.note('caught', { target: 'el:' + BOUNDARY })
    }
  });
  await d.note('waysOut', { target: 'el:' + SUM });
}

/** How do I prepare a value for the decision? In the Script before the element. */
async function script(d) {
  const code = field(SUM, 'code');
  await d.note('scriptBefore', { target: 'el:' + SUM });
  await d.open(SUM, 'output');
  await d.note('loop', { target: code + '@for', extra: 600 });
  await d.note('sumSet', { target: code + '@in.invoice.orderAmount = orderAmount', extra: 600 });
  await d.close();
  await d.run({
    on: {
      'rule:request': async () => {
        await d.panel('runtime');
        await d.note('preparedSent', { target: 'runtime:call', extra: 1200 });
        await d.panel(null);
      },
      'rule:mapped': ({ call }) => d.note('preparedResult', { target: 'el:' + RULE_TASK, quote: discountText(call) })
    }
  });
  await d.note('otherName');
}

export const tour = {
  chapters: [
    { id: 'decision', play: decision },
    { id: 'byName', play: byName },
    { id: 'nullValue', play: nullValue },
    { id: 'noField', play: noField },
    { id: 'script', play: script }
  ]
};
