/**
 * A decision table, drawn the way the DMN editor shows it: the hit policy in the corner, a
 * column per input and output, one row per rule. Conditions and results can be edited, rules
 * added and removed. The rule that decided the last evaluation is marked.
 */
import { h } from '../util.js';
import { icon } from './icons.js';
import { textBox } from './fields.js';
import { validateCondition, validateResult, validateExpression } from '../model/feel.js';

const HIT_POLICY = { UNIQUE: 'U', FIRST: 'F', ANY: 'A', PRIORITY: 'P', COLLECT: 'C', 'RULE ORDER': 'R', 'OUTPUT ORDER': 'O' };

/** A rule that matches everything: every condition is "-". */
const isCatchAll = rule => rule.inputs.every(condition => condition.trim() === '-' || condition.trim() === '');

/**
 * @param {Object} o
 * @param {Object} o.decision     a decision of a DMN model (see model/dmn.js)
 * @param {number[]} [o.hits]     indexes of the rules that hit in the last evaluation
 * @param {boolean} [o.editable]
 * @param {(kind: 'cell' | 'note' | 'rules') => void} [o.onChange]  'rules': a rule was added or removed;
 *   'note': the description of a rule changed, which decides nothing
 * @param {(key: string, params?: Object) => string} o.t
 * @param {Object} [o.controls]   filled with the cell inputs by key, e.g. "discount.r1.o1"
 * @returns {HTMLElement}
 */
