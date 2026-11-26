import { parseXml, childrenOf, childOf, textOf, XmlError } from './xml.js';

export class DmnError extends Error {
  constructor(message) {
    super(message);
    this.name = 'DmnError';
  }
}

const idOf = href => {
  const hash = String(href || '').lastIndexOf('#');
  return hash < 0 ? String(href || '') : String(href).slice(hash + 1);
};

function toTable(node) {
  return {
    hitPolicy: node.attrs.hitPolicy || 'UNIQUE',
    aggregation: node.attrs.aggregation || '',
    inputs: childrenOf(node, 'input').map(input => {
      const expression = childOf(input, 'inputExpression');
      const text = expression ? textOf(expression, 'text') : '';
      return { label: input.attrs.label || text, expression: text, type: (expression && expression.attrs.typeRef) || '' };
    }),
    outputs: childrenOf(node, 'output').map(output => ({
      name: output.attrs.name || '', label: output.attrs.label || output.attrs.name || '', type: output.attrs.typeRef || ''
    })),
    rules: childrenOf(node, 'rule').map(rule => ({
      inputs: childrenOf(rule, 'inputEntry').map(entry => textOf(entry, 'text')),
      outputs: childrenOf(rule, 'outputEntry').map(entry => textOf(entry, 'text')),
      annotation: childrenOf(rule, 'annotationEntry').map(entry => textOf(entry, 'text')).filter(Boolean).join(' ')
    }))
  };
}

export function parseDmn(xml, meta = {}) {
  let root;
  try {
    root = parseXml(xml);
  } catch (e) {
    if (e instanceof XmlError) throw new DmnError('The file is not well-formed XML: ' + e.message);
    throw e;
  }
  if (root.local !== 'definitions') throw new DmnError('The file is not a DMN model: its root element is <' + root.name + '>');

  const types = {};
  childrenOf(root, 'itemDefinition').forEach(item => {
    const allowed = childOf(item, 'allowedValues');
    types[item.attrs.name] = {
      name: item.attrs.name,
      base: textOf(item, 'typeRef') || (childrenOf(item, 'itemComponent').length ? 'structure' : 'Any'),
      collection: item.attrs.isCollection === 'true',
      allowedValues: allowed ? textOf(allowed, 'text') : '',
      components: childrenOf(item, 'itemComponent').map(component => ({
        name: component.attrs.name, type: textOf(component, 'typeRef') || 'Any', collection: component.attrs.isCollection === 'true'
      }))
    };
  });

  const variableType = node => {
    const variable = childOf(node, 'variable');
    return (variable && variable.attrs.typeRef) || '';
  };
  const inputs = childrenOf(root, 'inputData').map(node => ({ id: node.attrs.id || '', name: node.attrs.name || '', type: variableType(node) }));

  const logic = node => {
    const table = childOf(node, 'decisionTable');
    const literal = childOf(node, 'literalExpression');
    return {
      kind: table ? 'table' : literal ? 'literal' : 'other',
      table: table ? toTable(table) : null,
      expression: !table && literal ? textOf(literal, 'text').trim() : ''
    };
  };
  const knowledgeOf = node => childrenOf(node, 'knowledgeRequirement')
    .flatMap(requirement => requirement.children.filter(required => required.local === 'requiredKnowledge').map(required => idOf(required.attrs.href)));

  const decisions = childrenOf(root, 'decision').map(node => {
    const requires = { inputs: [], decisions: [], functions: knowledgeOf(node) };
    childrenOf(node, 'informationRequirement').forEach(requirement => {
      requirement.children.forEach(required => {
        if (required.local === 'requiredInput') requires.inputs.push(idOf(required.attrs.href));
        else if (required.local === 'requiredDecision') requires.decisions.push(idOf(required.attrs.href));
      });
    });
    return {
      id: node.attrs.id || '',
      name: node.attrs.name || '',
      type: variableType(node),
      description: textOf(node, 'description'),
      requires,
      ...logic(node)
    };
  });

  const functions = childrenOf(root, 'businessKnowledgeModel').map(node => {
    const encapsulated = childOf(node, 'encapsulatedLogic') || { attrs: {}, children: [] };
    return {
      id: node.attrs.id || '',
      name: node.attrs.name || '',
      type: encapsulated.attrs.typeRef || '',
      description: textOf(node, 'description'),
      params: childrenOf(encapsulated, 'formalParameter').map(param => ({ name: param.attrs.name || '', type: param.attrs.typeRef || '' })),
      requires: { functions: knowledgeOf(node) },
      function: true,
      ...logic(encapsulated)
    };
  });

  return {
    model: meta.model || root.attrs.name || '',
    file: meta.file || '',
    name: root.attrs.name || '',
    namespace: root.attrs.namespace || '',
    description: textOf(root, 'description'),
    types,
    inputs,
    decisions,
    functions
  };
}

