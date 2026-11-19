/**
 * Guide 01 — the walkthrough. Five chapters on the editor of dmn/invoice-pricing.dmn, on its table
 * lineDiscountTable, each starting from the project as the tutorial ships it; the notes are in text.js.
 * "Try it" beside the table evaluates it in the browser; its boxes are typed into like cells:
 * 'dmn:invoice-pricing:try.<input>'. See runtime/ui/tour.js for what the director `d` can do.
 */
import { MODEL } from '../shared/project.js';

const TABLE = 'lineDiscountTable';
const DMN = 'dmn:' + MODEL;
const AT = DMN + '@' + TABLE + '/'; // a part of the table: + rule6, r6.o1, policy, head
const CELL = DMN + ':' + TABLE + '.'; // a cell to type into: + r6.o1
const TRY = DMN + ':try.'; // a box of "Try it": + quantity

/** Types the facts of one line into "Try it", one box after the other. */
async function facts(d, category, quantity, customerTier, country) {
  await d.edit(TRY + 'category', category);
  await d.edit(TRY + 'quantity', String(quantity));
  await d.edit(TRY + 'customerTier', customerTier);
  await d.edit(TRY + 'country', country);
}

/** Read a decision table: inputs and outputs, a rule, the condition that matches everything, and a table as a function. */
async function read(d) {
  await d.tab(DMN);
  await d.note('table', { target: AT + 'head', extra: 600 });
  await d.note('rule', { target: AT + 'rule6', extra: 600 });
  await d.note('output', { target: AT + 'r6.o2' });
  await d.note('dash', { target: AT + 'r6.i3' });
  await d.note('function', { target: AT + 'head' });
}

/** Try other inputs: the same table, other facts, another answer. */
async function tryIt(d) {
  await d.tab(DMN);
  await d.note('tryStrip', { target: DMN + '@try' });
  await facts(d, 'Hardware', 10, 'Silver', 'CH');
  await d.note('laptops', { target: DMN + '@tryResult', extra: 600 });
  await facts(d, 'Hardware', 10, 'Gold', 'CH');
  await d.note('gold', { target: DMN + '@tryResult', extra: 600 });
  await facts(d, 'Hardware', 60, 'Silver', 'CH');
  await d.note('volume', { target: DMN + '@tryResult', extra: 600 });
}

/** First match wins: several rules match, the hit policy picks one, and the order of the rules matters. */
async function first(d) {
  await d.tab(DMN);
  await facts(d, 'Books', 12, 'Silver', 'DE');
  await d.note('several', { target: AT + 'rule1', extra: 800 });
  await d.note('policy', { target: AT + 'policy' });
  await d.note('order', { target: AT + 'rule2' });
  await d.note('catchAll', { target: AT + 'rule10' });
}

/** Conditions: a text, a list, a range, a comparison; and where a range ends. */
async function conditions(d) {
  await d.tab(DMN);
  await d.note('text', { target: AT + 'r1.i1' });
  await d.note('list', { target: AT + 'r1.i4' });
  await d.note('range', { target: AT + 'r6.i2' });
  await d.note('compare', { target: AT + 'r4.i2' });
  await facts(d, 'Software', 19, 'Silver', 'CH');
  await d.note('belowRange', { target: DMN + '@tryResult', extra: 600 });
  await d.edit(TRY + 'quantity', '20');
  await d.note('nextRange', { target: DMN + '@tryResult', extra: 600 });
}

/** Add a rule: above the rule that catches everything. */
async function add(d) {
  await d.tab(DMN);
  await facts(d, 'Consumables', 30, 'Silver', 'CH');
  await d.note('nothing', { target: DMN + '@tryResult', extra: 400 });
  await d.click(AT + 'addRule');
  await d.note('added', { target: AT + 'rule10', extra: 400 });
  d.focus(AT + 'r10.i1');
  await d.edit(CELL + 'r10.i1', '"Consumables"');
  d.focus(AT + 'r10.i2');
  await d.edit(CELL + 'r10.i2', '[20..50)');
  d.focus(AT + 'r10.o1');
  await d.edit(CELL + 'r10.o1', '4');
  d.focus(AT + 'r10.o2');
  await d.edit(CELL + 'r10.o2', '"Consumables: 20 or more"');
  await d.note('written', { target: AT + 'rule10', wait: 1600 });
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
