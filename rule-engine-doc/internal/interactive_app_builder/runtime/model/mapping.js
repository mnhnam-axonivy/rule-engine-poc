/**
 * How the Business Rule Task connects process data and a decision, following the connector
 * (rule-engine: FieldSelection, FeelNames, FieldLocator, ValueConverter):
 *
 *   - an input is read from the process data field with its name; fields of nested data
 *     classes are found too, the field closest to the root wins
 *   - a result is written to the field with its name
 *   - "Input fields" / "Output fields" only describe a new DMN: field paths such as
 *     `in.member.type`, each becoming an input or output named like its field
 */

/** Words of the FEEL grammar: a variable with one of these names cannot be used in an expression. */
const KEYWORDS = new Set(['and', 'between', 'else', 'every', 'external', 'false', 'for', 'function', 'if', 'in', 'instance',
  'not', 'null', 'of', 'or', 'return', 'satisfies', 'some', 'then', 'true']);

const MAX_DEPTH = 4;

export class MappingError extends Error {
  constructor(message) {
    super(message);
    this.name = 'MappingError';
  }
}

/** A valid FEEL name for a field name: reserved words get "_" appended, illegal characters are replaced. */
export function toFeelName(fieldName) {
  const raw = String(fieldName || '');
  if (!raw.trim()) return '_';
  let name = '';
  for (let i = 0; i < raw.length; i++) {
    const c = raw[i];
    const valid = i === 0 ? /[A-Za-z_]/.test(c) : /[A-Za-z0-9_]/.test(c);
    if (valid) name += c;
    else if (i === 0 && /\d/.test(c)) name += '_' + c;
    else name += '_';
  }
  return KEYWORDS.has(name) ? name + '_' : name;
}

/** The field name a FEEL name was derived from: `function` for `function_`. */
export function toFieldName(feelName) {
  const name = String(feelName || '');
  return name.endsWith('_') && KEYWORDS.has(name.slice(0, -1)) ? name.slice(0, -1) : name;
}

/** The segments of a path without its root variable: [member, type] for in.member.type. */
function segments(path) {
  const all = path.trim().split('.');
  return all.length > 1 && (all[0] === 'in' || all[0] === 'out') ? all.slice(1) : all;
}

function lastSegments(path, depth) {
  const parts = segments(path);
  const from = Math.max(0, parts.length - depth);
  return parts[from] + parts.slice(from + 1).map(part => part[0].toUpperCase() + part.slice(1)).join('');
}

/**
 * Names for field paths: the last segment, with parent segments added until the names differ.
 * in.member.type and in.order.type become memberType and orderType.
 * @returns {Map<string, string>} name by path, in the order of the paths
 */
export function uniqueNames(paths) {
  const distinct = [];
  paths.forEach(path => {
    const trimmed = String(path || '').trim();
    if (trimmed && !distinct.includes(trimmed)) distinct.push(trimmed);
  });
  const depths = new Map(distinct.map(path => [path, 1]));
  for (let changed = true; changed;) {
    changed = false;
    const counts = new Map();
    distinct.forEach(path => {
      const name = toFeelName(lastSegments(path, depths.get(path)));
      counts.set(name, (counts.get(name) || 0) + 1);
    });
    distinct.forEach(path => {
      const depth = depths.get(path);
      if (counts.get(toFeelName(lastSegments(path, depth))) > 1 && depth < segments(path).length) {
        depths.set(path, depth + 1);
        changed = true;
      }
    });
  }
  const names = new Map();
  const taken = new Set();
  distinct.forEach(path => {
    const name = toFeelName(lastSegments(path, depths.get(path)));
    let unique = name;
    for (let index = 2; taken.has(unique); index++) unique = name + index;
    taken.add(unique);
    names.set(path, unique);
  });
  return names;
}

/**
 * The fields selected for a new DMN: field paths separated by comma, semicolon or line break.
 * @returns {{ name: string, expression: string }[]}  in the order of selection
 */
export function parseFieldSelection(text) {
  const paths = [];
  String(text == null ? '' : text).split(/[,;\r\n]/).forEach(entry => {
    const path = entry.trim();
    if (path && !paths.includes(path)) paths.push(path);
  });
  const names = uniqueNames(paths);
  const selection = [];
  paths.forEach(path => {
    const name = names.get(path);
    if (!selection.some(known => known.name === name)) selection.push({ name, expression: path });
  });
  return selection;
}

// ── types of the process data ────────────────────────────────────────────────

const NUMERIC = /(^|\.)(Number|Integer|Long|Double|Float|Short|Byte|BigDecimal|BigInteger)$/;

/** 'string' | 'number' | 'boolean' | 'date' | 'datetime' | 'time' | 'duration' | 'list' | 'class' | 'any' */
export function kindOf(type, classes = {}) {
  const name = String(type || 'String');
  if (name === 'String' || name === 'java.lang.String') return 'string';
  if (NUMERIC.test(name)) return 'number';
  if (/(^|\.)Boolean$/.test(name)) return 'boolean';
  if (name === 'Date' || name === 'ch.ivyteam.ivy.scripting.objects.Date' || name === 'java.time.LocalDate') return 'date';
  if (name === 'DateTime' || name === 'ch.ivyteam.ivy.scripting.objects.DateTime') return 'datetime';
  if (name === 'Time') return 'time';
  if (name === 'Duration') return 'duration';
  if (/^(java\.util\.)?List</.test(name)) return 'list';
  if (classes[name]) return 'class';
  return 'any';
}

