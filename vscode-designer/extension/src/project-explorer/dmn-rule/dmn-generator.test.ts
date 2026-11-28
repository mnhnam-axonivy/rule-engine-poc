import { expect, test } from 'vitest';
import { decisionName, type DmnField, type DmnType, DmnTypes, generateDmn, toFeelName } from './dmn-generator';

const segment: DmnType = { kind: 'enumeration', name: 'tSegment', values: ['GOLD', 'SILVER', 'STANDARD'] };
const address: DmnType = {
  kind: 'structure',
  name: 'tAddress',
  fields: [
    { name: 'city', type: DmnTypes.STRING },
    { name: 'zip', type: DmnTypes.NUMBER }
  ]
};
const customer: DmnType = {
  kind: 'structure',
  name: 'tCustomer',
  fields: [
    { name: 'segment', type: segment },
    { name: 'tags', type: { kind: 'collection', element: DmnTypes.STRING } },
    { name: 'address', type: address }
  ]
};
const field = (name: string, type: DmnType): DmnField => ({ name, type });

test.describe('toFeelName', () => {
  test('a field name stays as it is', () => {
    expect(toFeelName('purchaseAmount')).toBe('purchaseAmount');
  });

  test('reserved words of FEEL get a suffix', () => {
    expect(toFeelName('function')).toBe('function_');
    expect(toFeelName('in')).toBe('in_');
  });

  test('illegal characters become underscores, a leading digit is kept behind one', () => {
    expect(toFeelName('total-amount')).toBe('total_amount');
    expect(toFeelName('2nd')).toBe('_2nd');
    expect(toFeelName(' ')).toBe('_');
  });
});

test.describe('decisionName', () => {
  test('with one output the decision is named like it, so its result is mapped by name', () => {
    expect(
      decisionName({
        modelName: 'member-discount',
        inputs: [field('amount', DmnTypes.NUMBER)],
        outputs: [field('discount', DmnTypes.NUMBER)]
      })
    ).toBe('discount');
  });

  test('with several outputs like the model', () => {
    const outputs = [field('discount', DmnTypes.NUMBER), field('upgrade', segment)];
    expect(decisionName({ modelName: 'Member-discount', inputs: [field('amount', DmnTypes.NUMBER)], outputs })).toBe('member_discount');
  });

  test('never like an input', () => {
    expect(
      decisionName({ modelName: 'm', inputs: [field('discount', DmnTypes.NUMBER)], outputs: [field('discount', DmnTypes.NUMBER)] })
    ).toBe('discountResult');
  });
});

test.describe('generateDmn', () => {
  test('an input node and a typed column per simple input', () => {
    const dmn = generateDmn({
      modelName: 'discount',
      inputs: [field('amount', DmnTypes.NUMBER)],
      outputs: [field('discount', DmnTypes.NUMBER)]
    });
    expect(dmn).toContain(
      '<inputData id="_input_amount" name="amount">\n    <variable id="_input_var_amount" name="amount" typeRef="number"/>'
    );
    expect(dmn).toContain('<inputExpression id="_table_input_expression_1" typeRef="number">\n          <text>amount</text>');
    expect(dmn).toContain(
      '<decision id="_decision_discount" name="discount">\n    <variable id="_decision_var_discount" name="discount" typeRef="number"/>'
    );
    expect(dmn).toContain('<output id="_table_output_discount" label="discount"/>');
    expect(dmn).toContain('<outputEntry id="_rule_1_output_discount">\n          <text>0</text>');
  });

  test('a data class is a structure, unfolded into a column per simple field; lists are no column', () => {
    const dmn = generateDmn({
      modelName: 'discount',
      inputs: [field('customer', customer)],
      outputs: [field('discount', DmnTypes.NUMBER)]
    });
    const labels = [...dmn.matchAll(/<input id="[^"]+" label="([^"]+)">/g)].map(match => match[1]);
    expect(labels).toEqual(['customer.segment', 'customer.address.city', 'customer.address.zip']);
    expect(dmn).toContain('<variable id="_input_var_customer" name="customer" typeRef="tCustomer"/>');
    expect(dmn).toContain('<itemComponent id="_type_tCustomer_tags" name="tags" isCollection="true">\n      <typeRef>string</typeRef>');
  });

  test('the parts of a type are defined before it, an enum allows its constants and null', () => {
    const dmn = generateDmn({
      modelName: 'discount',
      inputs: [field('customer', customer)],
      outputs: [field('discount', DmnTypes.NUMBER)]
    });
    const definitions = [...dmn.matchAll(/<itemDefinition id="[^"]+" name="([^"]+)"/g)].map(match => match[1]);
    expect(definitions).toEqual(['tSegment', 'tStringList', 'tAddress', 'tCustomer']);
    expect(dmn).toContain('<text>&quot;GOLD&quot;, &quot;SILVER&quot;, &quot;STANDARD&quot;, null</text>');
  });

  test('several outputs are named columns of a structured result', () => {
    const outputs = [field('discount', DmnTypes.NUMBER), field('upgrade', segment), field('approved', DmnTypes.BOOLEAN)];
    const dmn = generateDmn({ modelName: 'pricing', inputs: [field('amount', DmnTypes.NUMBER)], outputs });
    expect(dmn).toContain('<variable id="_decision_var_pricing" name="pricing" typeRef="tPricing"/>');
    expect(dmn).toContain('<output id="_table_output_upgrade" name="upgrade" typeRef="tSegment"/>');
    expect(dmn).toContain('<outputEntry id="_rule_1_output_upgrade">\n          <text>&quot;GOLD&quot;</text>');
    expect(dmn).toContain('<outputEntry id="_rule_1_output_approved">\n          <text>false</text>');
  });

  test('the inputs are laid out side by side below the decision', () => {
    const inputs = [field('amount', DmnTypes.NUMBER), field('segment', segment), field('vip', DmnTypes.BOOLEAN)];
    const dmn = generateDmn({ modelName: 'discount', inputs, outputs: [field('discount', DmnTypes.NUMBER)] });
    expect(dmn).toContain(
      '<dmndi:DMNShape id="_shape__decision_discount" dmnElementRef="_decision_discount" isCollapsed="false" isListedInputData="false">\n        <dc:Bounds x="260" y="80"'
    );
    expect(dmn).toContain('<dc:Bounds x="460" y="300" width="160" height="80"/>');
  });

  test('two fields with the same name are refused', () => {
    expect(() =>
      generateDmn({
        modelName: 'm',
        inputs: [field('amount', DmnTypes.NUMBER), field('amount', DmnTypes.STRING)],
        outputs: [field('x', DmnTypes.NUMBER)]
      })
    ).toThrow("Duplicate input field name 'amount'");
  });
});
