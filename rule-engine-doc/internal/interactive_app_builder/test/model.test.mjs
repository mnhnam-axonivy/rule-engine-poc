import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { parseXml, XmlError } from '../runtime/model/xml.js';
import { parseDmn, decisionOf, functionOf, logicsOf, requiredInputs, evaluationOrder, fingerprint, cloneDmn, DmnError, inputTypes, describeNames, describeOutputs } from '../runtime/model/dmn.js';
import { matches, literal, evaluateTable, evaluateExpression, validateCondition, validateResult, validateExpression, FeelError, FeelDate, roundTo } from '../runtime/model/feel.js';
import { DecisionRuntime, RuntimeError } from '../runtime/model/decision-runtime.js';
import { parseFieldSelection, uniqueNames, toFeelName, toFieldName, findField, typeOfPath, readPath, toJson, fromJson, MappingError } from '../runtime/model/mapping.js';
import { generateDmn, modelNameFor, readableByName, generatedFrom } from '../runtime/model/dmn-generator.js';
import { runScript, assignTo, validateScript, IvyScriptError } from '../runtime/model/ivyscript.js';
import { parseYaml } from '../runtime/model/yaml.js';
import { readSettings } from '../runtime/model/runner.js';
import { loadProcess } from '../runtime/model/process.js';

const demo = name => fileURLToPath(new URL('../../../../rule-engine-demo/' + name, import.meta.url));
const text = name => readFileSync(demo(name), 'utf8');
const json = name => JSON.parse(text(name));
const DATA = 'dataclass/com/axonivy/utils/rule/engine/demo/';

const fixture = name => readFileSync(fileURLToPath(new URL('./fixtures/' + name, import.meta.url)), 'utf8');
const CLASSES = ['Invoice', 'InvoiceLine'];
/**
 * A model with functions, a chain of decisions and the hit policies FIRST, UNIQUE and COLLECT: the invoice pricing
 * of an earlier demo, kept as a fixture to test the DMN engine of the simulation.
 */
const pricing = () => parseDmn(fixture('invoice-pricing.dmn'), { model: 'invoice-pricing', file: 'dmn/invoice-pricing.dmn' });
/** The model of the demo: one decision table. */
const demoModel = () => parseDmn(text('dmn/invoice-discount.dmn'), { model: 'invoice-discount', file: 'dmn/invoice-discount.dmn' });
const processModel = () => loadProcess(json('process/InvoicePricing.p.json'), json(DATA + 'InvoicePricingData.d.json'),
  { file: 'InvoicePricing.p.json', project: 'rule-engine-demo', classes: CLASSES.map(name => json(DATA + name + '.d.json')) });
const NS = 'com.axonivy.utils.rule.engine.demo.';
const lines = value => (Array.isArray(value) ? value.join('\n') : String(value || ''));
/** The inputs of the pricing model for its sample invoice. */
const SAMPLE = {
  customerTier: 'Silver', country: 'CH', paymentTerms: 'Net 30', promoCode: '', expressDelivery: false, invoiceDate: '2026-11-19',
  lines: [
    { product: 'Laptop 14"', category: 'Hardware', quantity: 10, unitPrice: 1200 },
    { product: 'Office suite licence, 1 year', category: 'Software', quantity: 25, unitPrice: 120 },
    { product: 'Installation and setup, hours', category: 'Services', quantity: 8, unitPrice: 150 },
    { product: 'A4 paper, box of 5 reams', category: 'Consumables', quantity: 60, unitPrice: 25 },
    { product: 'Project management handbook', category: 'Books', quantity: 12, unitPrice: 45 }]
};

// ── XML and DMN ──────────────────────────────────────────────────────────────

test('xml: elements, attributes, entities, comments and prefixes', () => {
  const root = parseXml('<?xml version="1.0"?>\n<!-- note -->\n<a:root x="1 &lt; 2" y=\'q\'><b>t &amp; u</b><c/><![CDATA[<raw>]]></a:root>');
  assert.equal(root.name, 'a:root');
  assert.equal(root.local, 'root');
  assert.deepEqual(root.attrs, { x: '1 < 2', y: 'q' });
  assert.equal(root.children[0].text, 't & u');
  assert.equal(root.children[1].local, 'c');
  assert.equal(root.text, '<raw>');
});

test('xml: what is not well-formed is reported', () => {
  assert.throws(() => parseXml('<a><b></a>'), XmlError);
  assert.throws(() => parseXml('<a>'), XmlError);
  assert.throws(() => parseXml('just text'), XmlError);
});

