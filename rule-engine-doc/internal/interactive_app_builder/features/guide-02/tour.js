/**
 * Guide 02 — the walkthrough of the architecture. Four chapters on the tab "Architecture";
 * the notes are in text.js. See runtime/ui/tour.js for what the director `d` can do, and
 * runtime/ui/architecture.js for the blocks and links it can light.
 */

import { MODEL, SAMPLE_INVOICE } from '../shared/project.js';
import { toJson } from '../../runtime/model/mapping.js';

/** What the demo process has in its process data when it reaches the Business Rule Task: the inputs of the model. */
const INPUT_NAMES = ['customerTier', 'country', 'paymentTerms', 'promoCode', 'expressDelivery', 'invoiceDate', 'lines'];
const INPUT = Object.fromEntries(INPUT_NAMES.map(name => [name, toJson(SAMPLE_INVOICE[name])]));

const lines = values => Object.keys(values).map(name => name + ' = '
  + (Array.isArray(values[name]) ? '[' + values[name].length + ' lines]' : JSON.stringify(values[name]))).join('\n');

/** 1 — two sides and one connection. */
async function parts(d) {
  await d.tab('arch');
  d.arch({ lit: ['ivy'] });
  await d.note('ivy', { target: 'arch:ivy' });
  d.arch({ lit: ['container'] });
  await d.note('container', { target: 'arch:container' });
  d.arch({ lit: ['connector', 'api'], flow: ['http>', 'http<'] });
  await d.note('http', { target: 'arch:http' });
  d.arch({ lit: ['ivy', 'container', 'http'] });
  await d.note('why', { target: 'arch:container' });
  d.arch(null);
}

/** 2 — the three Ivy projects and what each is for, and the Portal beside them. */
async function projects(d) {
  await d.tab('arch');
  d.arch({ lit: ['demo'] });
  await d.note('demo', { target: 'arch:demo' });
  d.arch({ lit: ['connector'] });
  await d.note('connector', { target: 'arch:connector' });
  d.arch({ lit: ['core'] });
  await d.note('core', { target: 'arch:core' });
  d.arch({ lit: ['core', 'shared'], flow: ['code'] });
  await d.note('shared', { target: 'arch:shared' });
  d.arch({ lit: ['demo', 'connector', 'core'] });
  await d.note('requires', { target: 'arch:demo' });
  d.arch({ lit: ['portal', 'connector'], flow: ['callable>'] });
  await d.note('portal', { target: 'arch:portal' });
  d.arch(null);
}

/** 3 — what is in the image the container runs. */
async function image(d) {
  await d.tab('arch');
  d.arch({ lit: ['container'] });
  await d.note('image', { target: 'arch:container' });
  d.arch({ lit: ['api'] });
  await d.note('api', { target: 'arch:api' });
  d.arch({ lit: ['engine'] });
  await d.note('engine', { target: 'arch:engine' });
  d.arch({ lit: ['db', 'volume'], flow: ['store>'] });
  await d.note('db', { target: 'arch:db' });
  d.arch({ lit: ['editor', 'browser'], flow: ['ui>'] });
  await d.note('editor', { target: 'arch:editor' });
  d.arch(null);
}

/** 4 — one decision on its way through the blocks, then the same run on the canvas. */
async function flow(d) {
  const { app } = d;
  await d.tab('arch');
  d.arch({ lit: ['demo'] });
  await d.note('flowProcess', { target: 'arch:demo', quote: lines(INPUT) });
  d.arch({ lit: ['demo', 'connector'] });
  await d.note('flowConnector', { target: 'arch:connector' });

  d.arch({ lit: ['connector', 'api'], flow: ['http>'], label: { http: 'POST …/' + MODEL + '/versions' } });
  await d.note('flowRegister', { target: 'arch:http' });
  // the simulated runtime does what the container does with the file
  app.runtime.register(MODEL, app.findDmn(MODEL), { source: 'PROJECT', author: 'rule-engine-demo' });
  app.refreshRuntimeViews();
  d.arch({ lit: ['api', 'engine', 'db', 'volume'], flow: ['store>'] });
  await d.note('flowStored', { target: 'arch:db' });

  d.arch({ lit: ['connector', 'api'], flow: ['http>'], label: { http: 'POST …/' + MODEL + '/evaluate' } });
  await d.note('flowEvaluate', { target: 'arch:http', quote: lines(INPUT) });
  const answer = app.runtime.evaluate(MODEL, null, INPUT);
  const table = answer.dmn.functions.find(fn => fn.name === 'lineDiscountTable').table;
  const first = answer.calls.lineDiscountTable[0];
  const rule = table.rules[first.matched[0]];
  const rules = answer.calls.lineDiscountTable.map((made, n) => ({ table: 'lineDiscountTable', call: n + 1, index: made.matched[0] }));
  app.arch.setCall({ model: MODEL, decision: null, results: answer.results, version: answer.version, rule: null, rules });
  d.arch({ lit: ['api', 'engine', 'db'] });
  await d.note('flowDecide', { target: 'arch:engine', quote: 'Line 1, rule ' + (first.matched[0] + 1) + ':  ' + rule.inputs.slice(0, 2).join('  |  ') + '  →  ' + rule.outputs[0] + ' %' });

  const total = answer.results.totals.total;
  d.arch({ lit: ['connector', 'api'], flow: ['http<'], label: { http: '{ "results": { "pricedLines": [5], "totals": { "total": ' + total + ' }, … }, "version": ' + answer.version + ' }' } });
  await d.note('flowResult', { target: 'arch:http' });
  d.arch({ lit: ['connector', 'demo'] });
  await d.note('flowWritten', {
    target: 'arch:demo',
    quote: ['in.pricing.totals.total = ' + total, 'case: ruleVersion_' + MODEL + ' = "' + answer.version + '"'].join('\n')
  });
  d.arch(null);

  await d.tab('process');
  await d.run({ fast: true, on: { 'rule:request': () => d.note('designer') } });
}

export const tour = {
  chapters: [
    { id: 'parts', play: parts },
    { id: 'projects', play: projects },
    { id: 'image', play: image },
    { id: 'flow', play: flow }
  ]
};
