/**
 * The numbers are those of RuleEngineContainerTest: the simulation has to decide what the real runtime
 * decides.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { parseDmn, decisionOf, addInput } from '../runtime/model/dmn.js';
import { DecisionRuntime } from '../runtime/model/decision-runtime.js';
import { ProcessRunner, RULE_ERRORS } from '../runtime/model/runner.js';
import { loadProcess, elementById, ruleConfig } from '../runtime/model/process.js';
import { SAMPLE_INVOICE, MODEL, DECISION } from '../features/shared/invoice.js';
import { extendedProcess, withApprover, APPROVER } from '../features/shared/approver.js';

const demo = name => fileURLToPath(new URL('../../../../rule-engine-demo/' + name, import.meta.url));
const text = name => readFileSync(demo(name), 'utf8');
const json = name => JSON.parse(text(name));
const DATA = 'dataclass/com/axonivy/utils/rule/engine/demo/';
const CLASSES = ['Invoice', 'InvoiceLine'];
const VARIABLES = 'Variables:\n  RuleEngine:\n    Url: http://localhost:8090\n    ApiKey: ""\n    TimeoutSeconds: "10"\n    AutoDeploy: "true"\n';

const SUM = 'f2';
const RULE_TASK = 'f4';
const APPLY = 'f6';

function setup({ approver = false } = {}) {
  const process = json('process/InvoicePricing.p.json');
  const classes = CLASSES.map(name => json(DATA + name + '.d.json'));
  const model = loadProcess(approver ? extendedProcess(process) : process, json(DATA + 'InvoicePricingData.d.json'),
    { file: 'InvoicePricing.p.json', project: 'rule-engine-demo', classes: approver ? withApprover(classes) : classes });
  const runtime = new DecisionRuntime();
  const files = [{ id: MODEL, current: parseDmn(text('dmn/invoice-discount.dmn'), { model: MODEL, file: 'dmn/invoice-discount.dmn' }) }];
  const project = {
    find: name => { const file = files.find(candidate => candidate.id === name); return file ? file.current : null; },
    add: dmn => files.push({ id: dmn.model, current: dmn })
  };
  const phases = [];
  const runner = new ProcessRunner({ model, runtime: () => runtime, project, io: { rule: phase => { phases.push(phase); } }, now: () => new Date(0) });
  const env = { model, runtime, files, runner, phases, variables: VARIABLES };
  env.invoice = (changes = {}, laptops = null) => {
    const invoice = { ...JSON.parse(JSON.stringify(SAMPLE_INVOICE)), ...changes };
    if (laptops !== null) invoice.lines[0].quantity = laptops;
    return invoice;
  };
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
const silver = dmn => decisionOf(dmn, DECISION).table.rules[5];
const decided = result => [result.data.invoice.discountPercent, result.data.invoice.discountReason, result.data.invoice.total].join(' ');

test('runner: the callable sums the lines, the table decides the discount, the process applies it', async () => {
  const env = setup();
  const result = await env.run();
  assert.equal(result.status, 'completed');
  assert.deepEqual(result.visited, ['f0', SUM, RULE_TASK, APPLY, 'f11']);
  assert.deepEqual(env.phases, ['request', 'registered', 'thinking', 'response', 'mapped', 'end']);
  const call = callOf(result);
  assert.deepEqual(call.mappings.map(m => [m.name, m.expression]), [['customerTier', 'in.invoice.customerTier'], ['orderAmount', 'in.invoice.orderAmount']]);
  assert.deepEqual(call.input, { customerTier: 'Silver', orderAmount: 9200 }, 'the order amount the Script summed');
  assert.equal(call.url, 'http://localhost:8090/api/models/invoice-discount/evaluate');
  assert.equal(call.decision, DECISION);
  assert.equal(call.version, 1);
  assert.deepEqual([call.rule.index, call.rule.outputs], [5, ['2', '"Silver customer"']], 'the rule that decided');

  const invoice = result.data.invoice;
  assert.equal(invoice.__type, 'com.axonivy.utils.rule.engine.demo.Invoice');
  assert.deepEqual(invoice.lines.map(line => line.amount), [4800, 1280, 720, 1200, 1200]);
  assert.deepEqual([invoice.orderAmount, invoice.discountPercent, invoice.discountReason], [9200, 2, 'Silver customer'],
    'the entries of the result, each to the field of its name');
  assert.deepEqual([invoice.discountAmount, invoice.total], [184, 9016]);
  assert.ok(!('discount' in invoice), 'a structure without a field of its own is unfolded');
  assert.ok(messages(result).includes("Rule engine: registered invoice-discount.dmn as version 1 of model 'invoice-discount' (ACTIVE)"));
  assert.ok(messages(result).includes('Invoice INV-2026-0042: 2% (Silver customer), total 9016'));
});

test('runner: other tiers and amounts, other rules', async () => {
  const env = setup();
  const of = async (customerTier, laptops) => decided(await env.run({ invoice: env.invoice({ customerTier }, laptops) }));
  assert.equal(await of('Standard', 4), '0 No discount 9200');
  assert.equal(await of('Gold', 4), '5 Gold customer 8740');
  assert.equal(await of('Platinum', 4), '8 Platinum customer 8464');
  assert.equal(await of('Silver', 5), '4 Silver, order of 10,000 or more 9984', 'a fifth laptop: 10,400');
  assert.equal(await of('Gold', 5), '8 Gold, order of 10,000 or more 9568');
});

test('runner: an unchanged file is registered once, a changed file is live with the next evaluation', async () => {
  const env = setup();
  await env.run();
  const again = await env.run();
  assert.deepEqual(env.phases, ['request', 'thinking', 'response', 'mapped', 'end']);
  assert.equal(callOf(again).version, 1);

  silver(env.file()).outputs[0] = '3';
  const changed = await env.run();
  assert.ok(env.phases.includes('registered'));
  assert.deepEqual([changed.data.invoice.discountPercent, changed.data.invoice.total, callOf(changed).version], [3, 8924, 2]);
  assert.deepEqual(env.runtime.versions(MODEL).map(v => [v.version, v.status, v.source]), [[1, 'RETIRED', 'PROJECT'], [2, 'ACTIVE', 'PROJECT']]);

  // a rollback on the runtime stays, the unchanged file is not sent again
  env.runtime.rollback(MODEL);
  const rolledBack = await env.run();
  assert.deepEqual([rolledBack.data.invoice.discountPercent, callOf(rolledBack).version], [2, 1]);
});

test('runner: a version from the Portal is the source of truth, the file of the project becomes a draft', async () => {
  const env = setup();
  await env.run();
  const portal = JSON.parse(JSON.stringify(env.file()));
  silver(portal).outputs[0] = '4';
  env.runtime.register(MODEL, portal, { source: 'PORTAL', author: 'admin', activate: true });
  silver(env.file()).outputs[0] = '3';
  const result = await env.run();
  assert.equal(result.data.invoice.discountPercent, 4, 'the rule owner decides');
  assert.deepEqual(env.runtime.versions(MODEL).map(v => v.status + ':' + v.source), ['RETIRED:PROJECT', 'ACTIVE:PORTAL', 'DRAFT:PROJECT']);
  assert.ok(messages(result).some(message => /is not active \(DRAFT\)/.test(message)));
});

test('runner: runtime stopped, wrong URL and timeout throw BPMN errors the boundary event catches', async () => {
  const env = setup();
  env.runtime.state = 'stopped';
  const stopped = await env.run();
  assert.deepEqual([stopped.status, callOf(stopped).errorCode], ['completed', RULE_ERRORS.UNREACHABLE]);
  assert.deepEqual(stopped.visited.slice(-2), ['f8', 'f10'], 'caught, on to "not priced"');
  assert.equal(stopped.data.errorCode, RULE_ERRORS.UNREACHABLE, 'the boundary event stores the error for the page');

  env.runtime.state = 'running';
  env.variables = VARIABLES.replace('8090', '8091');
  assert.equal(callOf(await env.run()).errorCode, RULE_ERRORS.UNREACHABLE);

  env.variables = VARIABLES.replace('"10"', '"2"');
  env.runtime.state = 'slow';
  const slow = await env.run();
  assert.equal(callOf(slow).errorCode, RULE_ERRORS.TIMEOUT);
  assert.match(callOf(slow).error, /2 seconds/);
});

test('runner: an empty field is sent as null; what does not fit is rule:engine:mapping', async () => {
  const env = setup();
  const noTier = await env.run({ invoice: env.invoice({ customerTier: null }) });
  assert.equal(callOf(noTier).input.customerTier, null, 'sent all the same');
  assert.equal(decided(noTier), '0 No discount 9200', 'only - matches null: the last rule decides');

  addInput(env.file(), DECISION, { name: 'creditLimit', type: 'number' });
  const noField = await env.run();
  assert.equal(callOf(noField).errorCode, RULE_ERRORS.MAPPING);
  assert.equal(callOf(noField).error, "No process data field found for the input(s) [creditLimit] of the decision 'discount' in model 'invoice-discount'. "
    + 'The process data needs a field named like each input, e.g. in.creditLimit, set before the Business Rule Task.');

  env.config(RULE_TASK).decision = ['invoice-discount#bonus'];
  assert.match(callOf(await env.run()).error, /has no decision 'bonus'/);
  env.config(RULE_TASK).decision = [];
  assert.match(callOf(await env.run()).error, /No decision selected/);
});

test('runner: "New DMN" generates the file once, then evaluates it like any other', async () => {
  const env = setup({ approver: true });
  const first = await env.run();
  assert.equal(first.status, 'completed');
  assert.deepEqual(first.visited, ['f0', SUM, RULE_TASK, APPLY, APPROVER.CREATE_TASK, 'f11']);
  assert.deepEqual(env.files.map(file => file.id), [MODEL, 'invoice-approver']);
  const generated = env.files[1].current;
  assert.deepEqual(generated.inputs.map(input => [input.name, input.type]), [['total', 'number'], ['discountPercent', 'number']]);
  assert.equal(generated.decisions[0].name, 'approver');
  const call = first.ruleCalls[1];
  assert.deepEqual(call.mappings.map(m => [m.expression, m.value]), [['in.invoice.total', 9016], ['in.invoice.discountPercent', 2]]);
  assert.equal(first.data.invoice.approver, '', 'the placeholder rule');
  assert.equal(first.data.invoice.total, 9016, 'the discount before it is the one of the demo');

  generated.decisions[0].table.rules.unshift({ inputs: ['>= 5000', '-'], outputs: ['"Sales manager"'], annotation: '' });
  const second = await env.run();
  assert.equal(second.data.invoice.approver, 'Sales manager');
  assert.equal(env.runtime.versions('invoice-approver').length, 2, 'the changed file is version 2');

  env.config(APPROVER.CREATE_TASK).newModel = MODEL;
  const taken = await env.run();
  assert.match(taken.ruleCalls[1].error, /A DMN named 'invoice-discount' exists already/);
});
