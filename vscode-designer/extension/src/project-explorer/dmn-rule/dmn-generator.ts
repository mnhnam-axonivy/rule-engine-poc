/**
 * Generates a ready-to-edit DMN 1.5 model from fields, so the rule author only fills in rules: one input node per
 * input field, named exactly like the field, and one decision with a decision table, a column per input and output
 * and one placeholder rule. The Business Rule Task of the rule engine maps inputs and results by these names.
 * The same files as the generator of the rule engine connector.
 */

export type DmnType =
  | { kind: 'simple'; feelName: string }
  | { kind: 'enumeration'; name: string; values: string[] }
  | { kind: 'structure'; name: string; fields: DmnField[] }
  | { kind: 'collection'; element: DmnType };

export type DmnField = { name: string; type: DmnType };

export type DmnSpec = {
  modelName: string;
  inputs: DmnField[];
  outputs: DmnField[];
  description?: string;
};

const simple = (feelName: string): DmnType => ({ kind: 'simple', feelName });

export const DmnTypes = {
  STRING: simple('string'),
  NUMBER: simple('number'),
  BOOLEAN: simple('boolean'),
  DATE: simple('date'),
  TIME: simple('time'),
  DATE_TIME: simple('date and time'),
  DAYS_TIME_DURATION: simple('days and time duration'),
  YEARS_MONTHS_DURATION: simple('years and months duration'),
  ANY: simple('Any')
};

const DMN_NAMESPACE = 'https://www.omg.org/spec/DMN/20230324/MODEL/';
const FEEL_NAMESPACE = 'https://www.omg.org/spec/DMN/20230324/FEEL/';
const DMNDI_NAMESPACE = 'https://www.omg.org/spec/DMN/20230324/DMNDI/';
const DC_NAMESPACE = 'http://www.omg.org/spec/DMN/20180521/DC/';
const DI_NAMESPACE = 'http://www.omg.org/spec/DMN/20180521/DI/';
const MODEL_NAMESPACE_PREFIX = 'https://axonivy.com/dmn/';
const EXPORTER = 'Axon Ivy Designer';

/** How deep a nested data class is unfolded into columns of the decision table. */
const MAX_COLUMN_DEPTH = 3;
const NODE_WIDTH = 160;
const NODE_HEIGHT = 80;
const NODE_GAP = 40;
const MARGIN = 60;
const DECISION_Y = 80;
const INPUT_Y = 300;

const FEEL_KEYWORDS = new Set([
  'and',
  'between',
  'else',
  'every',
  'external',
  'false',
  'for',
  'function',
  'if',
  'in',
  'instance',
  'not',
  'null',
  'of',
  'or',
  'return',
  'satisfies',
  'some',
  'then',
  'true'
]);

/** @returns a valid FEEL name for the field name: reserved words get a suffix, illegal characters are replaced */
export const toFeelName = (fieldName: string) => {
  if (!fieldName.trim()) {
    return '_';
  }
  let name = '';
  [...fieldName].forEach((c, i) => {
    const valid = i === 0 ? /[\p{L}_]/u.test(c) : /[\p{L}\p{Nd}_]/u.test(c);
    if (valid) {
      name += c;
    } else if (i === 0 && /\p{Nd}/u.test(c)) {
      name += `_${c}`;
    } else {
      name += '_';
    }
  });
  return FEEL_KEYWORDS.has(name) ? `${name}_` : name;
};

const capitalize = (name: string) => name.substring(0, 1).toUpperCase() + name.substring(1);
const decapitalize = (name: string) => name.substring(0, 1).toLowerCase() + name.substring(1);

/** A stable XML id, so generating the same model twice gives the same file. */
const id = (kind: string, name: string) => `_${kind ? `${kind}_` : ''}${name.replace(/[^A-Za-z0-9_-]/g, '_')}`;

