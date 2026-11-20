/**
 * What the rule owner adds in the Portal (guide-07, and the questions of guide-11-3): a key account discount. The
 * table discount gets the input customer, which the invoice has (in.invoice.customer) but the rules of the project
 * do not read, and a rule for one customer at the top: with the hit policy FIRST a rule below "Silver customer"
 * would never decide for this Silver customer. The same version is in rule-engine-test
 * (scenario/invoice-discount-key-account.dmn.xml), where the real runtime decides it.
 */
import { cloneDmn, addInput } from '../../runtime/model/dmn.js';

export const TABLE = 'discount';

/** The input the key account rule needs. The process data has it already. */
export const NEW_INPUT = ['customer', 'string'];

/** The rule of the rule owner: [customerTier, orderAmount, customer], [discountPercent, discountReason], description. */
export const NEW_RULE = [['-', '-', '"Bluebird Architects"'], ['10', '"Key account: framework contract"'], 'Framework contract of 2026'];

export const WHAT_CHANGED = 'Key account discount for Bluebird Architects';

/** The model with the key account rule, made of the model of the project the way the walkthrough makes it in the Portal. */
export function withKeyAccount(dmn) {
  const copy = cloneDmn(dmn);
  addInput(copy, TABLE, { name: NEW_INPUT[0], type: NEW_INPUT[1] });
  const table = copy.decisions.find(decision => decision.name === TABLE).table;
  table.rules.unshift({ inputs: NEW_RULE[0].slice(), outputs: NEW_RULE[1].slice(), annotation: NEW_RULE[2] });
  return copy;
}