export function decisionTable({ decision, hits = [], editable = true, onChange = () => {}, t, controls = {} }) {
  const root = h('div.dmn-decision' + (decision.function ? '.dmn-function' : ''), { 'data-decision': decision.name });
  // a function (business knowledge model) shows the parameters a decision calls it with
  const params = decision.function ? '(' + decision.params.map(param => param.name).join(', ') + ')' : '';
  const head = h('div.dmn-head', null,
    h('span.dmn-kind', null, icon('list', 13), t(decision.function ? 'dmn.function' : 'dmn.decision')),
    h('strong', null, decision.name, params ? h('span.dmn-params', null, params) : null),
    decision.type ? h('span.dmn-type', null, decision.type) : null,
    decision.description ? h('span.dmn-desc', null, decision.description) : null);
  root.append(head);

  if (decision.kind === 'literal') {
    // a FEEL literal expression: a formula that can be read and changed
    const check = input => {
      const error = validateExpression(input.value);
      input.classList.toggle('invalid', Boolean(error));
      input.title = error ? error.message : '';
    };
    const input = textBox({
      value: decision.expression || '',
      mono: true,
      rows: Math.max(1, (decision.expression || '').split('\n').length),
      readOnly: !editable,
      onInput: value => {
        decision.expression = value;
        check(input);
        onChange('cell');
      }
    });
    const setValue = input.setValue;
    input.setValue = value => { setValue(value); decision.expression = value; check(input); };
    input.setAttribute('aria-label', decision.name);
    check(input);
    controls[decision.name + '.expr'] = input;
    root.append(h('div.dmn-expression', { 'data-tour': 'expr' }, input), h('p.field-note', null, t('dmn.literal')));
    return root;
  }
  if (decision.kind !== 'table') {
    root.append(
      h('pre.dmn-expression', null, decision.expression || ''),
      h('p.field-note', null, t('dmn.notSimulated')));
    return root;
  }

  const table = decision.table;
  const policy = HIT_POLICY[table.hitPolicy] || table.hitPolicy[0];
  // a column is as wide as its longest entry, so that a list like "Gold", "Silver" is not cut off
  const widest = (list, column) => Math.min(30, Math.max(8, ...table.rules.map(rule => String(list(rule)[column] || '').length))) + 4;
  const columnHead = (kind, title, type, width) => h('th.' + kind, { style: { minWidth: width + 'ch' } },
    h('div.dmn-col', null, title), type ? h('div.dmn-col-type', null, '(' + type + ')') : null);

  const cell = (rule, rowIndex, kind, column) => {
    const list = kind === 'i' ? rule.inputs : rule.outputs;
    const key = 'r' + (rowIndex + 1) + '.' + kind + (column + 1);
    const check = input => {
      const error = kind === 'i' ? validateCondition(input.value) : validateResult(input.value);
      input.classList.toggle('invalid', Boolean(error));
      input.title = error ? error.message : '';
    };
    const input = textBox({
      value: list[column] === undefined ? '' : list[column],
      mono: true,
      readOnly: !editable,
      onInput: value => {
        list[column] = value;
        check(input);
        onChange('cell');
      }
    });
    // the walkthrough types into a cell through the same path a learner does
    const setValue = input.setValue;
    input.setValue = value => { setValue(value); list[column] = value; check(input); };
    input.setAttribute('aria-label', (kind === 'i' ? table.inputs[column].label : table.outputs[column].label || decision.name) + ', rule ' + (rowIndex + 1));
    check(input);
    controls[decision.name + '.' + key] = input;
    return h('td.' + (kind === 'i' ? 'in' : 'out'), { 'data-tour': key }, input);
  };

  // the description of a rule: says why the rule is there, does not decide anything
  const noteCell = (rule, rowIndex) => {
    const key = 'r' + (rowIndex + 1) + '.n';
    const input = textBox({ value: rule.annotation || '', readOnly: !editable, onInput: value => { rule.annotation = value; onChange('note'); } });
    const setValue = input.setValue;
    input.setValue = value => { setValue(value); rule.annotation = value; };
    input.setAttribute('aria-label', t('dmn.description') + ', rule ' + (rowIndex + 1));
    controls[decision.name + '.' + key] = input;
    return h('td.note', { 'data-tour': key }, input);
  };

  const rows = table.rules.map((rule, rowIndex) => {
    const hit = hits.includes(rowIndex);
    const remove = editable && table.rules.length > 1
      ? h('button.icon-btn.dmn-remove', {
        type: 'button', title: t('dmn.removeRule'), 'aria-label': t('dmn.removeRule'),
        onclick: () => { table.rules.splice(rowIndex, 1); onChange('rules'); }
      }, icon('trash', 12))
      : null;
    return h('tr' + (hit ? '.hit' : ''), { 'data-tour': 'rule' + (rowIndex + 1) },
      h('td.num', null, hit ? h('span.dmn-hit', { title: t('dmn.hit') }, icon('check', 11)) : null, String(rowIndex + 1)),
      table.inputs.map((input, column) => cell(rule, rowIndex, 'i', column)),
      table.outputs.map((output, column) => cell(rule, rowIndex, 'o', column)),
      editable ? noteCell(rule, rowIndex) : h('td.note', null, rule.annotation || ''),
      h('td.act', null, remove));
  });

  root.append(h('div.dmn-scroll', null, h('table.dmn-table', null,
    h('thead', null, h('tr', { 'data-tour': 'head' },
      h('th.policy', { title: t('dmn.hitPolicy', { policy: table.hitPolicy }), 'data-tour': 'policy' }, policy),
      table.inputs.map((input, column) => columnHead('in', input.label || input.expression, input.type, widest(rule => rule.inputs, column))),
      table.outputs.map((output, column) => columnHead('out', output.label || output.name || decision.name,
        output.type || (table.outputs.length === 1 ? decision.type : ''), widest(rule => rule.outputs, column))),
      h('th.note', null, t('dmn.description')),
      h('th.act'))),
    h('tbody', null, rows))));

  if (editable) {
    root.append(h('div.dmn-actions', null,
      h('button.link-btn', {
        type: 'button', 'data-tour': 'addRule',
        onclick: () => {
          const rule = { inputs: table.inputs.map(() => '-'), outputs: table.outputs.map(() => 'null'), annotation: '' };
          // a new rule goes above the rule that catches everything, otherwise it could never hit
          const last = table.rules[table.rules.length - 1];
          const at = last && isCatchAll(last) && table.hitPolicy === 'FIRST' ? table.rules.length - 1 : table.rules.length;
          table.rules.splice(at, 0, rule);
          onChange('rules');
        }
      }, icon('plus', 13), t('dmn.addRule')),
      h('span.dmn-policy-note', null, t('dmn.hitPolicy.' + (HIT_POLICY[table.hitPolicy] ? table.hitPolicy.replace(/ /g, '') : 'other'), { policy: table.hitPolicy }))));
  }
  return root;
}