export function decisionOf(model, name) {
  return model.decisions.find(decision => decision.name === name) || null;
}

export function functionOf(model, name) {
  return (model.functions || []).find(fn => fn.name === name) || null;
}

export function logicOf(model, name) {
  return decisionOf(model, name) || functionOf(model, name);
}

/** Everything the editor shows, the functions first: they are what the decisions call. */
export function logicsOf(model) {
  return [...(model.functions || []), ...model.decisions];
}

/**
 * A type for a reader, as DmnModelInfo of the connector has it: a FEEL type as it is, an item definition resolved to
 * what it stands for (tSupplierRating → string), a structure as "structure", a collection as "list of …".
 */
export function typeLabel(model, typeRef, depth = 0) {
  if (!typeRef) return null;
  let name = String(typeRef).trim();
  const prefix = name.indexOf(':');
  if (prefix > 0 && !(model.types || {})[name]) name = name.slice(prefix + 1);
  const item = (model.types || {})[name];
  if (!item || depth > 8) return name;
  const base = item.base === 'structure' ? 'structure' : item.base === 'Any' ? name : typeLabel(model, item.base, depth + 1);
  return item.collection ? 'list of ' + base : base;
}

export function outputsOf(model, decision) {
  const columns = decision.table ? decision.table.outputs : [];
  if (columns.length > 1) return columns.map(column => ({ name: column.name || column.label, type: typeLabel(model, column.type) }));
  return [{ name: decision.name, type: typeLabel(model, decision.type || (columns[0] && columns[0].type)) }];
}

export function describeNames(names, types) {
  return names.map(name => (types[name] ? name + ' (' + types[name] + ')' : name)).join(', ');
}

export function inputTypes(model) {
  const types = {};
  model.inputs.forEach(input => { const type = typeLabel(model, input.type); if (type) types[input.name] = type; });
  return types;
}

export function describeOutputs(model, decision) {
  const outputs = outputsOf(model, decision);
  const types = {};
  outputs.forEach(output => { if (output.type) types[output.name] = output.type; });
  return describeNames(outputs.map(output => output.name), types);
}

export function requiredInputs(model, decisionName) {
  if (!decisionName) return model.inputs.map(input => input.name);
  const start = decisionOf(model, decisionName);
  if (!start) return null;
  const ids = [];
  const visited = new Set();
  const collect = decision => {
    if (!decision || visited.has(decision.id)) return;
    visited.add(decision.id);
    decision.requires.inputs.forEach(id => { if (!ids.includes(id)) ids.push(id); });
    decision.requires.decisions.forEach(id => collect(model.decisions.find(d => d.id === id)));
  };
  collect(start);
  return ids.map(id => model.inputs.find(input => input.id === id)).filter(Boolean).map(input => input.name);
}

export function evaluationOrder(model, decisionName) {
  const order = [];
  const visiting = new Set();
  const visit = decision => {
    if (!decision || order.includes(decision) || visiting.has(decision.id)) return;
    visiting.add(decision.id);
    decision.requires.decisions.forEach(id => visit(model.decisions.find(d => d.id === id)));
    order.push(decision);
  };
  if (decisionName) visit(decisionOf(model, decisionName));
  else model.decisions.forEach(visit);
  return order;
}

/**
 * Adds an input to a model and a column for it to the table of a decision. The existing rules get "-" for it,
 * so they decide as before.
 */
export function addInput(model, decisionName, { name, type = 'string' }) {
  const decision = decisionOf(model, decisionName);
  if (!decision || decision.kind !== 'table' || !name) return false;
  let input = model.inputs.find(candidate => candidate.name === name);
  if (!input) {
    input = { id: '_input_' + name, name, type };
    model.inputs.push(input);
  }
  if (!decision.requires.inputs.includes(input.id)) decision.requires.inputs.push(input.id);
  if (!decision.table.inputs.some(column => column.expression === name)) {
    decision.table.inputs.push({ label: name, expression: name, type });
    decision.table.rules.forEach(rule => rule.inputs.push('-'));
  }
  return true;
}

export function cloneDmn(model) {
  return JSON.parse(JSON.stringify(model));
}

/**
 * What makes two models the same version: everything that decides. Layout and ids of the file
 * are not part of it.
 */
export function fingerprint(model) {
  const logic = item => [item.name, item.type, item.kind, item.expression,
    item.table && [item.table.hitPolicy, item.table.aggregation || '', item.table.inputs, item.table.outputs, item.table.rules.map(rule => [rule.inputs, rule.outputs])]];
  return JSON.stringify({
    name: model.name,
    inputs: model.inputs.map(input => [input.name, input.type]),
    decisions: model.decisions.map(logic),
    functions: (model.functions || []).map(fn => [...logic(fn), fn.params.map(param => param.name)])
  });
}
