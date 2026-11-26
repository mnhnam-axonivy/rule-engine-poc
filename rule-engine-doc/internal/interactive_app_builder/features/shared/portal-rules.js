import { cloneDmn, addInput } from '../../runtime/model/dmn.js';

export const TABLE = 'discount';

export const NEW_INPUT = ['customer', 'string'];

export const NEW_RULE = [['-', '-', '"Bluebird Architects"'], ['10', '"Key account: framework contract"'], 'Framework contract of 2026'];

export const WHAT_CHANGED = 'Key account discount for Bluebird Architects';

export function withKeyAccount(dmn) {
  const copy = cloneDmn(dmn);
  addInput(copy, TABLE, { name: NEW_INPUT[0], type: NEW_INPUT[1] });
  const table = copy.decisions.find(decision => decision.name === TABLE).table;
  table.rules.unshift({ inputs: NEW_RULE[0].slice(), outputs: NEW_RULE[1].slice(), annotation: NEW_RULE[2] });
  return copy;
}