test('dmn: a model is read with its inputs, functions, decisions and rules', () => {
  const dmn = pricing();
  assert.equal(dmn.name, 'invoice-pricing');
  assert.deepEqual(dmn.inputs.map(i => [i.name, i.type]), [['customerTier', 'tCustomerTier'], ['country', 'tCountry'], ['paymentTerms', 'tPaymentTerms'],
    ['promoCode', 'string'], ['expressDelivery', 'boolean'], ['invoiceDate', 'date'], ['lines', 'tLines']]);
  assert.deepEqual(dmn.functions.map(fn => [fn.name, fn.kind, fn.params.map(p => p.name).join(',')]), [
    ['lineDiscountTable', 'table', 'category,quantity,customerTier,country'], ['vatRateTable', 'table', 'country,category'], ['taxFormula', 'literal', 'net,rate']]);
  assert.deepEqual(dmn.decisions.map(d => [d.name, d.kind]), [['orderDiscounts', 'table'], ['orderDiscountPercent', 'literal'], ['pricedLines', 'literal'],
    ['shipping', 'table'], ['totals', 'literal'], ['approvalFlags', 'table'], ['approval', 'literal']]);
  assert.deepEqual(logicsOf(dmn).slice(0, 3).map(item => item.name), ['lineDiscountTable', 'vatRateTable', 'taxFormula'], 'the editor shows the functions first');
  const table = functionOf(dmn, 'lineDiscountTable').table;
  assert.equal(table.hitPolicy, 'FIRST');
  assert.equal(table.rules.length, 10);
  assert.deepEqual(table.rules[5], { inputs: ['"Hardware"', '[10..50)', '-', '-'], outputs: ['6', '"Hardware: 10 units or more"'], annotation: '' });
  assert.equal(functionOf(dmn, 'taxFormula').expression, 'round half up(net * rate / 100, 2)');
  assert.deepEqual(decisionOf(dmn, 'pricedLines').requires.functions, ['_bkm_lineDiscountTable', '_bkm_vatRateTable', '_bkm_taxFormula']);
  assert.equal(dmn.types.tCategory.allowedValues, '"Hardware", "Software", "Services", "Consumables", "Books"');
});

test('dmn: a decision knows its inputs through other decisions, and the order they are evaluated in', () => {
  const dmn = pricing();
  assert.deepEqual(requiredInputs(dmn, 'orderDiscounts'), ['customerTier', 'paymentTerms', 'promoCode', 'invoiceDate']);
  assert.deepEqual(requiredInputs(dmn, 'approval').sort(), ['country', 'customerTier', 'expressDelivery', 'invoiceDate', 'lines', 'paymentTerms', 'promoCode']);
  assert.deepEqual(evaluationOrder(dmn, 'shipping').map(d => d.name), ['orderDiscounts', 'orderDiscountPercent', 'pricedLines', 'shipping']);
  assert.equal(requiredInputs(dmn, 'unknown'), null);
});

test('dmn: what a decision needs and gives, with the types for a reader (the dropdown of the mask)', () => {
  const model = pricing();
  assert.equal(describeNames(requiredInputs(model, 'orderDiscounts'), inputTypes(model)), 'customerTier (string), paymentTerms (string), promoCode (string), invoiceDate (date)',
    'an item definition is resolved to what it stands for');
  assert.equal(inputTypes(model).lines, 'list of structure');
  assert.equal(describeOutputs(model, decisionOf(model, 'shipping')), 'fee (number), reason (string)', 'several output columns: a structure');
  assert.equal(describeOutputs(model, decisionOf(model, 'totals')), 'totals (structure)');
  assert.equal(describeOutputs(model, decisionOf(model, 'pricedLines')), 'pricedLines (list of structure)');
});

test('dmn: only what decides makes a new version', () => {
  const a = pricing();
  const b = cloneDmn(a);
  assert.equal(fingerprint(a), fingerprint(b));
  functionOf(b, 'lineDiscountTable').table.rules[5].annotation = 'another note';
  assert.equal(fingerprint(a), fingerprint(b));
  functionOf(b, 'lineDiscountTable').table.rules[5].outputs[0] = '8';
  assert.notEqual(fingerprint(a), fingerprint(b), 'a function decides too');
  const c = cloneDmn(a);
  functionOf(c, 'taxFormula').expression = 'decimal(net * rate / 100, 2)';
  assert.notEqual(fingerprint(a), fingerprint(c), 'and so does a formula');
});

test('dmn: a file that is not DMN is refused', () => {
  assert.throws(() => parseDmn('<rules/>'), DmnError);
  assert.throws(() => parseDmn('<definitions>'), DmnError);
});