const quote = (value: string) => `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;

/**
 * The name of the decision. With a single output it is named like the output field, so its result is mapped by name;
 * with several outputs like the model, returning a structure with one entry per output field.
 */
export const decisionName = (spec: DmnSpec) => {
  const [single] = spec.outputs;
  const name = spec.outputs.length === 1 && single ? single.name : toFeelName(decapitalize(spec.modelName));
  return spec.inputs.some(input => input.name === name) ? `${name}Result` : name;
};

const normalize = (fields: DmnField[], kind: string) => {
  const names = new Set<string>();
  return fields.map(field => {
    const name = toFeelName(field.name);
    if (names.has(name)) {
      throw new Error(`Duplicate ${kind} field name '${name}'`);
    }
    names.add(name);
    return { name, type: field.type };
  });
};

export const normalizeSpec = (spec: DmnSpec): DmnSpec => {
  const modelName = spec.modelName.trim();
  if (!modelName) {
    throw new Error('The model needs a name');
  }
  const inputs = normalize(spec.inputs, 'input');
  const outputs = normalize(spec.outputs, 'output');
  if (inputs.length === 0 || outputs.length === 0) {
    throw new Error(`Select at least one input and one output field for model '${modelName}'`);
  }
  return { modelName, inputs, outputs, description: spec.description?.trim() || undefined };
};

class Xml {
  private out = '';
  private level = 0;

  line(line: string) {
    this.out += `${line}\n`;
  }

  open(element: string, ...attributes: string[]) {
    this.out += `${this.indent()}<${element}${this.attributes(attributes)}>\n`;
    this.level++;
  }

  empty(element: string, ...attributes: string[]) {
    this.out += `${this.indent()}<${element}${this.attributes(attributes)}/>\n`;
  }

  text(element: string, text: string) {
    this.out += `${this.indent()}<${element}>${Xml.escape(text)}</${element}>\n`;
  }

  close(element: string) {
    this.level--;
    this.out += `${this.indent()}</${element}>\n`;
  }

  toString() {
    return this.out;
  }

  private attributes(attributes: string[]) {
    let result = '';
    for (let i = 0; i + 1 < attributes.length; i += 2) {
      result += ` ${attributes[i]}="${Xml.escape(attributes[i + 1] ?? '')}"`;
    }
    return result;
  }

  private indent() {
    return '  '.repeat(this.level);
  }

  private static escape(value: string) {
    return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
}

/** The item definitions of the model: enums, structures and lists that inputs and outputs refer to. */
class TypeDefinitions {
  private readonly definitions = new Map<string, DmnType>();

  ref(type: DmnType): string {
    switch (type.kind) {
      case 'simple':
        return type.feelName;
      case 'enumeration':
      case 'structure':
        return type.name;
      case 'collection':
        return this.listName(type.element);
    }
  }

  private listName(element: DmnType) {
    const name =
      element.kind === 'simple'
        ? `t${element.feelName.split(' ').map(capitalize).join('')}` // "date and time" becomes tDateAndTimeList
        : this.ref(element);
    return `${name}List`;
  }

  /** Registers the type and everything it is built from. Parts first, so the file reads top-down. */
  register(type: DmnType) {
    if (type.kind === 'structure') {
      type.fields.forEach(field => this.register(field.type));
      this.add(type.name, type);
    } else if (type.kind === 'enumeration') {
      this.add(type.name, type);
    } else if (type.kind === 'collection') {
      this.register(type.element);
      this.add(this.listName(type.element), type);
    }
  }

  freeName(wanted: string) {
    let name = wanted;
    for (let index = 2; this.definitions.has(name); index++) {
      name = `${wanted}${index}`;
    }
    return name;
  }

  write(xml: Xml) {
    this.definitions.forEach((type, name) => {
      if (type.kind === 'enumeration') {
        xml.open('itemDefinition', 'id', id('type', name), 'name', name, 'isCollection', 'false');
        xml.text('typeRef', 'string');
        xml.open('allowedValues', 'id', id('type_values', name));
        // null stays allowed: a field that is not set must not fail the decision
        xml.text('text', `${type.values.map(quote).join(', ')}, null`);
        xml.close('allowedValues');
        xml.close('itemDefinition');
      } else if (type.kind === 'collection') {
        xml.open('itemDefinition', 'id', id('type', name), 'name', name, 'isCollection', 'true');
        xml.text('typeRef', this.ref(type.element));
        xml.close('itemDefinition');
      } else if (type.kind === 'structure') {
        xml.open('itemDefinition', 'id', id('type', name), 'name', name, 'isCollection', 'false');
        for (const field of type.fields) {
          const component = toFeelName(field.name);
          const list = field.type.kind === 'collection';
          const componentType = field.type.kind === 'collection' ? field.type.element : field.type;
          xml.open('itemComponent', 'id', id('type', `${name}_${component}`), 'name', component, 'isCollection', String(list));
          xml.text('typeRef', this.ref(componentType));
          xml.close('itemComponent');
        }
        xml.close('itemDefinition');
      }
    });
  }

  private add(name: string, type: DmnType) {
    if (!this.definitions.has(name)) {
      this.definitions.set(name, type);
    }
  }
}

type Column = { expression: string; typeRef?: string };

/**
 * The columns of an input field: simple values and lists are one column,
 * a nested data class is unfolded into one column per simple field.
 */
const columns = (input: DmnField, types: TypeDefinitions): Column[] => {
  if (input.type.kind !== 'structure') {
    return [{ expression: input.name, typeRef: types.ref(input.type) }];
  }
  const result: Column[] = [];
  unfold(input.name, input.type.fields, 1, types, result);
  return result.length > 0 ? result : [{ expression: input.name }];
};

const unfold = (path: string, fields: DmnField[], depth: number, types: TypeDefinitions, result: Column[]) => {
  for (const field of fields) {
    const fieldPath = `${path}.${toFeelName(field.name)}`;
    if (field.type.kind === 'structure') {
      if (depth < MAX_COLUMN_DEPTH) {
        unfold(fieldPath, field.type.fields, depth + 1, types, result);
      }
    } else if (field.type.kind !== 'collection') {
      result.push({ expression: fieldPath, typeRef: types.ref(field.type) });
    }
  }
};

/** @returns the result of the placeholder rule: a neutral value that is valid for the type */
const placeholder = (type: DmnType) => {
  if (type.kind === 'enumeration') {
    const [first] = type.values;
    if (first !== undefined) {
      return quote(first);
    }
  }
  if (type.kind === 'simple') {
    switch (type.feelName) {
      case 'number':
        return '0';
      case 'string':
        return '""';
      case 'boolean':
        return 'false';
    }
  }
  return 'null';
};

const decisionType = (spec: DmnSpec, decision: string, types: TypeDefinitions) => {
  const [single] = spec.outputs;
  if (spec.outputs.length === 1 && single) {
    return types.ref(single.type);
  }
  const result: DmnType = { kind: 'structure', name: types.freeName(`t${capitalize(decision)}`), fields: spec.outputs };
  types.register(result);
  return result.name;
};

const writeDecision = (xml: Xml, spec: DmnSpec, decision: string, type: string, types: TypeDefinitions) => {
  xml.open('decision', 'id', id('decision', decision), 'name', decision);
  xml.empty('variable', 'id', id('decision_var', decision), 'name', decision, 'typeRef', type);
  for (const input of spec.inputs) {
    xml.open('informationRequirement', 'id', id('requirement', input.name));
    xml.empty('requiredInput', 'href', `#${id('input', input.name)}`);
    xml.close('informationRequirement');
  }
  xml.open('decisionTable', 'id', id('table', decision), 'hitPolicy', 'FIRST');
  const all = spec.inputs.flatMap(input => columns(input, types));
  all.forEach((column, index) => {
    const number = String(index + 1);
    xml.open('input', 'id', id('table_input', number), 'label', column.expression);
    if (column.typeRef === undefined) {
      xml.open('inputExpression', 'id', id('table_input_expression', number));
    } else {
      xml.open('inputExpression', 'id', id('table_input_expression', number), 'typeRef', column.typeRef);
    }
    xml.text('text', column.expression);
    xml.close('inputExpression');
    xml.close('input');
  });
  const named = spec.outputs.length > 1;
  for (const output of spec.outputs) {
    if (named) {
      xml.empty('output', 'id', id('table_output', output.name), 'name', output.name, 'typeRef', types.ref(output.type));
    } else {
      // a single output takes its type from the decision, a type reference here is flagged by the validator
      xml.empty('output', 'id', id('table_output', output.name), 'label', output.name);
    }
  }
  xml.empty('annotation', 'name', 'Description');
  xml.open('rule', 'id', id('rule', '1'));
  all.forEach((_, index) => {
    xml.open('inputEntry', 'id', id('rule_1_input', String(index + 1)));
    xml.text('text', '-');
    xml.close('inputEntry');
  });
  for (const output of spec.outputs) {
    xml.open('outputEntry', 'id', id('rule_1_output', output.name));
    xml.text('text', placeholder(output.type));
    xml.close('outputEntry');
  }
  xml.open('annotationEntry');
  xml.text('text', 'Placeholder: matches everything. Add your rules above this one.');
  xml.close('annotationEntry');
  xml.close('rule');
  xml.close('decisionTable');
  xml.close('decision');
};

