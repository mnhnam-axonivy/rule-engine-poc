import { expect, test } from 'vitest';
import { dmnRuleName, dmnRuleTemplate, isValidDmnRuleName } from './dmn-rule-file';

test.describe('dmnRuleName', () => {
  test('is the typed name without spaces around it', () => {
    expect(dmnRuleName('  member-discount ')).toBe('member-discount');
  });

  test('without the extension of the file', () => {
    expect(dmnRuleName('member-discount.dmn')).toBe('member-discount');
    expect(dmnRuleName('member-discount.DMN')).toBe('member-discount');
  });
});

test.describe('isValidDmnRuleName', () => {
  test('letters, digits, dashes, underscores and dots', () => {
    expect(isValidDmnRuleName('member-discount')).toBe(true);
    expect(isValidDmnRuleName('invoice_approval.v2')).toBe(true);
    expect(isValidDmnRuleName('2026-pricing')).toBe(true);
  });

  test('starts with a letter or digit', () => {
    expect(isValidDmnRuleName('-discount')).toBe(false);
    expect(isValidDmnRuleName('.discount')).toBe(false);
    expect(isValidDmnRuleName('_discount')).toBe(false);
  });

  test('no spaces, no other characters, not empty', () => {
    expect(isValidDmnRuleName('member discount')).toBe(false);
    expect(isValidDmnRuleName('rabatt/kunde')).toBe(false);
    expect(isValidDmnRuleName('räbatt')).toBe(false);
    expect(isValidDmnRuleName('')).toBe(false);
  });
});

test.describe('dmnRuleTemplate', () => {
  test('the model is named like the rule', () => {
    const dmn = dmnRuleTemplate('member-discount');
    expect(dmn).toContain('id="_definitions_member-discount" name="member-discount"');
    expect(dmn).toContain('namespace="https://axonivy.com/dmn/member-discount"');
    expect(dmn).toContain('<dmndi:DMNDiagram id="_diagram_member-discount"');
  });

  test('a dot is no character of an XML id', () => {
    const dmn = dmnRuleTemplate('pricing.v2');
    expect(dmn).toContain('id="_definitions_pricing_v2" name="pricing.v2"');
    expect(dmn).toContain('namespace="https://axonivy.com/dmn/pricing_v2"');
  });

  test('a decision table with one input, one output and a placeholder rule', () => {
    const dmn = dmnRuleTemplate('member-discount');
    expect(dmn).toMatch(
      /^<\?xml version="1.0" encoding="UTF-8"\?>\n<definitions xmlns="https:\/\/www.omg.org\/spec\/DMN\/20230324\/MODEL\/"/
    );
    expect(dmn).toContain('<inputData id="_input_input" name="input">');
    expect(dmn).toContain('<decision id="_decision_output" name="output">');
    expect(dmn).toContain('<decisionTable id="_table_output" hitPolicy="FIRST">');
    expect(dmn.match(/<rule /g)).toHaveLength(1);
    expect(dmn).toContain('<text>Placeholder: matches everything. Add your rules above this one.</text>');
  });
});