test('dmn: the demo model is one decision table, decided from the top', () => {
  const dmn = demoModel();
  assert.equal(dmn.name, 'invoice-discount');
  assert.deepEqual(dmn.inputs.map(i => [i.name, i.type]), [['customerTier', 'string'], ['orderAmount', 'number']]);
  assert.deepEqual(dmn.decisions.map(d => [d.name, d.kind]), [['discount', 'table']]);
  assert.equal(describeOutputs(dmn, decisionOf(dmn, 'discount')), 'discountPercent (number), discountReason (string)');
  const table = decisionOf(dmn, 'discount').table;
  assert.equal(table.hitPolicy, 'FIRST');
  assert.equal(table.rules.length, 7);
  assert.deepEqual(table.rules[5], { inputs: ['"Silver"', '-'], outputs: ['2', '"Silver customer"'], annotation: '' });
  const decide = (customerTier, orderAmount) => evaluateTable(table, { customerTier, orderAmount });
  assert.deepEqual(decide('Silver', 9200), { result: { discountPercent: 2, discountReason: 'Silver customer' }, matched: [5] });
  assert.deepEqual(decide('Gold', 9200).matched, [3]);
  assert.deepEqual(decide('Gold', 10400).matched, [2]);
  assert.deepEqual(decide('Silver', 12000).matched, [4], 'first match wins: rule 5 stands above rules 6 and 7');
  assert.deepEqual([decide('Silver', 9999).matched, decide('Silver', 10000).matched], [[5], [4]], '>= includes the number');
  assert.deepEqual(decide('Standard', 12000).matched, [6]);
  assert.deepEqual(decide(null, 9200), { result: { discountPercent: 0, discountReason: 'No discount' }, matched: [6] }, 'only - matches null');
});

// ── FEEL ─────────────────────────────────────────────────────────────────────

test('feel: conditions of a rule', () => {
  assert.equal(matches('-', 5), true);
  assert.equal(matches('', null), true);
  assert.equal(matches('"Books"', 'Books'), true);
  assert.equal(matches('"Books"', 'Hardware'), false);
  assert.equal(matches('"DE", "AT"', 'AT'), true);
  assert.equal(matches('>= 50', 50), true);
  assert.equal(matches('< 500', 500), false);
  assert.equal(matches('[10..50)', 10), true);
  assert.equal(matches('[10..50)', 50), false);
  assert.equal(matches('(10..50]', 50), true);
  assert.equal(matches('not("Books")', 'Software'), true);
  assert.equal(matches('not("CH", "DE")', 'CH'), false);
  assert.equal(matches('true', true), true);
  assert.equal(matches('15', 15), true);
  assert.equal(matches('"a, b"', 'a, b'), true, 'a comma inside quotes does not separate');
  const day = iso => new FeelDate(iso);
  assert.equal(matches('[date("2026-11-01")..date("2027-01-31")]', day('2026-11-19')), true, 'a range of dates');
  assert.equal(matches('[date("2026-11-01")..date("2027-01-31")]', day('2027-03-01')), false);
  assert.equal(matches('not([date("2026-11-01")..date("2027-01-31")])', day('2027-03-01')), true);
});

test('feel: a value that is not set matches only "-" and null', () => {
  assert.equal(matches('>= 50', null), false);
  assert.equal(matches('[10..50)', null), false);
  assert.equal(matches('"Books"', null), false);
  assert.equal(matches('null', null), true);
  assert.equal(matches('-', null), true);
  assert.equal(matches('not(null, "", "WINTER26")', null), false);
  assert.equal(matches('not(null, "", "WINTER26")', 'SUMMER'), true);
  assert.equal(matches('>= 50', 'text'), false, 'a number is not compared with a text');
});

test('feel: results of a table cell are literal values, anything else is reported', () => {
  assert.equal(literal('15'), 15);
  assert.equal(literal('12.5'), 12.5);
  assert.equal(literal('"Key account"'), 'Key account');
  assert.equal(literal('true'), true);
  assert.equal(literal('null'), null);
  assert.throws(() => literal('amount * 2'), e => e instanceof FeelError && e.kind === 'unsupported');
  assert.equal(validateResult('15'), null);
  assert.ok(validateResult('abc') instanceof FeelError);
  assert.equal(validateCondition('[1..5]'), null);
  assert.ok(validateCondition('"open') instanceof FeelError);
});