const shape = (xml: Xml, elementId: string, x: number, y: number) => {
  xml.open(
    'dmndi:DMNShape',
    'id',
    id('shape', elementId),
    'dmnElementRef',
    elementId,
    'isCollapsed',
    'false',
    'isListedInputData',
    'false'
  );
  xml.empty('dc:Bounds', 'x', String(x), 'y', String(y), 'width', String(NODE_WIDTH), 'height', String(NODE_HEIGHT));
  xml.close('dmndi:DMNShape');
};

const writeDiagram = (xml: Xml, spec: DmnSpec, decision: string) => {
  const inputs = spec.inputs.length;
  const totalWidth = inputs * NODE_WIDTH + (inputs - 1) * NODE_GAP;
  const decisionX = MARGIN + Math.trunc((totalWidth - NODE_WIDTH) / 2);
  xml.open('dmndi:DMNDI');
  xml.open('dmndi:DMNDiagram', 'id', id('diagram', spec.modelName), 'name', 'Default DRD', 'useAlternativeInputDataShape', 'false');
  shape(xml, id('decision', decision), decisionX, DECISION_Y);
  spec.inputs.forEach((input, index) => {
    const x = MARGIN + index * (NODE_WIDTH + NODE_GAP);
    shape(xml, id('input', input.name), x, INPUT_Y);
    xml.open(
      'dmndi:DMNEdge',
      'id',
      id('edge', input.name),
      'dmnElementRef',
      id('requirement', input.name),
      'sourceElement',
      id('shape', id('input', input.name)),
      'targetElement',
      id('shape', id('decision', decision))
    );
    xml.empty('di:waypoint', 'x', String(x + NODE_WIDTH / 2), 'y', String(INPUT_Y));
    xml.empty('di:waypoint', 'x', String(decisionX + NODE_WIDTH / 2), 'y', String(DECISION_Y + NODE_HEIGHT));
    xml.close('dmndi:DMNEdge');
  });
  xml.close('dmndi:DMNDiagram');
  xml.close('dmndi:DMNDI');
};

