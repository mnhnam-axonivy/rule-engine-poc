/**
 * Guide 02 — the walkthrough of the architecture. Four chapters on the tab "Architecture";
 * the notes are in text.js. See runtime/ui/tour.js for what the director `d` can do, and
 * runtime/ui/architecture.js for the blocks and links it can light.
 */

import { MODEL, DECISION } from '../shared/project.js';

/** What the demo process has in its process data when it reaches the Business Rule Task: the inputs of the model. */
const INPUT = { customerTier: 'Silver', orderAmount: 9200 };

const lines = values => Object.keys(values).map(name => name + ' = ' + JSON.stringify(values[name])).join('\n');

/** 1 — two sides and one connection. */
async function parts(d) {
  await d.tab('arch');
  d.arch({ lit: ['ivy'] });
  await d.note('ivy', { target: 'arch:ivy' });
  d.arch({ lit: ['container'] });
  await d.note('container', { target: 'arch:container' });
  d.arch({ lit: ['connector', 'engine'], flow: ['http>', 'http<'] });
  await d.note('http', { target: 'arch:http' });
  d.arch({ lit: ['ivy', 'container', 'http'] });
  await d.note('why', { target: 'arch:container' });
  d.arch(null);
}

/** 2 — the Ivy projects and what each is for, and the Portal beside them. */
async function projects(d) {
  await d.tab('arch');
  d.arch({ lit: ['demo'] });
  await d.note('demo', { target: 'arch:demo' });
  d.arch({ lit: ['connector', 'files'] });
  await d.note('connector', { target: 'arch:connector' });
  d.arch({ lit: ['demo', 'connector'] });
  await d.note('requires', { target: 'arch:demo' });
  d.arch({ lit: ['portal', 'connector'], flow: ['callable>'] });
  await d.note('portal', { target: 'arch:portal' });
  d.arch({ lit: ['editor', 'browser'], flow: ['ui>'] });
  await d.note('editor', { target: 'arch:editor' });
  d.arch(null);
}

/** 3 — what is in the image the container runs. */
async function image(d) {
  await d.tab('arch');
  d.arch({ lit: ['container'] });
  await d.note('image', { target: 'arch:container' });
  d.arch({ lit: ['engine'] });
  await d.note('engine', { target: 'arch:engine' });
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

  d.arch({ lit: ['connector', 'engine'], flow: ['http>'], label: { http: 'POST /jitdmn/validate' } });
  await d.note('flowRegister', { target: 'arch:http' });
  // the simulation stores the file, as the connector does once the DMN engine accepted it
  app.runtime.register(MODEL, app.findDmn(MODEL), { source: 'PROJECT', author: 'rule-engine-demo' });
  app.refreshRuntimeViews();
  d.arch({ lit: ['connector', 'files'] });
  await d.note('flowStored', { target: 'arch:files' });

  d.arch({ lit: ['connector', 'engine'], flow: ['http>'], label: { http: 'POST /jitdmn/dmnresult' } });
  await d.note('flowEvaluate', { target: 'arch:http', quote: lines(INPUT) });
  const answer = app.runtime.evaluate(MODEL, DECISION, INPUT);
  const table = answer.dmn.decisions.find(decision => decision.name === DECISION).table;
  const index = answer.matched[DECISION][0];
  const rule = { index, inputs: table.rules[index].inputs, outputs: table.rules[index].outputs };
  app.arch.setCall({ model: MODEL, decision: DECISION, results: answer.results, version: answer.version, rule, rules: [] });
  app.refreshRuntimeViews();
  d.arch({ lit: ['engine'] });
  await d.note('flowDecide', { target: 'arch:engine', quote: 'rule ' + (index + 1) + ':  ' + rule.inputs.join('  |  ') + '  →  ' + rule.outputs.join(', ') });

  const result = answer.results[DECISION];
  d.arch({ lit: ['connector', 'engine'], flow: ['http<'], label: { http: '{ "decisionResults": [ { "decisionName": "discount", "result": { "discountPercent": ' + result.discountPercent + ', … } } ] }' } });
  await d.note('flowResult', { target: 'arch:http' });
  d.arch({ lit: ['connector', 'demo'] });
  await d.note('flowWritten', {
    target: 'arch:demo',
    quote: ['in.invoice.discountPercent = ' + result.discountPercent, 'case: ruleVersion_' + MODEL + ' = "' + answer.version + '"'].join('\n')
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