test('feel: expressions of a formula, a context and a list', () => {
  assert.equal(evaluateExpression('round half up(net * rate / 100, 2)', { net: 513, rate: 2.6 }), 13.34);
  assert.equal(evaluateExpression('decimal(net * rate / 100, 2)', { net: 0.125, rate: 100 }), 0.12, 'decimal() rounds to the even neighbour');
  assert.equal(evaluateExpression('round half up(net * rate / 100 * 20, 0) / 20', { net: 11280, rate: 8.1 }), 913.7, 'Swiss 5 centimes');
  assert.equal(evaluateExpression('0.1 + 0.2', {}), 0.3, 'arithmetic as with decimals');
  assert.deepEqual(evaluateExpression('{ a: 2, b: a * 3, c: if b > 5 then "big" else "small" }', {}), { a: 2, b: 6, c: 'big' }, 'an entry reads the ones before it');
  const items = [{ net: 10, vat: 1 }, { net: 20, vat: 2 }];
  assert.deepEqual(evaluateExpression('lines.net', { lines: items }), [10, 20], 'a path over a list');
  assert.equal(evaluateExpression('sum(lines.net)', { lines: items }), 30);
  assert.equal(evaluateExpression('min(10, sum(concatenate([0], [4, 2, 5])))', {}), 10);
  assert.deepEqual(evaluateExpression('for line in lines return line.net + line.vat', { lines: items }), [11, 22]);
  assert.equal(evaluateExpression('list contains(["Sales manager"], "Finance director")', {}), false);
  assert.deepEqual(evaluateExpression('lines[net > 15]', { lines: items }), [items[1]], 'a filter');
  assert.equal(evaluateExpression('lines[-1].net', { lines: items }), 20);
  assert.equal(evaluateExpression('date("2026-11-19") < date("2027-01-31")', {}), true);
  assert.equal(roundTo(2.675, 2, 'half up'), 2.68, 'no float noise: 2.675 rounds up');
  assert.throws(() => evaluateExpression('netto * rate', { net: 1, rate: 2 }), e => e instanceof FeelError && /Unknown variable 'netto'/.test(e.message));
  assert.ok(validateExpression('net * rate /') instanceof FeelError);
  assert.ok(validateExpression('function(x) external {}') instanceof FeelError, 'no function definitions');
  assert.equal(validateExpression('round half up(net * rate / 100, 2)'), null);
});

test('feel: a table that is a function decides as its rules say', () => {
  const table = functionOf(pricing(), 'lineDiscountTable').table;
  const decide = (category, quantity, customerTier, country) => evaluateTable(table, { category, quantity, customerTier, country });
  assert.deepEqual(decide('Hardware', 10, 'Silver', 'CH'), { result: { percent: 6, reason: 'Hardware: 10 units or more' }, matched: [5] });
  assert.deepEqual(decide('Hardware', 10, 'Gold', 'CH').matched, [4]);
  assert.deepEqual(decide('Hardware', 60, 'Silver', 'CH').matched, [3]);
  assert.deepEqual(decide('Books', 12, 'Silver', 'DE'), { result: { percent: 0, reason: 'Fixed book price in DE and AT: no discount' }, matched: [0] }, 'first match wins');
  assert.deepEqual(decide('Software', 19, 'Silver', 'CH').matched, [7]);
  assert.deepEqual(decide('Software', 20, 'Silver', 'CH').matched, [6]);
  assert.deepEqual(decide('Consumables', 30, 'Silver', 'CH').matched, [9]);
  assert.throws(() => evaluateTable(table, { category: 'Books', quantity: 1, customerTier: 'Gold' }), e => e instanceof FeelError && /country/.test(e.message));
});

test('feel: UNIQUE allows one match, COLLECT returns every hit, input columns are expressions', () => {
  const dmn = pricing();
  const vat = functionOf(dmn, 'vatRateTable').table;
  assert.deepEqual(evaluateTable(vat, { country: 'DE', category: 'Books' }), { result: 7, matched: [2] });
  const twice = cloneDmn(dmn);
  functionOf(twice, 'vatRateTable').table.rules[1].inputs[1] = '-';
  assert.throws(() => evaluateTable(functionOf(twice, 'vatRateTable').table, { country: 'CH', category: 'Books' }), e => e instanceof FeelError && /UNIQUE/.test(e.message));
  const discounts = decisionOf(dmn, 'orderDiscounts').table;
  const collected = evaluateTable(discounts, { customerTier: 'Platinum', paymentTerms: 'Prepaid', promoCode: 'WINTER26', invoiceDate: new FeelDate('2026-11-19') });
  assert.deepEqual(collected.result.map(d => d.percent), [4, 2, 5]);
  assert.deepEqual(collected.matched, [1, 2, 3]);
  assert.deepEqual(evaluateTable(discounts, { customerTier: 'Silver', paymentTerms: 'Net 30', promoCode: '', invoiceDate: null }).result, [], 'COLLECT without a hit: an empty list');
  const shipping = decisionOf(dmn, 'shipping').table;
  assert.equal(shipping.inputs[1].expression, 'sum(pricedLines.net)');
  assert.equal(evaluateTable(shipping, { country: 'CH', expressDelivery: false, pricedLines: [{ net: 300 }, { net: 100 }] }).result.fee, 15);
});

// ── The decision runtime ─────────────────────────────────────────────────────

const withHardware = (dmn, value) => {
  const copy = cloneDmn(dmn);
  functionOf(copy, 'lineDiscountTable').table.rules[5].outputs[0] = String(value);
  return copy;
};
const laptop = answer => answer.results.pricedLines[0].discountPercent;

