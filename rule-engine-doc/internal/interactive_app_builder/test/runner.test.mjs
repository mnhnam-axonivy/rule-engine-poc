/**
 * The demo process, the callable InvoicePricing, run by the simulation without any UI: the Business Rule Task has
 * to behave like the connector's RuleTaskExecutor, and the model has to decide what the real runtime decides
 * (the numbers are those of RuleEngineContainerTest).
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { parseDmn, functionOf, addInput } from '../runtime/model/dmn.js';
import { DecisionRuntime } from '../runtime/model/decision-runtime.js';
import { ProcessRunner, RULE_ERRORS } from '../runtime/model/runner.js';
import { loadProcess, elementById, ruleConfig } from '../runtime/model/process.js';
import { SAMPLE_INVOICE, MODEL } from '../features/shared/invoice.js';
import { extendedProcess, withDeliveryDays, DELIVERY } from '../features/shared/delivery.js';

const demo = name => fileURLToPath(new URL('../../../../rule-engine-demo/' + name, import.meta.url));
const text = name => readFileSync(demo(name), 'utf8');
const json = name => JSON.parse(text(name));
const DATA = 'dataclass/com/axonivy/utils/rule/engine/demo/';
const CLASSES = ['SalesInvoice', 'InvoiceLine', 'PricingResult', 'PricedLine', 'Discount', 'Shipping', 'InvoiceTotals', 'Approval'];
const VARIABLES = 'Variables:\n  RuleEngine:\n    Url: http://localhost:8090\n    ApiKey: ""\n    TimeoutSeconds: "10"\n    AutoDeploy: "true"\n';

const PREPARE = 'f2';
const RULE_TASK = 'f4';

function setup({ delivery = false } = {}) {
  const process = json('process/InvoicePricing.p.json');
  const classes = CLASSES.map(name => json(DATA + name + '.d.json'));
  const model = loadProcess(delivery ? extendedProcess(process) : process, json(DATA + 'InvoicePricingData.d.json'),
    { file: 'InvoicePricing.p.json', project: 'rule-engine-demo', classes: delivery ? withDeliveryDays(classes) : classes });
  const runtime = new DecisionRuntime();
  const files = [{ id: MODEL, current: parseDmn(text('dmn/invoice-pricing.dmn'), { model: MODEL, file: 'dmn/invoice-pricing.dmn' }) }];
  const project = {
    find: name => { const file = files.find(candidate => candidate.id === name); return file ? file.current : null; },
    add: dmn => files.push({ id: dmn.model, current: dmn })
  };
  const phases = [];
  const runner = new ProcessRunner({ model, runtime: () => runtime, project, io: { rule: phase => { phases.push(phase); } }, now: () => new Date(0) });
  const env = { model, runtime, files, runner, phases, variables: VARIABLES };
  env.invoice = changes => ({ ...JSON.parse(JSON.stringify(SAMPLE_INVOICE)), ...changes });
  env.run = (options = {}) => {
    phases.length = 0;
    return runner.run({ variablesText: env.variables, params: { invoice: options.invoice || env.invoice() }, ...options });
  };
  env.config = id => ruleConfig(elementById(model, id));
  env.file = () => project.find(MODEL);
  return env;
}

const callOf = result => result.ruleCalls[0];
const messages = result => result.logs.map(entry => entry.message);
const laptop = result => result.data.pricing.pricedLines[0];

test('runner: the callable prices the invoice and writes every result by name', async () => {
  const env = setup();
  const result = await env.run();
  assert.equal(result.status, 'completed');
  assert.deepEqual(result.visited, ['f0', 'f2', 'f4', 'f6', 'f11']);
  assert.deepEqual(env.phases, ['request', 'registered', 'thinking', 'response', 'mapped', 'end']);
  const call = callOf(result);
  assert.deepEqual(call.mappings.map(m => [m.name, m.expression]), [
    ['customerTier', 'in.invoice.customerTier'], ['country', 'in.invoice.country'], ['paymentTerms', 'in.invoice.paymentTerms'],
    ['promoCode', 'in.invoice.promoCode'], ['expressDelivery', 'in.invoice.expressDelivery'], ['invoiceDate', 'in.invoice.invoiceDate'],
    ['lines', 'in.invoice.lines']]);
  assert.deepEqual(call.input.lines[0], { product: 'Laptop 14"', category: 'Hardware', quantity: 10, unitPrice: 1200 }, 'a line as a structure');
  assert.equal(call.url, 'http://localhost:8090/api/models/invoice-pricing/evaluate');
  assert.equal(call.decision, null, 'all decisions of the model');
  assert.equal(call.version, 1);
  assert.deepEqual(call.rules.filter(rule => rule.table === 'lineDiscountTable').map(rule => rule.index + 1), [6, 7, 3, 9, 2], 'the rule of each line');
  assert.deepEqual(call.matched.lineDiscountTable, [1, 2, 5, 6, 8], 'every rule of the function that hit, for the editor');

  const pricing = result.data.pricing;
  assert.equal(pricing.__type, 'com.axonivy.utils.rule.engine.demo.PricingResult');
  assert.equal(pricing.pricedLines.length, 5);
  assert.equal(pricing.pricedLines[0].__type, 'com.axonivy.utils.rule.engine.demo.PricedLine', 'a list of structures fills a list of data classes');
  assert.deepEqual(pricing.pricedLines.map(line => line.vat), [913.68, 206.55, 97.2, 111.78, 13.34]);
  assert.deepEqual(pricing.orderDiscounts, []);
  assert.deepEqual([pricing.totals.net, pricing.totals.vat, pricing.totals.total], [16923, 1342.55, 18265.55]);
  assert.deepEqual([pricing.approval.route, pricing.approval.reasons], ['Sales manager', ['A line discount of 15% or more']]);
  assert.equal(pricing.shipping.reason, 'Free delivery in CH from 500');
  assert.ok(!('approvalFlags' in pricing), 'a list without a field of its name is not stored');
  assert.ok(messages(result).includes("Rule engine: registered invoice-pricing.dmn as version 1 of model 'invoice-pricing' (ACTIVE)"));
  assert.ok(messages(result).includes('Invoice INV-2026-0042: total 18265.55, Sales manager'));
});

test('runner: an unchanged file is registered once, a changed file is live with the next evaluation', async () => {
  const env = setup();
  await env.run();
  const again = await env.run();
  assert.deepEqual(env.phases, ['request', 'thinking', 'response', 'mapped', 'end']);
  assert.equal(callOf(again).version, 1);

  functionOf(env.file(), 'lineDiscountTable').table.rules[5].outputs[0] = '8';
  const changed = await env.run();
  assert.ok(env.phases.includes('registered'));
  assert.deepEqual([laptop(changed).discountPercent, laptop(changed).net, callOf(changed).version], [8, 11040, 2]);
  assert.deepEqual(env.runtime.versions(MODEL).map(v => [v.version, v.status, v.source]), [[1, 'RETIRED', 'PROJECT'], [2, 'ACTIVE', 'PROJECT']]);

  // a rollback on the runtime stays, the unchanged file is not sent again
  env.runtime.rollback(MODEL);
  const rolledBack = await env.run();
  assert.deepEqual([laptop(rolledBack).discountPercent, callOf(rolledBack).version], [6, 1]);
});

test('runner: a version from the Portal is the source of truth, the file of the project becomes a draft', async () => {
  const env = setup();
  await env.run();
  const portal = JSON.parse(JSON.stringify(env.file()));
  functionOf(portal, 'lineDiscountTable').table.rules[5].outputs[0] = '10';
  env.runtime.register(MODEL, portal, { source: 'PORTAL', author: 'admin', activate: true });
  functionOf(env.file(), 'lineDiscountTable').table.rules[5].outputs[0] = '8';
  const result = await env.run();
  assert.equal(laptop(result).discountPercent, 10, 'the rule owner decides');
  assert.deepEqual(env.runtime.versions(MODEL).map(v => v.status + ':' + v.source), ['RETIRED:PROJECT', 'ACTIVE:PORTAL', 'DRAFT:PROJECT']);
  assert.ok(messages(result).some(message => /is not active \(DRAFT\)/.test(message)));
});

test('runner: other conditions, other rules; the Script before the element prepares the promo code', async () => {
  const env = setup();
  const germany = await env.run({ invoice: env.invoice({ country: 'DE' }) });
  assert.equal(germany.data.pricing.pricedLines[4].discountReason, 'Fixed book price in DE and AT: no discount');
  assert.deepEqual(germany.data.pricing.pricedLines.map(line => line.vatRate), [19, 19, 19, 19, 7]);
  assert.deepEqual([germany.data.pricing.totals.total, germany.data.pricing.approval.route], [20105.7, 'Finance director']);

  const stacked = await env.run({ invoice: env.invoice({ customerTier: 'Platinum', paymentTerms: 'Prepaid', promoCode: ' winter26 ' }) });
  assert.equal(callOf(stacked).input.promoCode, 'WINTER26', 'trimmed and in capitals before it is sent');
  assert.deepEqual(stacked.data.pricing.orderDiscounts.map(discount => discount.percent), [4, 2, 5]);
  assert.deepEqual([stacked.data.pricing.orderDiscountPercent, stacked.data.pricing.totals.total], [10, 15972]);

  const expired = await env.run({ invoice: env.invoice({ promoCode: 'WINTER26', invoiceDate: '2027-03-01' }) });
  assert.match(expired.data.pricing.orderDiscounts[0].reason, /only valid from 1 Nov 2026 to 31 Jan 2027/);
});

test('runner: runtime stopped, wrong URL and timeout throw BPMN errors the boundary event catches', async () => {
  const env = setup();
  env.runtime.state = 'stopped';
  const stopped = await env.run();
  assert.deepEqual([stopped.status, callOf(stopped).errorCode], ['completed', RULE_ERRORS.UNREACHABLE]);
  assert.deepEqual(stopped.visited.slice(-2), ['f8', 'f10'], 'caught, on to "not priced"');
  assert.equal(stopped.data.pricing.errorCode, RULE_ERRORS.UNREACHABLE, 'the boundary event stores the error for the page');

  env.runtime.state = 'running';
  env.variables = VARIABLES.replace('8090', '8091');
  assert.equal(callOf(await env.run()).errorCode, RULE_ERRORS.UNREACHABLE);

  env.variables = VARIABLES.replace('"10"', '"2"');
  env.runtime.state = 'slow';
  const slow = await env.run();
  assert.equal(callOf(slow).errorCode, RULE_ERRORS.TIMEOUT);
  assert.match(callOf(slow).error, /2 seconds/);
});

test('runner: what the runtime refuses is rule:engine:dmn, what does not fit is rule:engine:mapping', async () => {
  const env = setup();
  const noTier = await env.run({ invoice: env.invoice({ customerTier: null }) });
  assert.deepEqual([callOf(noTier).errorCode, callOf(noTier).runtimeCode], [RULE_ERRORS.DMN, 'EVALUATION_FAILED']);
  assert.match(callOf(noTier).error, /'customerTier' is not allowed by the declared type \(tCustomerTier\)/, 'null is not one of the allowed values');

  const noPromo = await env.run({ invoice: env.invoice({ promoCode: null }) });
  assert.equal(noPromo.data.pricing.totals.total, 18265.55, 'a text without allowed values takes null; the rules with - match');

  addInput(env.file(), 'approvalFlags', { name: 'creditLimit', type: 'number' });
  const noField = await env.run();
  assert.equal(callOf(noField).errorCode, RULE_ERRORS.MAPPING);
  assert.equal(callOf(noField).error, "No process data field found for the input(s) [creditLimit] of the model 'invoice-pricing'. "
    + 'The process data needs a field named like each input, e.g. in.creditLimit, set before the Business Rule Task.');

  env.config(RULE_TASK).decision = ['invoice-pricing#bonus'];
  assert.match(callOf(await env.run()).error, /has no decision 'bonus'/);
  env.config(RULE_TASK).decision = [];
  assert.match(callOf(await env.run()).error, /No decision selected/);
});

test('runner: "New DMN" generates the file once, then evaluates it like any other', async () => {
  const env = setup({ delivery: true });
  const first = await env.run();
  assert.equal(first.status, 'completed');
  assert.deepEqual(first.visited, ['f0', PREPARE, RULE_TASK, DELIVERY.CREATE_TASK, 'f6', 'f11']);
  assert.deepEqual(env.files.map(file => file.id), [MODEL, 'delivery-days']);
  const generated = env.files[1].current;
  assert.deepEqual(generated.inputs.map(input => [input.name, input.type]), [['country', 'string'], ['expressDelivery', 'boolean']]);
  assert.equal(generated.decisions[0].name, 'deliveryDays');
  const call = first.ruleCalls[1];
  assert.deepEqual(call.mappings.map(m => m.expression), ['in.invoice.country', 'in.invoice.expressDelivery']);
  assert.equal(first.data.pricing.deliveryDays, 0, 'the placeholder rule');
  assert.equal(first.data.pricing.totals.total, 18265.55, 'the pricing before it is the one of the demo');

  generated.decisions[0].table.rules.unshift({ inputs: ['"CH"', '-'], outputs: ['2'], annotation: 'Switzerland' });
  const second = await env.run();
  assert.equal(second.data.pricing.deliveryDays, 2);
  assert.equal(env.runtime.versions('delivery-days').length, 2, 'the changed file is version 2');

  env.config(DELIVERY.CREATE_TASK).newModel = 'invoice-pricing';
  const taken = await env.run();
  assert.match(taken.ruleCalls[1].error, /A DMN named 'invoice-pricing' exists already/);
});
