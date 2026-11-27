/**
 * The demo as the lessons see it, without the files of the project: the name of the model, the elements of the
 * process, the invoice the page of the demo passes, and how the notes quote what a run gives. Plain JavaScript,
 * so that the unit tests use the same.
 */
/** The model: the name of the .dmn file. */
export const MODEL = 'invoice-discount';
/** Its one decision, a decision table. */
export const DECISION = 'discount';

/** Element ids of InvoicePricing.p.json. */
export const ELEMENTS = {
  START: 'f0', // price(Invoice)
  SUM: 'f2', // Script: sum the lines
  RULE_TASK: 'f4',
  BOUNDARY: 'f8',
  APPLY: 'f6', // Script: apply the discount
  END: 'f11',
  NOT_PRICED: 'f10'
};

const NS = 'com.axonivy.utils.rule.engine.demo.';
const line = (product, quantity, unitPrice) => ({ __type: NS + 'InvoiceLine', product, quantity, unitPrice });

/** What the page of the demo passes: InvoiceSamples.standard(), a Silver customer, an order of 9,200. */
export const SAMPLE_INVOICE = {
  __type: NS + 'Invoice',
  number: 'INV-2026-0042',
  customer: 'Bluebird Architects',
  customerTier: 'Silver',
  lines: [
    line('Laptop 14"', 4, 1200),
    line('Monitor 27"', 4, 320),
    line('Docking station', 4, 180),
    line('Office suite licence, 1 year', 10, 120),
    line('Installation and setup, hours', 8, 150)
  ]
};

// ── what a run gives, as the notes quote it ──────────────────────────────────

const amount = value => (value === null || value === undefined ? 'null' : Number(value).toFixed(2));
const plain = value => (value === null || value === undefined ? 'null' : String(value));
const unquote = text => String(text || '').replace(/^"|"$/g, '');

/** What the decision answered: discount = 2 % · Silver customer   (version 1) */
export function discountText(call) {
  const result = (call.results || {}).discount || {};
  return 'discount = ' + plain(result.discountPercent) + ' % · ' + plain(result.discountReason) + '   (version ' + call.version + ')';
}

/** The rule that decided: rule 6: "Silver" | -  →  2 · Silver customer */
export function ruleText(call) {
  const rule = call.rule;
  return rule ? 'rule ' + (rule.index + 1) + ':  ' + rule.inputs.join('  |  ') + '  →  ' + rule.outputs[0] + ' · ' + unquote(rule.outputs[1]) : '';
}

/** The invoice after the process applied the discount: order 9200.00 · 2 % · −184.00 · total 9016.00 */
export const invoiceText = invoice => (invoice ? 'order ' + amount(invoice.orderAmount) + ' · ' + plain(invoice.discountPercent) + ' % · −'
  + amount(invoice.discountAmount) + ' · total ' + amount(invoice.total) : '');

/** The inputs a call sent, one per line. */
export const inputsText = call => (call.mappings || []).map(m => m.name + ' = ' + JSON.stringify(m.value)).join('\n');

export const errorText = call => call.errorCode + '\n' + call.error;