test('runtime: starts empty, the first version goes live, the same content is stored once', () => {
  const runtime = new DecisionRuntime();
  assert.throws(() => runtime.evaluate('invoice-pricing', null, SAMPLE), e => e instanceof RuntimeError && e.code === 'MODEL_NOT_FOUND');
  const first = runtime.register('invoice-pricing', pricing(), { source: 'PROJECT', author: 'demo' });
  assert.equal(first.created, true);
  assert.equal(first.entry.status, 'ACTIVE');
  assert.equal(runtime.register('invoice-pricing', pricing(), { source: 'PROJECT' }).created, false);
  assert.equal(runtime.versions('invoice-pricing').length, 1);
  const answer = runtime.evaluate('invoice-pricing', null, SAMPLE);
  assert.equal(answer.version, 1);
  assert.deepEqual(answer.results.pricedLines.map(line => line.vat), [913.68, 206.55, 97.2, 111.78, 13.34]);
  assert.deepEqual(answer.results.totals, { gross: 18240, subtotal: 16923, lineDiscounts: 1317, orderDiscount: 0, net: 16923, vat: 1342.55, shippingFee: 0, total: 18265.55 });
  assert.deepEqual(answer.results.approval, { route: 'Sales manager', reasons: ['A line discount of 15% or more'] });
  assert.deepEqual(answer.calls.lineDiscountTable.map(made => made.matched[0]), [5, 6, 2, 8, 1], 'a function: one call per line');
  assert.deepEqual(answer.calls.taxFormula[4], { args: [513, 2.6], matched: [], result: 13.34 });
});

test('runtime: a project version replaces a project version; a rollback walks back', () => {
  const runtime = new DecisionRuntime();
  runtime.register('invoice-pricing', pricing(), { source: 'PROJECT' });
  runtime.register('invoice-pricing', withHardware(pricing(), 8), { source: 'PROJECT' });
  runtime.register('invoice-pricing', withHardware(pricing(), 9), { source: 'PROJECT' });
  assert.deepEqual(runtime.versions('invoice-pricing').map(v => v.status), ['RETIRED', 'RETIRED', 'ACTIVE']);
  assert.equal(laptop(runtime.evaluate('invoice-pricing', null, SAMPLE)), 9);
  assert.equal(runtime.rollback('invoice-pricing').version, 2);
  assert.equal(runtime.rollback('invoice-pricing').version, 1);
  assert.equal(laptop(runtime.evaluate('invoice-pricing', null, SAMPLE)), 6);
  assert.throws(() => runtime.rollback('invoice-pricing'), e => e.code === 'INVALID_STATE');
});

test('runtime: a version activated from the Portal is not replaced by the project', () => {
  const runtime = new DecisionRuntime();
  runtime.register('invoice-pricing', pricing(), { source: 'PROJECT' });
  const portal = runtime.register('invoice-pricing', withHardware(pricing(), 10), { source: 'PORTAL', author: 'rule.owner', activate: true });
  assert.equal(portal.entry.status, 'ACTIVE');
  const redeployed = runtime.register('invoice-pricing', withHardware(pricing(), 8), { source: 'PROJECT' });
  assert.deepEqual([redeployed.entry.version, redeployed.entry.status], [3, 'DRAFT']);
  assert.equal(runtime.register('invoice-pricing', pricing(), { source: 'PROJECT' }).entry.status, 'RETIRED', 'the initial file does not come back either');
  assert.equal(laptop(runtime.evaluate('invoice-pricing', null, SAMPLE)), 10);
  runtime.activate('invoice-pricing', 3);
  assert.equal(laptop(runtime.evaluate('invoice-pricing', null, SAMPLE)), 8);
});

