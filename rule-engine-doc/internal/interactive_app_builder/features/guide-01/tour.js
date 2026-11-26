import { MODEL, DECISION } from '../shared/project.js';

const DMN = 'dmn:' + MODEL;
const AT = DMN + '@' + DECISION + '/';
const CELL = DMN + ':' + DECISION + '.';
const TRY = DMN + ':try.';

async function facts(d, customerTier, orderAmount) {
  await d.edit(TRY + 'customerTier', customerTier);
  await d.edit(TRY + 'orderAmount', String(orderAmount));
}

async function read(d) {
  await d.tab(DMN);
  await d.note('table', { target: AT + 'head', extra: 600 });
  await d.note('rule', { target: AT + 'rule6', extra: 600 });
  await d.note('output', { target: AT + 'r6.o2' });
  await d.note('dash', { target: AT + 'r6.i2' });
  await d.note('model', { target: DMN + '@inputs' });
}

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

async function first(d) {
  await d.tab(DMN);
  await facts(d, 'Silver', 12000);
  await d.note('several', { target: AT + 'rule5', extra: 800 });
  await d.note('policy', { target: AT + 'policy' });
  await d.note('order', { target: AT + 'rule6' });
  await d.note('catchAll', { target: AT + 'rule7' });
}

async function conditions(d) {
  await d.tab(DMN);
  await d.note('text', { target: AT + 'r6.i1' });
  await d.note('compare', { target: AT + 'r5.i2' });
  await facts(d, 'Silver', 9999);
  await d.note('below', { target: DMN + '@tryResult', extra: 600 });
  await d.edit(TRY + 'orderAmount', '10000');
  await d.note('from', { target: DMN + '@tryResult', extra: 600 });
}

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
