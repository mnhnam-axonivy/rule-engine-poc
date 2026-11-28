import { expect, test } from 'vitest';
import { type DataClass, displayType, DmnTypeMapper, enumConstants, parseDataClass, ruleFields } from './dataclass-fields';
import { DmnTypes } from './dmn-generator';

const dataClass = (qualifiedName: string, fields: Array<[string, string]>): DataClass => ({
  qualifiedName,
  simpleName: qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1),
  namespace: qualifiedName.substring(0, qualifiedName.lastIndexOf('.')),
  project: 'demo',
  projectDir: '/work/demo',
  fields: fields.map(([name, type]) => ({ name, type }))
});

const customer = dataClass('com.acme.Customer', [
  ['name', 'String'],
  ['segment', 'com.acme.Segment'],
  ['address', 'com.acme.Address'],
  ['friend', 'com.acme.Customer']
]);
const address = dataClass('com.acme.Address', [['city', 'String']]);
const otherCustomer = dataClass('com.other.Customer', [['id', 'Number']]);
const mapper = () =>
  new DmnTypeMapper([customer, address, otherCustomer], qualifiedName =>
    qualifiedName === 'com.acme.Segment' ? ['GOLD', 'SILVER'] : undefined
  );

test.describe('parseDataClass', () => {
  test('a field without a type is a String', () => {
    const json = JSON.stringify({
      simpleName: 'Member',
      namespace: 'com.acme',
      fields: [{ name: 'name' }, { name: 'amount', type: 'Number' }]
    });
    expect(parseDataClass(json, '/work/demo')).toEqual({
      qualifiedName: 'com.acme.Member',
      simpleName: 'Member',
      namespace: 'com.acme',
      project: 'demo',
      projectDir: '/work/demo',
      fields: [
        { name: 'name', type: 'String' },
        { name: 'amount', type: 'Number' }
      ]
    });
  });

  test('a file without a simple name is no data class', () => {
    expect(parseDataClass('{"fields": []}', '/work/demo')).toBeUndefined();
  });
});

test.describe('ruleFields', () => {
  test('every field of every data class, with its declared type without packages', () => {
    expect(ruleFields([address])).toEqual([
      { id: 'demo:com.acme.Address.city', name: 'city', dataClass: 'Address', namespace: 'com.acme', project: 'demo', type: 'String' }
    ]);
    expect(displayType('List<com.acme.Address>')).toBe('List<Address>');
    expect(displayType('java.lang.Integer')).toBe('Integer');
  });
});

test.describe('enumConstants', () => {
  test('the constants of an enum, also with arguments, annotations, comments and a body', () => {
    const source = `package com.acme;
      /** the segments */
      public enum Segment {
        @Deprecated GOLD("g"), // the best
        SILVER("s") { @Override public String toString() { return "silver"; } },
        /* the rest */ STANDARD;
        private final String code;
        Segment(String code) { this.code = code; }
        Segment() { this(""); }
      }`;
    expect(enumConstants(source, 'Segment')).toEqual(['GOLD', 'SILVER', 'STANDARD']);
  });

  test('a class is no enum', () => {
    expect(enumConstants('public class Segment { }', 'Segment')).toBeUndefined();
  });
});

test.describe('DmnTypeMapper', () => {
  test('the types of the Ivy scripting language and of Java', () => {
    const types = mapper();
    expect(types.toDmnType('String')).toEqual(DmnTypes.STRING);
    expect(types.toDmnType('Number')).toEqual(DmnTypes.NUMBER);
    expect(types.toDmnType('java.lang.Integer')).toEqual(DmnTypes.NUMBER);
    expect(types.toDmnType('java.lang.Boolean')).toEqual(DmnTypes.BOOLEAN);
    expect(types.toDmnType('Date')).toEqual(DmnTypes.DATE);
    expect(types.toDmnType('DateTime')).toEqual(DmnTypes.DATE_TIME);
    expect(types.toDmnType('java.util.Date')).toEqual(DmnTypes.DATE_TIME);
    expect(types.toDmnType('Time')).toEqual(DmnTypes.TIME);
    expect(types.toDmnType('Duration')).toEqual(DmnTypes.DAYS_TIME_DURATION);
    expect(types.toDmnType('List<String>')).toEqual({ kind: 'collection', element: DmnTypes.STRING });
    expect(types.toDmnType('java.util.List<java.lang.Integer>')).toEqual({ kind: 'collection', element: DmnTypes.NUMBER });
    expect(types.toDmnType('File')).toEqual(DmnTypes.ANY);
    expect(types.toDmnType('java.lang.Object')).toEqual(DmnTypes.ANY);
  });

  test('an enum is the list of its constants', () => {
    expect(mapper().toDmnType('com.acme.Segment')).toEqual({ kind: 'enumeration', name: 'tSegment', values: ['GOLD', 'SILVER'] });
  });

  test('a data class is a structure of its fields; a recursive field ends as Any', () => {
    expect(mapper().toDmnType('com.acme.Customer')).toEqual({
      kind: 'structure',
      name: 'tCustomer',
      fields: [
        { name: 'name', type: DmnTypes.STRING },
        { name: 'segment', type: { kind: 'enumeration', name: 'tSegment', values: ['GOLD', 'SILVER'] } },
        { name: 'address', type: { kind: 'structure', name: 'tAddress', fields: [{ name: 'city', type: DmnTypes.STRING }] } },
        { name: 'friend', type: DmnTypes.ANY }
      ]
    });
  });

  test('two data classes with the same simple name stay apart', () => {
    const types = mapper();
    expect(types.toDmnType('com.acme.Customer')).toMatchObject({ name: 'tCustomer' });
    expect(types.toDmnType('com.other.Customer')).toMatchObject({ name: 'tCustomer2' });
    expect(types.toDmnType('com.acme.Customer')).toMatchObject({ name: 'tCustomer' });
  });
});
