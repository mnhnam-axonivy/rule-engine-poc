/**
 * Guide 01 — the walkthrough. Five chapters on the editor of dmn/invoice-discount.dmn, on its table discount,
 * each starting from the project as the tutorial ships it; the notes are in text.js.
 * "Try it" beside the table evaluates it in the browser; its boxes are typed into like cells:
 * 'dmn:invoice-discount:try.<input>'. See runtime/ui/tour.js for what the director `d` can do.
 */
import { MODEL, DECISION } from '../shared/project.js';

const DMN = 'dmn:' + MODEL;
const AT = DMN + '@' + DECISION + '/'; // a part of the table: + rule6, r6.o1, policy, head
const CELL = DMN + ':' + DECISION + '.'; // a cell to type into: + r7.o1
const TRY = DMN + ':try.'; // a box of "Try it": + orderAmount

/** Types the facts of one invoice into "Try it", one box after the other. */
async function facts(d, customerTier, orderAmount) {
  await d.edit(TRY + 'customerTier', customerTier);
  await d.edit(TRY + 'orderAmount', String(orderAmount));
}

/** Read a decision table: inputs and outputs, a rule, the condition that matches everything. */
async function read(d) {
  await d.tab(DMN);
  await d.note('table', { target: AT + 'head', extra: 600 });
  await d.note('rule', { target: AT + 'rule6', extra: 600 });
  await d.note('output', { target: AT + 'r6.o2' });
  await d.note('dash', { target: AT + 'r6.i2' });
  await d.note('model', { target: DMN + '@inputs' });
}

/** Try other inputs: the same table, other facts, another answer. */
async function tryIt(d) {
  await d.tab(DMN);
  await d.note('tryStrip', { target: DMN + '@try' });
  await facts(d, 'Silver', 9200);
  await d.note('sample', { target: DMN + '@tryResult', extra: 600 });
  await facts(d, 'Gold', 9200);
  await d.note('gold', { target: DMN + '@tryResult', extra: 600 });
  await facts(d, 'Gold', 10400);
  await d.note('large', { target: DMN + '@tryResult', extra: 600 });
}

/** First match wins: several rules match, the hit policy picks one, and the order of the rules matters. */
async function first(d) {
  await d.tab(DMN);
  await facts(d, 'Silver', 12000);
  await d.note('several', { target: AT + 'rule5', extra: 800 });
  await d.note('policy', { target: AT + 'policy' });
  await d.note('order', { target: AT + 'rule6' });
  await d.note('catchAll', { target: AT + 'rule7' });
}

/** Conditions: a text, a comparison; and where a comparison ends. */
async function conditions(d) {
  await d.tab(DMN);
  await d.note('text', { target: AT + 'r6.i1' });
  await d.note('compare', { target: AT + 'r5.i2' });
  await facts(d, 'Silver', 9999);
  await d.note('below', { target: DMN + '@tryResult', extra: 600 });
  await d.edit(TRY + 'orderAmount', '10000');
  await d.note('from', { target: DMN + '@tryResult', extra: 600 });
}

/** Add a rule: above the rule that catches everything. */
async function add(d) {
  await d.tab(DMN);
  await facts(d, 'Standard', 12000);
  await d.note('nothing', { target: DMN + '@tryResult', extra: 400 });
  await d.click(AT + 'addRule');
  await d.note('added', { target: AT + 'rule7', extra: 400 });
  d.focus(AT + 'r7.i1');
  await d.edit(CELL + 'r7.i1', '"Standard"');
  d.focus(AT + 'r7.i2');
  await d.edit(CELL + 'r7.i2', '>= 10000');
  d.focus(AT + 'r7.o1');
  await d.edit(CELL + 'r7.o1', '1');
  d.focus(AT + 'r7.o2');
  await d.edit(CELL + 'r7.o2', '"Standard, order of 10,000 or more"');
  await d.note('written', { target: AT + 'rule7', wait: 1600 });
  await d.note('newResult', { target: DMN + '@tryResult', extra: 600 });
  await d.note('whereEdit');
}

export const tour = {
  chapters: [
    { id: 'read', play: read },
    { id: 'try', play: tryIt },
    { id: 'first', play: first },
    { id: 'conditions', play: conditions },
    { id: 'add', play: add }
  ]
};
