/**
 * The scenario of the lesson "Edit rules in the Portal", without any UI: the rules of the demo do not read the
 * customer of an invoice; in the Portal the rule owner adds the input customer and a key account rule; the same
 * process prices the same invoice lower. The Business Rule Task has to behave like the connector's
 * RuleTaskExecutor, which sends what the version that answers needs. The real runtime decides the same version in
 * RuleEngineContainerTest.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { parseDmn, requiredInputs, functionOf } from '../runtime/model/dmn.js';
import { DecisionRuntime } from '../runtime/model/decision-runtime.js';
import { ProcessRunner } from '../runtime/model/runner.js';
import { loadProcess } from '../runtime/model/process.js';
import { SAMPLE_INVOICE, MODEL } from '../features/shared/invoice.js';
import { withKeyAccount, TABLE } from '../features/shared/portal-rules.js';

const demo = name => fileURLToPath(new URL('../../../../rule-engine-demo/' + name, import.meta.url));
const text = name => readFileSync(demo(name), 'utf8');
const json = name => JSON.parse(text(name));
const DATA = 'dataclass/com/axonivy/utils/rule/engine/demo/';
const CLASSES = ['SalesInvoice', 'InvoiceLine', 'PricingResult', 'PricedLine', 'Discount', 'Shipping', 'InvoiceTotals', 'Approval'];
const VARIABLES = 'Variables:\n  RuleEngine:\n    Url: http://localhost:8090\n    ApiKey: ""\n    TimeoutSeconds: "10"\n    AutoDeploy: "true"\n';

function setup() {
  const model = loadProcess(json('process/InvoicePricing.p.json'), json(DATA + 'InvoicePricingData.d.json'),
    { file: 'InvoicePricing.p.json', project: 'rule-engine-demo', classes: CLASSES.map(name => json(DATA + name + '.d.json')) });
  const runtime = new DecisionRuntime();
  const file = parseDmn(text('dmn/invoice-pricing.dmn'), { model: MODEL, file: 'dmn/invoice-pricing.dmn' });
  const project = { find: name => (name === MODEL ? file : null), add: () => {} };
  const runner = new ProcessRunner({ model, runtime: () => runtime, project, io: {}, now: () => new Date(0) });
  return { runtime, file, run: () => runner.run({ variablesText: VARIABLES, params: { invoice: JSON.parse(JSON.stringify(SAMPLE_INVOICE)) } }) };
}

const callOf = result => result.ruleCalls[0];

test('portal: the rules of the project give the sample invoice no order discount', async () => {
  const env = setup();
  const result = await env.run();
  assert.deepEqual(result.data.pricing.orderDiscounts, []);
  assert.equal(result.data.pricing.totals.total, 18265.55);
  assert.ok(!callOf(result).mappings.some(m => m.name === 'customer'), 'the file does not read the customer');
});

test('portal: with the key account rule of the Portal, the same process prices the same invoice lower', async () => {
  const env = setup();
  await env.run();
  const portal = withKeyAccount(env.runtime.active(MODEL).dmn);
  assert.deepEqual(requiredInputs(portal, TABLE), ['customerTier', 'paymentTerms', 'promoCode', 'invoiceDate', 'customer']);
  env.runtime.register(MODEL, portal, { source: 'PORTAL', author: 'admin', activate: true });

  const result = await env.run();
  const call = callOf(result);
  assert.equal(call.version, 2);
  assert.deepEqual(call.mappings.find(m => m.name === 'customer'), { name: 'customer', expression: 'in.invoice.customer', value: 'Bluebird Architects' },
    'the input of the Portal version is found by name');
  const pricing = result.data.pricing;
  assert.deepEqual(pricing.orderDiscounts.map(discount => [discount.percent, discount.reason]), [[3, 'Key account']]);
  assert.equal(pricing.orderDiscountPercent, 3);
  assert.deepEqual(pricing.pricedLines.map(line => line.orderDiscount), [338.4, 76.5, 36, 41.4, 15.39], 'each line its share');
  assert.equal(pricing.totals.total, 17717.58);
  assert.equal(functionOf(env.file, 'lineDiscountTable').table.rules.length, 10, 'the file of the project is untouched');
});

test('portal: a version from the project would be replaced by the file, so the file decides what is sent', async () => {
  const env = setup();
  await env.run();
  env.runtime.register(MODEL, withKeyAccount(env.runtime.active(MODEL).dmn), { source: 'PROJECT', author: 'another project', activate: true });
  const result = await env.run();
  assert.ok(!callOf(result).mappings.some(m => m.name === 'customer'), 'only the inputs of the file');
});

test('portal: rolled back, the invoice is priced as before', async () => {
  const env = setup();
  await env.run();
  env.runtime.register(MODEL, withKeyAccount(env.runtime.active(MODEL).dmn), { source: 'PORTAL', author: 'admin', activate: true });
  assert.equal((await env.run()).data.pricing.totals.total, 17717.58);
  env.runtime.rollback(MODEL);
  const result = await env.run();
  assert.deepEqual([callOf(result).version, result.data.pricing.totals.total], [1, 18265.55]);
});