/** The FEEL type a field has in a generated DMN. */
export function feelTypeOf(type, classes = {}) {
  return { string: 'string', number: 'number', boolean: 'boolean', date: 'date', datetime: 'date and time', time: 'time', duration: 'days and time duration' }[kindOf(type, classes)] || 'Any';
}

/** A name as the tolerant search compares it: letters and digits, lower case. */
export const normalize = name => String(name).replace(/[^A-Za-z0-9]/g, '').toLowerCase();

/**
 * Searches a field by name, level by level. An exact name wins over one that only differs in
 * case, blanks or separators ("Risk Level" finds riskLevel).
 * @param {{ dataClass: Object, classes: Object }} model
 * @returns {{ expression: string, type: string } | null}
 */
export function findField(model, name) {
  const wanted = toFieldName(name);
  return search(model, wanted, false) || search(model, wanted, true);
}

function search(model, name, tolerant) {
  const wanted = tolerant ? normalize(name) : name;
  const queue = [{ cls: model.dataClass, expression: 'in', depth: 0, parents: [] }];
  while (queue.length) {
    const node = queue.shift();
    const hit = node.cls.fields.find(field => (tolerant ? normalize(field.name) : field.name) === wanted);
    if (hit) return { expression: node.expression + '.' + hit.name, type: hit.type || 'String' };
    if (node.depth >= MAX_DEPTH) continue;
    node.cls.fields.forEach(field => {
      const nested = model.classes[field.type];
      if (nested && nested !== node.cls && !node.parents.includes(nested)) {
        queue.push({ cls: nested, expression: node.expression + '.' + field.name, depth: node.depth + 1, parents: [...node.parents, node.cls] });
      }
    });
  }
  return null;
}

/** The declared type of a field path such as in.member.type, or null when it leads to no field. */
export function typeOfPath(model, expression) {
  const path = String(expression).trim().split('.').map(part => part.trim());
  if (path.length < 2 || (path[0] !== 'in' && path[0] !== 'out')) return null;
  let cls = model.dataClass;
  let type = null;
  for (let i = 1; i < path.length; i++) {
    const field = cls ? cls.fields.find(candidate => candidate.name === path[i]) : null;
    if (!field) return null;
    type = field.type || 'String';
    cls = model.classes[type] || null;
  }
  return type;
}

/** A plain field path (in.member.type), as opposed to a calculation. */
export const isFieldPath = expression => /^(in|out)(\.[A-Za-z_$][\w$]*)+$/.test(String(expression).trim());

/** Reads a field path from the process data. A nested object that is not set gives null. */
export function readPath(data, expression) {
  const path = String(expression).trim().split('.').slice(1);
  let value = data;
  for (const part of path) {
    if (value === null || value === undefined) return null;
    value = value[part.trim()];
  }
  return value === undefined ? null : value;
}

/** A value of the process data as the JSON value the runtime gets. */
export function toJson(value) {
  if (value === undefined || value === null) return null;
  if (Array.isArray(value)) return value.map(toJson);
  if (typeof value === 'object') {
    const json = {};
    Object.keys(value).filter(key => !key.startsWith('__')).forEach(key => { json[toFeelName(key)] = toJson(value[key]); });
    return json;
  }
  return value;
}

const describe = value => (value === null ? 'null' : typeof value === 'string' ? 'String' : typeof value === 'number' ? 'Number' : typeof value === 'boolean' ? 'Boolean' : Array.isArray(value) ? 'List' : 'structure');

/**
 * A value of a decision result in the type of the field that takes it.
 * @throws {MappingError} when the value does not fit the type
 */
export function fromJson(value, type, classes = {}) {
  if (value === null || value === undefined) return null;
  const kind = kindOf(type, classes);
  const mismatch = () => new MappingError("The value '" + (typeof value === 'object' ? JSON.stringify(value) : value) + "' (" + describe(value) + ') cannot be converted to ' + String(type || 'String').split('.').pop());
  switch (kind) {
    case 'string':
      if (typeof value === 'object') throw mismatch();
      return String(value);
    case 'number':
      if (typeof value === 'number') return value;
      if (typeof value === 'string' && /^\s*-?(\d+\.?\d*|\.\d+)\s*$/.test(value)) return Number(value);
      throw mismatch();
    case 'boolean':
      if (typeof value === 'boolean') return value;
      if (value === 'true' || value === 'false') return value === 'true';
      throw mismatch();
    case 'class': {
      if (typeof value !== 'object' || Array.isArray(value)) throw mismatch();
      const cls = classes[type];
      const typed = { __type: cls.name };
      cls.fields.forEach(field => {
        const key = Object.prototype.hasOwnProperty.call(value, toFeelName(field.name)) ? toFeelName(field.name) : field.name;
        typed[field.name] = fromJson(value[key], field.type, classes);
      });
      return typed;
    }
    case 'date':
    case 'datetime':
    case 'time':
    case 'duration':
      if (typeof value !== 'string') throw mismatch();
      return value;
    case 'list': {
      // a list of a data class: each element becomes an object of that class
      const list = Array.isArray(value) ? value : [value];
      const element = elementTypeOf(type);
      return list.map(entry => fromJson(entry, element, classes));
    }
    default:
      return value;
  }
}

/** The type of the elements of a list type: com.acme.Line for List<com.acme.Line>, Object without one. */
export function elementTypeOf(type) {
  const m = /<\s*([^<>]+?)\s*>$/.exec(String(type || ''));
  return m ? m[1] : 'Object';
}
