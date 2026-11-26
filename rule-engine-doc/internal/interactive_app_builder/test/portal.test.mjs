import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { parseDmn, requiredInputs, decisionOf } from '../runtime/model/dmn.js';
import { DecisionRuntime } from '../runtime/model/decision-runtime.js';
import { ProcessRunner } from '../runtime/model/runner.js';
import { loadProcess } from '../runtime/model/process.js';
import { SAMPLE_INVOICE, MODEL } from '../features/shared/invoice.js';
import { withKeyAccount, TABLE } from '../features/shared/portal-rules.js';

const demo = name => fileURLToPath(new URL('../../../../rule-engine-demo/' + name, import.meta.url));
const text = name => readFileSync(demo(name), 'utf8');
const json = name => JSON.parse(text(name));
const DATA = 'dataclass/com/axonivy/utils/rule/engine/demo/';
const CLASSES = ['Invoice', 'InvoiceLine'];
const VARIABLES = 'Variables:\n  RuleEngine:\n    Url: http://localhost:8090\n    ApiKey: ""\n    TimeoutSeconds: "10"\n    AutoDeploy: "true"\n';

function setup() {
  const model = loadProcess(json('process/InvoicePricing.p.json'), json(DATA + 'InvoicePricingData.d.json'),
    { file: 'InvoicePricing.p.json', project: 'rule-engine-demo', classes: CLASSES.map(name => json(DATA + name + '.d.json')) });
  const runtime = new DecisionRuntime();
  const file = parseDmn(text('dmn/invoice-discount.dmn'), { model: MODEL, file: 'dmn/invoice-discount.dmn' });
  const project = { find: name => (name === MODEL ? file : null), add: () => {} };
  const runner = new ProcessRunner({ model, runtime: () => runtime, project, io: {}, now: () => new Date(0) });
  const run = (changes = {}) => runner.run({ variablesText: VARIABLES, params: { invoice: { ...JSON.parse(JSON.stringify(SAMPLE_INVOICE)), ...changes } } });
  return { runtime, file, run };
}

const callOf = result => result.ruleCalls[0];

test('portal: the rules of the project give the sample invoice the discount of its tier', async () => {
  const env = setup();
  const result = await env.run();
  assert.deepEqual([result.data.invoice.discountReason, result.data.invoice.total], ['Silver customer', 9016]);
  assert.ok(!callOf(result).mappings.some(m => m.name === 'customer'), 'the file does not read the customer');
});

test('portal: with the key account rule of the Portal, the same process gives the same invoice more', async () => {
  const env = setup();
  await env.run();
  const portal = withKeyAccount(env.runtime.active(MODEL).dmn);
  assert.deepEqual(requiredInputs(portal, TABLE), ['customerTier', 'orderAmount', 'customer']);
  assert.deepEqual(decisionOf(portal, TABLE).table.rules[0].inputs, ['-', '-', '"Bluebird Architects"'], 'at the top: FIRST');
  env.runtime.register(MODEL, portal, { source: 'PORTAL', author: 'admin', activate: true });

  const result = await env.run();
  const call = callOf(result);
  assert.equal(call.version, 2);
  assert.deepEqual(call.mappings.find(m => m.name === 'customer'), { name: 'customer', expression: 'in.invoice.customer', value: 'Bluebird Architects' },
    'the input of the Portal version is found by name');
  const invoice = result.data.invoice;
  assert.deepEqual([invoice.discountPercent, invoice.discountReason, invoice.discountAmount, invoice.total], [10, 'Key account: framework contract', 920, 8280]);
  assert.equal(decisionOf(env.file, TABLE).table.rules.length, 7, 'the file of the project is untouched');

  const other = await env.run({ customer: 'Another customer' });
  assert.equal(other.data.invoice.discountReason, 'Silver customer', 'only the key account');
});

test('portal: a version from the project would be replaced by the file, so the file decides what is sent', async () => {
  const env = setup();
  await env.run();
  env.runtime.register(MODEL, withKeyAccount(env.runtime.active(MODEL).dmn), { source: 'PROJECT', author: 'another project', activate: true });
  const result = await env.run();
  assert.ok(!callOf(result).mappings.some(m => m.name === 'customer'), 'only the inputs of the file');
});

test('portal: rolled back, the invoice gets the discount of before', async () => {
  const env = setup();
  await env.run();
  env.runtime.register(MODEL, withKeyAccount(env.runtime.active(MODEL).dmn), { source: 'PORTAL', author: 'admin', activate: true });
  assert.equal((await env.run()).data.invoice.total, 8280);
  env.runtime.rollback(MODEL);
  const result = await env.run();
  assert.deepEqual([callOf(result).version, result.data.invoice.total], [1, 9016]);
});
