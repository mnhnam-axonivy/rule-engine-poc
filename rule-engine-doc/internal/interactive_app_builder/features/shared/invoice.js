/**
 * The demo as the lessons see it, without the files of the project: the name of the model, the elements of the
 * process, the invoice the page of the demo passes, and how the notes quote what a run gives. Plain JavaScript,
 * so that the unit tests use the same.
 */
/** The model on the runtime: the name of the .dmn file. */
export const MODEL = 'invoice-pricing';

/** Element ids of InvoicePricing.p.json. */
export const ELEMENTS = {
  START: 'f0', // price(SalesInvoice)
  PREPARE: 'f2', // Script: prepare the promo code
  RULE_TASK: 'f4',
  BOUNDARY: 'f8',
  LOG: 'f6',
  END: 'f11',
  NOT_PRICED: 'f10'
};

const NS = 'com.axonivy.utils.rule.engine.demo.';
const line = (product, category, quantity, unitPrice) => ({ __type: NS + 'InvoiceLine', product, category, quantity, unitPrice });

/** What the page of the demo passes: InvoiceSamples.standard(), each line decided by another rule. */
export const SAMPLE_INVOICE = {
  __type: NS + 'SalesInvoice',
  number: 'INV-2026-0042',
  customer: 'Bluebird Architects',
  customerTier: 'Silver',
  country: 'CH',
  paymentTerms: 'Net 30',
  promoCode: '',
  expressDelivery: false,
  invoiceDate: '2026-11-19',
  lines: [
    line('Laptop 14"', 'Hardware', 10, 1200),
    line('Office suite licence, 1 year', 'Software', 25, 120),
    line('Installation and setup, hours', 'Services', 8, 150),
    line('A4 paper, box of 5 reams', 'Consumables', 60, 25),
    line('Project management handbook', 'Books', 12, 45)
  ]
};

// ── what a run gives, as the notes quote it ──────────────────────────────────

const amount = value => (value === null || value === undefined ? 'null' : Number(value).toFixed(2));

/** One priced line: Hardware 10 × 1200 · 6 % · net 11280.00 · VAT 913.68 */
export const pricedLineText = priced => priced.category + ' ' + priced.quantity + ' × ' + priced.unitPrice + ' · ' + priced.discountPercent + ' % · net '
  + amount(priced.net) + ' · VAT ' + amount(priced.vat);

/** What the Business Rule Task got back, in a few lines: the lines, the total, who approves, the version. */
export function pricedText(call) {
  const results = call.results || {};
  const lines = (results.pricedLines || []).map((priced, i) => 'line ' + (i + 1) + ': ' + pricedLineText(priced));
  const total = results.totals ? 'total ' + amount(results.totals.total) : '';
  const route = results.approval ? results.approval.route : '';
  return lines.concat([[total, route].filter(Boolean).join(' · ') + '   (version ' + call.version + ')']).join('\n');
}

/** The first line of an invoice: line 1: Hardware 10 × 1200 · 6 % · …   (version 1) */
export const laptopText = call => {
  const priced = ((call.results || {}).pricedLines || [])[0];
  return priced ? 'line 1: ' + pricedLineText(priced) + '   (version ' + call.version + ')' : '';
};

/** The inputs a call sent, one per line; a list by its number of elements. */
export const inputsText = call => (call.mappings || []).map(m => m.name + ' = '
  + (Array.isArray(m.value) ? '[' + m.value.length + ' lines]' : JSON.stringify(m.value))).join('\n');

export const errorText = call => call.errorCode + '\n' + call.error;

/** The rule of lineDiscountTable that decided each line: line 1: rule 6 · Hardware: 10 units or more */
export const lineRulesText = call => (call.rules || []).filter(rule => rule.table === 'lineDiscountTable')
  .map(rule => 'line ' + rule.call + ': rule ' + (rule.index + 1) + ' · ' + String(rule.outputs[1] || '').replace(/^"|"$/g, '')).join('\n');

const percent = value => (value === null || value === undefined ? 'null' : String(value));

/** orderDiscounts = Key account 3 % · applied 3 % · total 17717.58   (version 2) */
export const discountsText = call => {
  const results = call.results || {};
  const list = (results.orderDiscounts || []).map(discount => discount.reason + ' ' + percent(discount.percent) + ' %');
  return 'orderDiscounts = ' + (list.length ? list.join(', ') : 'none') + ' · applied ' + percent(results.orderDiscountPercent) + ' %'
    + (results.totals ? ' · total ' + amount(results.totals.total) : '') + '   (version ' + call.version + ')';
};