export const generateDmn = (unnormalized: DmnSpec) => {
  const spec = normalizeSpec(unnormalized);
  const types = new TypeDefinitions();
  spec.inputs.forEach(input => types.register(input.type));
  spec.outputs.forEach(output => types.register(output.type));
  const decision = decisionName(spec);
  const type = decisionType(spec, decision, types);

  const xml = new Xml();
  xml.line('<?xml version="1.0" encoding="UTF-8"?>');
  xml.open(
    'definitions',
    'xmlns',
    DMN_NAMESPACE,
    'xmlns:dmndi',
    DMNDI_NAMESPACE,
    'xmlns:dc',
    DC_NAMESPACE,
    'xmlns:di',
    DI_NAMESPACE,
    'id',
    id('definitions', spec.modelName),
    'name',
    spec.modelName,
    'namespace',
    MODEL_NAMESPACE_PREFIX + id('', spec.modelName).substring(1),
    'expressionLanguage',
    FEEL_NAMESPACE,
    'typeLanguage',
    FEEL_NAMESPACE,
    'exporter',
    EXPORTER
  );
  if (spec.description) {
    xml.text('description', spec.description);
  }
  types.write(xml);
  for (const input of spec.inputs) {
    xml.open('inputData', 'id', id('input', input.name), 'name', input.name);
    xml.empty('variable', 'id', id('input_var', input.name), 'name', input.name, 'typeRef', types.ref(input.type));
    xml.close('inputData');
  }
  writeDecision(xml, spec, decision, type, types);
  writeDiagram(xml, spec, decision);
  xml.close('definitions');
  return xml.toString();
};