test('runtime: drafts from the API stay drafts, errors carry the codes of the API, inputs are type checked', () => {
  const runtime = new DecisionRuntime();
  runtime.register('invoice-pricing', pricing());
  assert.equal(runtime.register('invoice-pricing', withHardware(pricing(), 8)).entry.status, 'DRAFT');
  assert.throws(() => runtime.evaluate('invoice-pricing', 'bonus', SAMPLE), e => e.code === 'DECISION_NOT_FOUND' && /Available decisions: \[orderDiscounts,/.test(e.message));
  const without = { ...SAMPLE };
  delete without.promoCode;
  assert.throws(() => runtime.evaluate('invoice-pricing', null, without), e => e.code === 'EVALUATION_FAILED' && /'promoCode' not found/.test(e.message));
  assert.throws(() => runtime.evaluate('invoice-pricing', null, { ...SAMPLE, customerTier: 'Diamond' }),
    e => e.code === 'EVALUATION_FAILED' && /not allowed by the declared type \(tCustomerTier\)/.test(e.message));
  assert.throws(() => runtime.evaluate('invoice-pricing', null, { ...SAMPLE, customerTier: null }),
    e => e.code === 'EVALUATION_FAILED' && /'null' of 'customerTier'/.test(e.message), 'null is not one of the allowed values');
  assert.throws(() => runtime.evaluate('invoice-pricing', null, { ...SAMPLE, lines: [{ ...SAMPLE.lines[0], category: 'Food' }] }),
    e => e.code === 'EVALUATION_FAILED' && /\(tLines\)/.test(e.message), 'the allowed values of a component of a list');
  assert.equal(runtime.evaluate('invoice-pricing', null, { ...SAMPLE, promoCode: null }).results.totals.total, 18265.55, 'a plain text takes null');
  assert.throws(() => runtime.activate('invoice-pricing', 9), e => e.code === 'VERSION_NOT_FOUND');
});

test('runtime: one decision with what it needs; the others are not evaluated', () => {
  const runtime = new DecisionRuntime();
  runtime.register('invoice-pricing', pricing());
  const answer = runtime.evaluate('invoice-pricing', 'orderDiscounts', { customerTier: 'Gold', paymentTerms: 'Prepaid', promoCode: null, invoiceDate: null });
  assert.deepEqual(answer.results, { orderDiscounts: [{ percent: 2, reason: 'Gold customer bonus' }, { percent: 2, reason: 'Early payment: prepaid' }] });
  assert.deepEqual(answer.matched, { orderDiscounts: [0, 2] });
});

// ── Mapping ──────────────────────────────────────────────────────────────────

test('mapping: selected fields get their DMN names', () => {
  assert.deepEqual(parseFieldSelection('in.invoice.country, in.invoice.expressDelivery\nin.customer.country; in.amount\nin.invoice.country'), [
    { name: 'invoiceCountry', expression: 'in.invoice.country' },
    { name: 'expressDelivery', expression: 'in.invoice.expressDelivery' },
    { name: 'customerCountry', expression: 'in.customer.country' },
    { name: 'amount', expression: 'in.amount' }]);
  assert.deepEqual(parseFieldSelection(''), []);
  assert.deepEqual([...uniqueNames(['in.a.b.type', 'in.c.b.type']).values()], ['aBType', 'cBType']);
  assert.equal(toFeelName('function'), 'function_');
  assert.equal(toFieldName('function_'), 'function');
  assert.equal(toFieldName('total_'), 'total_');
});

test('mapping: a field is found by name, the one closest to the root first', () => {
  const model = processModel();
  assert.deepEqual(findField(model, 'customerTier'), { expression: 'in.invoice.customerTier', type: 'String' });
  assert.deepEqual(findField(model, 'orderAmount'), { expression: 'in.invoice.orderAmount', type: 'java.math.BigDecimal' });
  assert.deepEqual(findField(model, 'lines'), { expression: 'in.invoice.lines', type: 'List<' + NS + 'InvoiceLine>' }, 'a list field by its own name');
  assert.deepEqual(findField(model, 'Customer Tier'), { expression: 'in.invoice.customerTier', type: 'String' }, 'case and blanks do not matter');
  assert.equal(findField(model, 'discount'), null, 'no field of the name: a structured result is unfolded');
  assert.equal(findField(model, 'product'), null, 'a list is not searched inside');
  assert.equal(typeOfPath(model, 'in.invoice.total'), 'java.math.BigDecimal');
  assert.equal(typeOfPath(model, 'in.invoice.unknown'), null);
});

test('mapping: values are read without creating nested objects and converted both ways', () => {
  const model = processModel();
  assert.equal(readPath({}, 'in.invoice.country'), null);
  assert.equal(readPath({ invoice: { __type: 'x', country: 'CH' } }, 'in.invoice.country'), 'CH');
  assert.deepEqual(toJson({ __type: 'x', product: 'Laptop', quantity: undefined }), { product: 'Laptop', quantity: null });
  assert.equal(fromJson(15, 'java.math.BigDecimal', model.classes), 15);
  assert.equal(fromJson(2, 'java.lang.Integer', model.classes), 2);
  assert.equal(fromJson(7, 'String', model.classes), '7');
  assert.equal(fromJson(null, 'Number', model.classes), null);
  assert.throws(() => fromJson('a lot', 'Number', model.classes), e => e instanceof MappingError && /a lot/.test(e.message));
  const laptop = { product: 'Laptop', quantity: 4, unitPrice: 1200, amount: 4800 };
  assert.deepEqual(fromJson(laptop, NS + 'InvoiceLine', model.classes), { __type: NS + 'InvoiceLine', ...laptop });
  assert.deepEqual(fromJson([laptop], 'List<' + NS + 'InvoiceLine>', model.classes), [{ __type: NS + 'InvoiceLine', ...laptop }],
    'a list of structures: a list of data classes');
});

test('generator: a table from selected fields, named so that it maps by name', () => {
  const model = processModel();
  const dmn = generateDmn({
    modelName: 'invoice-approver',
    inputs: parseFieldSelection('in.invoice.total, in.invoice.discountPercent'),
    outputs: parseFieldSelection('in.invoice.discountAmount')
  }, model);
  assert.equal(dmn.file, 'dmn/invoice-approver.dmn');
  assert.deepEqual(dmn.inputs.map(i => [i.name, i.type]), [['total', 'number'], ['discountPercent', 'number']]);
  const decision = dmn.decisions[0];
  assert.equal(decision.name, 'discountAmount');
  assert.equal(dmn.description, 'Generated by a Business Rule Task. Inputs: in.invoice.total, in.invoice.discountPercent. Outputs: in.invoice.discountAmount.');
  assert.equal(decision.table.hitPolicy, 'FIRST');
  assert.deepEqual(decision.table.rules, [{ inputs: ['-', '-'], outputs: ['0'], annotation: 'Placeholder: matches everything. Add your rules above this one.' }]);
  assert.deepEqual(requiredInputs(dmn, 'discountAmount'), ['total', 'discountPercent']);
  assert.deepEqual(evaluateTable(decision.table, { total: 9016, discountPercent: 2 }), { result: 0, matched: [0] });

  // a nested data class is unfolded into columns
  const nested = {
    dataClass: { name: 'Data', simpleName: 'Data', fields: [{ name: 'shipping', type: 'Shipping' }, { name: 'errorCode' }] },
    classes: { Shipping: { name: 'Shipping', fields: [{ name: 'fee', type: 'Number' }, { name: 'reason' }] } }
  };
  const whole = generateDmn({ modelName: 'Shipping.dmn', inputs: parseFieldSelection('in.shipping'), outputs: parseFieldSelection('in.errorCode') }, nested);
  assert.deepEqual(whole.decisions[0].table.inputs.map(c => c.expression), ['shipping.fee', 'shipping.reason']);
  assert.equal(modelNameFor('invoice approver.dmn'), 'invoice-approver');
  assert.throws(() => modelNameFor('  '), e => e instanceof MappingError && e.message === "'New DMN name' is empty. Name the new DMN, e.g. member-discount.");
  assert.throws(() => generateDmn({ modelName: 'x', inputs: parseFieldSelection('in.invoice.unknown'), outputs: parseFieldSelection('in.errorCode') }, model),
    e => e instanceof MappingError && /in\.invoice\.unknown/.test(e.message));
  assert.throws(() => generateDmn({ modelName: 'x', inputs: [], outputs: [] }, model), MappingError);
  assert.throws(() => generateDmn({ modelName: 'x', inputs: parseFieldSelection('tier = in.invoice.customerTier'), outputs: parseFieldSelection('in.errorCode') }, model),
    e => e instanceof MappingError && /is no field\. List fields with their path/.test(e.message), 'an input is named like its field: there is nothing to rename');
});

test('generator: a selection that the mapping by name would not find again is refused', () => {
  const model = processModel();
  assert.doesNotThrow(() => readableByName(parseFieldSelection('in.invoice.total, in.invoice.discountPercent'), parseFieldSelection('in.errorCode'), model));
  assert.throws(() => readableByName(parseFieldSelection('in.errorCode'), parseFieldSelection('in.errorCode'), model),
    /'errorCode' is selected as input and as output/);

  // the same name in two data classes: in.order.type comes first
  const twice = {
    dataClass: { name: 'Data', simpleName: 'Data', fields: [{ name: 'order', type: 'Order' }, { name: 'member', type: 'Member' }] },
    classes: { Order: { name: 'Order', fields: [{ name: 'type' }] }, Member: { name: 'Member', fields: [{ name: 'type' }, { name: 'discount', type: 'Number' }] } }
  };
  assert.throws(() => readableByName(parseFieldSelection('in.member.type'), parseFieldSelection('in.member.discount'), twice),
    /would use in\.order\.type for 'type', not in\.member\.type/);
  assert.throws(() => readableByName(parseFieldSelection('in.order.type, in.member.type'), parseFieldSelection('in.member.discount'), twice),
    /Several selected fields are named 'type'/);
});

// ── IvyScript and settings ───────────────────────────────────────────────────

test('ivyscript: fields of nested data classes are assigned, the objects on the way are created', () => {
  const model = processModel();
  const ctx = { data: {}, fields: model.dataClass.fields, dataClassName: model.dataClass.name, classes: model.classes };
  runScript('in.invoice.customerTier = "Gold";\nin.invoice.number = "INV-1";', ctx);
  assert.deepEqual(ctx.data.invoice, { __type: NS + 'Invoice', customerTier: 'Gold', number: 'INV-1' });
  assignTo('in.errorCode', 'none', ctx);
  assignTo('in.invoice.lines', [{ __type: NS + 'InvoiceLine', product: 'X' }], ctx);
  assert.deepEqual([ctx.data.errorCode, ctx.data.invoice.lines.length], ['none', 1]);
  assert.throws(() => runScript('in.invoice.unknown = 1;', ctx), e => e instanceof IvyScriptError && e.kind === 'field');
  assert.throws(() => assignTo('in.invoice.lines', 'text', ctx), e => e instanceof IvyScriptError);
});

test('ivyscript: the Scripts of the demo sum the lines and apply the discount, as BigDecimal does', () => {
  const model = processModel();
  const process = json('process/InvoicePricing.p.json');
  const code = id => lines(process.elements.find(e => e.id === id).config.output.code);
  const logs = [];
  const ctx = { data: {}, fields: model.dataClass.fields, dataClassName: model.dataClass.name, classes: model.classes, log: (level, message) => logs.push(message) };
  const line = (quantity, unitPrice) => ({ __type: NS + 'InvoiceLine', quantity, unitPrice });
  ctx.data.invoice = { __type: NS + 'Invoice', number: 'INV-1', lines: [line(4, 1200), line(1, 0.3)] };
  assert.equal(validateScript(code('f2'), ctx), null);
  assert.equal(validateScript(code('f6'), ctx), null);

  runScript(code('f2'), ctx);
  assert.deepEqual(ctx.data.invoice.lines.map(l => l.amount), [4800, 0.3], 'a loop over the lines sets a field of each');
  assert.equal(ctx.data.invoice.orderAmount, 4800.3, 'no float noise');
  Object.assign(ctx.data.invoice, { discountPercent: 2.5, discountReason: 'Odd' });
  runScript(code('f6'), ctx);
  assert.deepEqual([ctx.data.invoice.discountAmount, ctx.data.invoice.total], [120.01, 4680.29], '120.0075 rounded half up');
  assert.deepEqual(logs, ['Invoice INV-1: 2.5% (Odd), total 4680.29']);

  ctx.data.invoice.discountPercent = null;
  assert.throws(() => runScript(code('f6'), ctx), e => e.javaType === 'NullPointerException', 'Java does not multiply by null');
  ctx.data.invoice.lines = null;
  runScript(code('f2'), ctx);
  assert.equal(ctx.data.invoice.orderAmount, 0, 'a list that is not set has no elements');
  assert.throws(() => runScript('BigDecimal x = BigDecimal.ONE.movePointLeft(3).setScale(2);', ctx), e => e.javaType === 'ArithmeticException');
  assert.equal(validateScript('for (int i = 0; i < 3; i++) { }', ctx).kind, 'unsupported');
});

test('settings: what the connector reads from variables.yaml, with its defaults', () => {
  const read = yaml => readSettings(parseYaml(yaml).value);
  assert.deepEqual(read('Variables:\n  RuleEngine:\n    # the container\n    Url: http://localhost:8090\n    ApiKey: ""\n    TimeoutSeconds: "3"\n    AutoDeploy: "false"\n    ModelPrefix: sales-\n'),
    { url: 'http://localhost:8090', apiKey: '', timeout: 3, autoDeploy: false, prefix: 'sales-' });
  assert.deepEqual(read('Variables:\n  RuleEngine:\n    Url: http://rules:8080\n'), { url: 'http://rules:8080', apiKey: '', timeout: 10, autoDeploy: true, prefix: '' });
  const real = read(readFileSync(fileURLToPath(new URL('../../../../rule-engine/config/variables.yaml', import.meta.url)), 'utf8'));
  assert.deepEqual([real.url, real.timeout, real.autoDeploy], ['http://localhost:8090', 10, true], 'the variables.yaml of the connector');
});

test('ivyscript: the check of a script accepts a field of a nested data class, as a run does', () => {
  const model = processModel();
  const ctx = { data: {}, fields: model.dataClass.fields, dataClassName: model.dataClass.name, classes: model.classes };
  assert.equal(validateScript('in.invoice.customerTier = "Gold";\nin.invoice.customer = in.invoice.customer.trim();', ctx), null);
  assert.match(validateScript('in.invoice.unknown = 1;', ctx).message, /in\.invoice\.unknown' cannot be read: invoice has no field unknown/);
  assert.match(validateScript('invoice = 1;', ctx).message, /Only process data fields/);
});

test('generator: a file says which fields generated it, so it is told apart from another file of the same name', () => {
  const model = processModel();
  const inputs = parseFieldSelection('in.invoice.total, in.invoice.discountPercent');
  const outputs = parseFieldSelection('in.invoice.discountAmount');
  const generated = generateDmn({ modelName: 'invoice-approver', inputs, outputs }, model);
  assert.ok(generatedFrom(generated, inputs, outputs), 'the file its element generated');
  assert.ok(!generatedFrom(generated, inputs, parseFieldSelection('in.errorCode')), 'other fields: another file');
  assert.ok(!generatedFrom(demoModel(), inputs, outputs), 'dmn/invoice-discount.dmn was written by hand');
});
