export class FeelError extends Error {
  constructor(message, kind = 'syntax') {
    super(message);
    this.name = 'FeelError';
    this.kind = kind;
  }
}

export class FeelDate {
  constructor(iso) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(String(iso))) throw new FeelError('"' + iso + '" is not a date like "2026-11-19"');
    this.iso = String(iso);
  }
  valueOf() { return this.iso; }
  toString() { return this.iso; }
  toJSON() { return this.iso; }
}

const NUMBER = /^[-+]?(\d+\.?\d*|\.\d+)$/;
const isNumber = value => typeof value === 'number' && !Number.isNaN(value);
const isDate = value => value instanceof FeelDate;
const isContext = value => value !== null && typeof value === 'object' && !Array.isArray(value) && !isDate(value) && !value.__feelFunction;
/** One arithmetic step: 0.1 + 0.2 is 0.3, as with decimals. */
export const clean = n => (isNumber(n) && Number.isFinite(n) ? Number(n.toPrecision(15)) : n);

export function splitList(text) {
  const parts = [];
  let depth = 0;
  let quote = false;
  let current = '';
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (quote) {
      current += ch;
      if (ch === '\\' && i + 1 < text.length) current += text[++i];
      else if (ch === '"') quote = false;
      continue;
    }
    if (ch === '"') { quote = true; current += ch; continue; }
    if (ch === '(' || ch === '[' || ch === '{') depth++;
    else if (ch === ')' || ch === ']' || ch === '}') depth--;
    if (ch === ',' && depth <= 0) { parts.push(current.trim()); current = ''; continue; }
    current += ch;
  }
  if (quote) throw new FeelError('A text is not closed with "');
  parts.push(current.trim());
  return parts;
}

export function literal(source) {
  const text = String(source == null ? '' : source).trim();
  if (text === '') throw new FeelError('A value is missing');
  if (text === 'null') return null;
  if (text === 'true') return true;
  if (text === 'false') return false;
  if (NUMBER.test(text)) return Number(text);
  if (text[0] === '"') {
    if (text.length < 2 || text[text.length - 1] !== '"') throw new FeelError('The text ' + text + ' is not closed with "');
    return text.slice(1, -1).replace(/\\(.)/g, '$1');
  }
  throw new FeelError('"' + text + '" is not a value this simulation understands. Use a number, a "text" in quotes, true, false or null.', 'unsupported');
}

function operand(text) {
  try {
    return literal(text);
  } catch (e) {
    if (!(e instanceof FeelError) || e.kind !== 'unsupported') throw e;
    return evaluateExpression(text, {});
  }
}

function order(a, b) {
  if (a === null || a === undefined || b === null || b === undefined) return null;
  if (isNumber(a) && isNumber(b)) return a < b ? -1 : a > b ? 1 : 0;
  if (typeof a === 'string' && typeof b === 'string') return a < b ? -1 : a > b ? 1 : 0;
  if (isDate(a) && isDate(b)) return a.iso < b.iso ? -1 : a.iso > b.iso ? 1 : 0;
  return null;
}

export function same(a, b) {
  if (a === undefined) a = null;
  if (b === undefined) b = null;
  if (a === null || b === null) return a === b;
  if (isDate(a) || isDate(b)) return isDate(a) && isDate(b) && a.iso === b.iso;
  if (Array.isArray(a) || Array.isArray(b)) {
    return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((value, i) => same(value, b[i]));
  }
  if (isContext(a) || isContext(b)) {
    if (!isContext(a) || !isContext(b)) return false;
    const keys = Object.keys(a);
    return keys.length === Object.keys(b).length && keys.every(key => same(a[key], b[key]));
  }
  return a === b;
}

function compare(op, value, operandValue) {
  if (op === '=') return same(value, operandValue);
  if (op === '!=') return !same(value, operandValue);
  // an ordering needs two values of the same kind; with null there is no answer, so no match
  const o = order(value, operandValue);
  if (o === null) return false;
  if (op === '<') return o < 0;
  if (op === '<=') return o <= 0;
  if (op === '>') return o > 0;
  return o >= 0;
}

function matchesOne(test, value) {
  if (test === '-' || test === '') return true;
  const not = /^not\s*\(([\s\S]*)\)$/.exec(test);
  if (not) return !matches(not[1], value);
  const range = /^([[(\]])\s*(.+?)\s*\.\.\s*(.+?)\s*([\])[])$/.exec(test);
  if (range) {
    const low = operand(range[2]);
    const high = operand(range[3]);
    if (order(low, high) === null) throw new FeelError('The range ' + test + ' needs two numbers or two dates', 'unsupported');
    if (order(value, low) === null) return false;
    const lowOk = range[1] === '[' ? order(value, low) >= 0 : order(value, low) > 0;
    const highOk = range[4] === ']' ? order(value, high) <= 0 : order(value, high) < 0;
    return lowOk && highOk;
  }
  const comparison = /^(<=|>=|!=|<|>|=)\s*([\s\S]+)$/.exec(test);
  if (comparison) return compare(comparison[1], value, operand(comparison[2]));
  return compare('=', value === undefined ? null : value, operand(test));
}

export function matches(condition, value) {
  const text = String(condition == null ? '' : condition).trim();
  if (text === '-' || text === '') return true;
  return splitList(text).some(part => matchesOne(part, value));
}

export function validateCondition(condition) {
  try {
    matches(condition, null);
    return null;
  } catch (e) {
    if (e instanceof FeelError) return e;
    throw e;
  }
}

export function validateResult(text) {
  try {
    literal(text);
    return null;
  } catch (e) {
    if (e instanceof FeelError) return e;
    throw e;
  }
}

export function validateExpression(text) {
  try {
    parseExpression(text);
    return null;
  } catch (e) {
    if (e instanceof FeelError) return e;
    throw e;
  }
}

const KEYWORDS = new Set(['if', 'then', 'else', 'for', 'in', 'return', 'and', 'or', 'true', 'false', 'null', 'some', 'every', 'satisfies',
  'between', 'instance', 'of', 'function', 'external']);
const PUNCTUATION = ['..', '<=', '>=', '!=', '**', '(', ')', '[', ']', '{', '}', ',', ':', '.', '+', '-', '*', '/', '=', '<', '>'];

function tokenize(source) {
  const src = String(source == null ? '' : source);
  const tokens = [];
  let i = 0;
  while (i < src.length) {
    const ch = src[i];
    if (/\s/.test(ch)) { i++; continue; }
    if (ch === '/' && src[i + 1] === '/') { while (i < src.length && src[i] !== '\n') i++; continue; }
    if (ch === '"') {
      let value = '';
      let j = i + 1;
      for (; j < src.length && src[j] !== '"'; j++) {
        if (src[j] === '\\' && j + 1 < src.length) {
          const n = src[++j];
          value += n === 'n' ? '\n' : n === 't' ? '\t' : n;
        } else value += src[j];
      }
      if (j >= src.length) throw new FeelError('A text is not closed with "');
      tokens.push({ t: 'str', v: value, pos: i });
      i = j + 1;
      continue;
    }
    const number = /^(\d+(\.\d+)?|\.\d+)/.exec(src.slice(i));
    if (number) {
      tokens.push({ t: 'num', v: Number(number[0]), pos: i });
      i += number[0].length;
      continue;
    }
    const name = /^[A-Za-z_?][\w?']*/.exec(src.slice(i));
    if (name) {
      tokens.push({ t: KEYWORDS.has(name[0]) ? 'kw' : 'name', v: name[0], pos: i });
      i += name[0].length;
      continue;
    }
    const p = PUNCTUATION.find(x => src.startsWith(x, i));
    if (!p) throw new FeelError("Unexpected character '" + ch + "'");
    tokens.push({ t: 'p', v: p, pos: i });
    i += p.length;
  }
  tokens.push({ t: 'eof', v: '', pos: src.length });
  return tokens;
}

const BUILT_IN = {};

/** The longest run of name tokens that is the name of a built-in function, e.g. round half up. */
function multiWordName(tokens, at) {
  let end = at;
  while (tokens[end] && tokens[end].t === 'name') end++;
  for (let last = end; last > at + 1; last--) {
    const joined = tokens.slice(at, last).map(t => t.v).join(' ');
    if (BUILT_IN[joined]) return { name: joined, next: last };
  }
  return { name: tokens[at].v, next: at + 1 };
}

export function parseExpression(source) {
  const tokens = tokenize(source);
  let pos = 0;
  const peek = () => tokens[pos];
  const next = () => tokens[pos++];
  const is = (t, v) => peek().t === t && (v === undefined || peek().v === v);
  const expect = (t, v) => {
    if (!is(t, v)) {
      const found = peek().t === 'eof' ? 'the end' : "'" + peek().v + "'";
      throw new FeelError('Expected ' + (v ? "'" + v + "'" : t) + ' but found ' + found + ' in "' + source + '"');
    }
    return next();
  };

  function expression() {
    if (is('kw', 'if')) {
      next();
      const condition = expression();
      expect('kw', 'then');
      const then = expression();
      expect('kw', 'else');
      return { type: 'if', condition, then, otherwise: expression() };
    }
    if (is('kw', 'for')) {
      next();
      const variable = expect('name').v;
      expect('kw', 'in');
      const list = expression();
      expect('kw', 'return');
      return { type: 'for', variable, list, body: expression() };
    }
    if (is('kw', 'some') || is('kw', 'every')) {
      const quantifier = next().v;
      const variable = expect('name').v;
      expect('kw', 'in');
      const list = expression();
      expect('kw', 'satisfies');
      return { type: 'quantified', quantifier, variable, list, body: expression() };
    }
    if (is('kw', 'function') || is('kw', 'external')) {
      throw new FeelError('Function definitions are not part of this simulation.', 'unsupported');
    }
    return disjunction();
  }
  function disjunction() {
    let left = conjunction();
    while (is('kw', 'or')) { next(); left = { type: 'or', left, right: conjunction() }; }
    return left;
  }
  function conjunction() {
    let left = comparison();
    while (is('kw', 'and')) { next(); left = { type: 'and', left, right: comparison() }; }
    return left;
  }
  function comparison() {
    const left = additive();
    if (is('p') && ['=', '!=', '<', '<=', '>', '>='].includes(peek().v)) {
      const op = next().v;
      return { type: 'compare', op, left, right: additive() };
    }
    if (is('kw', 'between')) {
      next();
      const low = additive();
      expect('kw', 'and');
      return { type: 'between', value: left, low, high: additive() };
    }
    return left;
  }
  function additive() {
    let left = multiplicative();
    while (is('p', '+') || is('p', '-')) {
      const op = next().v;
      left = { type: 'arith', op, left, right: multiplicative() };
    }
    return left;
  }
  function multiplicative() {
    let left = power();
    while (is('p', '*') || is('p', '/')) {
      const op = next().v;
      left = { type: 'arith', op, left, right: power() };
    }
    return left;
  }
  function power() {
    const left = unary();
    if (is('p', '**')) { next(); return { type: 'arith', op: '**', left, right: unary() }; }
    return left;
  }
  function unary() {
    if (is('p', '-')) { next(); return { type: 'negate', value: unary() }; }
    return postfix(primary());
  }
  function postfix(node) {
    for (;;) {
      if (is('p', '.')) {
        next();
        const name = expect('name').v;
        node = { type: 'path', object: node, name };
      } else if (is('p', '(')) {
        next();
        const args = [];
        if (!is('p', ')')) {
          args.push(expression());
          while (is('p', ',')) { next(); args.push(expression()); }
        }
        expect('p', ')');
        node = { type: 'call', callee: node, args };
      } else if (is('p', '[')) {
        next();
        const filter = expression();
        expect('p', ']');
        node = { type: 'filter', list: node, filter };
      } else return node;
    }
  }
  function primary() {
    const t = peek();
    if (t.t === 'num') { next(); return { type: 'value', value: t.v }; }
    if (t.t === 'str') { next(); return { type: 'value', value: t.v }; }
    if (t.t === 'kw' && (t.v === 'true' || t.v === 'false')) { next(); return { type: 'value', value: t.v === 'true' }; }
    if (t.t === 'kw' && t.v === 'null') { next(); return { type: 'value', value: null }; }
    if (t.t === 'name') {
      const { name, next: after } = multiWordName(tokens, pos);
      pos = after;
      return { type: 'name', name };
    }
    if (t.t === 'p' && t.v === '(') {
      next();
      const inner = expression();
      expect('p', ')');
      return inner;
    }
    if (t.t === 'p' && t.v === '[') {
      next();
      const items = [];
      if (!is('p', ']')) {
        items.push(expression());
        while (is('p', ',')) { next(); items.push(expression()); }
      }
      expect('p', ']');
      return { type: 'list', items };
    }
    if (t.t === 'p' && t.v === '{') {
      next();
      const entries = [];
      while (!is('p', '}')) {
        let key;
        if (is('str')) key = next().v;
        else {
          const words = [expect('name').v];
          while (is('name')) words.push(next().v);
          key = words.join(' ');
        }
        expect('p', ':');
        entries.push({ key, value: expression() });
        if (!is('p', ',')) break;
        next();
      }
      expect('p', '}');
      return { type: 'context', entries };
    }
    throw new FeelError((t.t === 'eof' ? 'The expression ends too early' : "Unexpected '" + t.v + "'") + ' in "' + source + '"');
  }

  const tree = expression();
  if (!is('eof')) throw new FeelError("Unexpected '" + peek().v + "' in \"" + source + '"');
  return tree;
}

const AST_CACHE = new Map();
const parsed = source => {
  if (typeof source !== 'string') return source;
  let tree = AST_CACHE.get(source);
  if (!tree) {
    tree = parseExpression(source);
    if (AST_CACHE.size > 500) AST_CACHE.clear();
    AST_CACHE.set(source, tree);
  }
  return tree;
};

/** A name of the scope or of a scope around it; what every JavaScript object has does not count. */
const has = (scope, name) => {
  for (let s = scope; s && s !== Object.prototype; s = Object.getPrototypeOf(s)) {
    if (Object.prototype.hasOwnProperty.call(s, name)) return true;
  }
  return false;
};
const child = (scope, entries) => Object.assign(Object.create(scope || null), entries);

export function evaluateExpression(source, scope) {
  return evaluate(parsed(source), scope || {});
}

function truth(value) {
  return value === true ? true : value === false ? false : null;
}

function evaluate(node, scope) {
  switch (node.type) {
    case 'value':
      return node.value;
    case 'name':
      if (has(scope, node.name)) return scope[node.name] === undefined ? null : scope[node.name];
      if (BUILT_IN[node.name]) return { __feelFunction: true, name: node.name, builtIn: BUILT_IN[node.name] };
      throw new FeelError("Unknown variable '" + node.name + "'", 'missing');
    case 'list':
      return node.items.map(item => evaluate(item, scope));
    case 'context': {
      const result = {};
      let inner = child(scope, {});
      node.entries.forEach(entry => {
        result[entry.key] = evaluate(entry.value, inner);
        inner = child(inner, { [entry.key]: result[entry.key] });
      });
      return result;
    }
    case 'path': {
      const object = evaluate(node.object, scope);
      const read = value => (isContext(value) && Object.prototype.hasOwnProperty.call(value, node.name) ? value[node.name] : null);
      if (Array.isArray(object)) return object.map(read);
      return read(object);
    }
    case 'filter': {
      const list = evaluate(node.list, scope);
      if (list === null) return null;
      const items = Array.isArray(list) ? list : [list];
      if (node.filter.type === 'value' && isNumber(node.filter.value)) {
        const index = node.filter.value;
        const at = index > 0 ? index - 1 : items.length + index;
        return at >= 0 && at < items.length ? items[at] : null;
      }
      if (node.filter.type === 'negate' && node.filter.value.type === 'value') {
        const at = items.length - node.filter.value.value;
        return at >= 0 && at < items.length ? items[at] : null;
      }
      return items.filter(item => truth(evaluate(node.filter, child(scope, { item, ...(isContext(item) ? item : {}) }))) === true);
    }
    case 'call': {
      const fn = evaluate(node.callee, scope);
      const args = node.args.map(arg => evaluate(arg, scope));
      return invoke(fn, args, node.callee.type === 'name' ? node.callee.name : 'the function');
    }
    case 'negate': {
      const value = evaluate(node.value, scope);
      if (value === null) return null;
      if (!isNumber(value)) throw new FeelError('Only a number can be negated, not ' + JSON.stringify(value));
      return -value;
    }
    case 'arith':
      return arithmetic(node.op, evaluate(node.left, scope), evaluate(node.right, scope));
    case 'compare': {
      const left = evaluate(node.left, scope);
      const right = evaluate(node.right, scope);
      if (node.op === '=') return same(left, right);
      if (node.op === '!=') return !same(left, right);
      const o = order(left, right);
      if (o === null) return null;
      return { '<': o < 0, '<=': o <= 0, '>': o > 0, '>=': o >= 0 }[node.op];
    }
    case 'between': {
      const value = evaluate(node.value, scope);
      const low = order(value, evaluate(node.low, scope));
      const high = order(value, evaluate(node.high, scope));
      return low === null || high === null ? null : low >= 0 && high <= 0;
    }
    case 'and': {
      const left = truth(evaluate(node.left, scope));
      if (left === false) return false;
      const right = truth(evaluate(node.right, scope));
      if (right === false) return false;
      return left === null || right === null ? null : true;
    }
    case 'or': {
      const left = truth(evaluate(node.left, scope));
      if (left === true) return true;
      const right = truth(evaluate(node.right, scope));
      if (right === true) return true;
      return left === null || right === null ? null : false;
    }
    case 'if':
      return truth(evaluate(node.condition, scope)) === true ? evaluate(node.then, scope) : evaluate(node.otherwise, scope);
    case 'for': {
      const list = evaluate(node.list, scope);
      if (list === null) return null;
      return (Array.isArray(list) ? list : [list]).map(item => evaluate(node.body, child(scope, { [node.variable]: item })));
    }
    case 'quantified': {
      const list = evaluate(node.list, scope);
      const items = list === null ? [] : Array.isArray(list) ? list : [list];
      const results = items.map(item => truth(evaluate(node.body, child(scope, { [node.variable]: item }))));
      return node.quantifier === 'some' ? results.some(r => r === true) : results.every(r => r === true);
    }
    default:
      throw new FeelError('Not simulated: ' + node.type, 'unsupported');
  }
}

function arithmetic(op, a, b) {
  if (a === null || a === undefined || b === null || b === undefined) return null;
  if (op === '+' && typeof a === 'string' && typeof b === 'string') return a + b;
  if (!isNumber(a) || !isNumber(b)) {
    throw new FeelError("'" + op + "' needs two numbers, not " + JSON.stringify(a) + ' and ' + JSON.stringify(b), 'unsupported');
  }
  switch (op) {
    case '+': return clean(a + b);
    case '-': return clean(a - b);
    case '*': return clean(a * b);
    case '/': return b === 0 ? null : clean(a / b);
    default: return clean(a ** b);
  }
}

export function invoke(fn, args, name) {
  if (fn && fn.__feelFunction) {
    if (fn.builtIn) return fn.builtIn(args);
    if (typeof fn.invoke === 'function') return fn.invoke(args);
  }
  if (typeof fn === 'function') return fn(args);
  throw new FeelError("'" + name + "' is not a function", 'missing');
}

const listOf = args => (args.length === 1 && Array.isArray(args[0]) ? args[0] : args);
const numbers = (args, name) => {
  const list = listOf(args);
  if (list.some(value => value !== null && !isNumber(value))) throw new FeelError(name + '() needs numbers');
  return list;
};
const anyNull = args => args.some(arg => arg === null || arg === undefined);

export function roundTo(n, scale, mode) {
  if (n === null || scale === null) return null;
  const factor = 10 ** scale;
  const x = Number((n * factor).toPrecision(15));
  const sign = x < 0 ? -1 : 1;
  const abs = Math.abs(x);
  let r;
  if (mode === 'half up') r = Math.round(abs);
  else if (mode === 'half down') r = Math.ceil(abs - 0.5);
  else if (mode === 'up') r = Math.ceil(abs);
  else if (mode === 'down') r = Math.floor(abs);
  else {
    const floor = Math.floor(abs);
    const rest = Number((abs - floor).toPrecision(12));
    r = rest > 0.5 ? floor + 1 : rest < 0.5 ? floor : floor % 2 === 0 ? floor : floor + 1;
  }
  return clean((sign * r) / factor) || 0;
}

const round = mode => args => {
  if (anyNull(args)) return null;
  return roundTo(args[0], args.length > 1 ? args[1] : 0, mode);
};

Object.assign(BUILT_IN, {
  sum: args => { const list = numbers(args, 'sum'); return list.length ? list.reduce((a, b) => clean(a + b), 0) : null; },
  min: args => { const list = listOf(args); return list.length ? list.reduce((a, b) => (order(b, a) < 0 ? b : a)) : null; },
  max: args => { const list = listOf(args); return list.length ? list.reduce((a, b) => (order(b, a) > 0 ? b : a)) : null; },
  mean: args => { const list = numbers(args, 'mean'); return list.length ? clean(list.reduce((a, b) => a + b, 0) / list.length) : null; },
  count: args => (Array.isArray(args[0]) ? args[0].length : null),
  concatenate: args => (args.some(arg => !Array.isArray(arg)) ? null : [].concat(...args)),
  append: args => (Array.isArray(args[0]) ? args[0].concat(args.slice(1)) : null),
  flatten: args => (Array.isArray(args[0]) ? args[0].flat(Infinity) : null),
  'distinct values': args => (Array.isArray(args[0]) ? args[0].filter((value, i, all) => all.findIndex(other => same(other, value)) === i) : null),
  'list contains': args => (Array.isArray(args[0]) ? args[0].some(value => same(value, args[1])) : null),
  decimal: round('half even'),
  'round half up': round('half up'),
  'round half down': round('half down'),
  'round up': round('up'),
  'round down': round('down'),
  floor: args => (anyNull(args) ? null : Math.floor(args[0])),
  ceiling: args => (anyNull(args) ? null : Math.ceil(args[0])),
  abs: args => (anyNull(args) ? null : Math.abs(args[0])),
  modulo: args => (anyNull(args) || args[1] === 0 ? null : clean(args[0] - args[1] * Math.floor(args[0] / args[1]))),
  date: args => {
    if (anyNull(args)) return null;
    if (args.length === 3) return new FeelDate([args[0], args[1], args[2]].map((part, i) => String(part).padStart(i ? 2 : 4, '0')).join('-'));
    return isDate(args[0]) ? args[0] : new FeelDate(String(args[0]).slice(0, 10));
  },
  string: args => (args[0] === null ? null : isDate(args[0]) ? args[0].iso : String(args[0])),
  number: args => (args[0] === null || !NUMBER.test(String(args[0]).trim()) ? null : Number(args[0])),
  'upper case': args => (typeof args[0] === 'string' ? args[0].toUpperCase() : null),
  'lower case': args => (typeof args[0] === 'string' ? args[0].toLowerCase() : null),
  'string length': args => (typeof args[0] === 'string' ? args[0].length : null),
  contains: args => (typeof args[0] === 'string' && typeof args[1] === 'string' ? args[0].includes(args[1]) : null),
  'starts with': args => (typeof args[0] === 'string' && typeof args[1] === 'string' ? args[0].startsWith(args[1]) : null),
  'ends with': args => (typeof args[0] === 'string' && typeof args[1] === 'string' ? args[0].endsWith(args[1]) : null),
  substring: args => (typeof args[0] === 'string' ? args[0].substr(args[1] > 0 ? args[1] - 1 : args[0].length + args[1], args[2] === undefined ? undefined : args[2]) : null),
  not: args => (args[0] === true ? false : args[0] === false ? true : null)
});

const SINGLE = ['UNIQUE', 'FIRST', 'ANY', 'PRIORITY'];

function outputValue(text, scope) {
  try {
    return literal(text);
  } catch (e) {
    if (!(e instanceof FeelError) || e.kind !== 'unsupported') throw e;
    return evaluateExpression(text, scope);
  }
}

export function evaluateTable(table, context) {
  const values = table.inputs.map(input => {
    try {
      return evaluateExpression(input.expression, context);
    } catch (e) {
      if (e instanceof FeelError && e.kind === 'missing') {
        const name = /'([^']+)'/.exec(e.message);
        throw new FeelError("Required dependency '" + (name ? name[1] : input.expression) + "' not found", 'missing');
      }
      throw e;
    }
  });
  const matched = [];
  table.rules.forEach((rule, index) => {
    const hit = table.inputs.every((input, column) => matches(rule.inputs[column], values[column]));
    if (hit) matched.push(index);
  });
  const resultOf = rule => {
    if (table.outputs.length === 1) return outputValue(rule.outputs[0], context);
    const result = {};
    table.outputs.forEach((output, column) => { result[output.name || 'output' + (column + 1)] = outputValue(rule.outputs[column], context); });
    return result;
  };
  if (table.hitPolicy === 'UNIQUE' && matched.length > 1) {
    throw new FeelError('UNIQUE hit policy decision tables can only have one matching rule. Multiple matches found: rules '
      + matched.map(index => index + 1).join(', '));
  }
  if (SINGLE.includes(table.hitPolicy)) {
    return { result: matched.length ? resultOf(table.rules[matched[0]]) : null, matched: matched.slice(0, 1) };
  }
  // COLLECT, RULE ORDER, OUTPUT ORDER: every rule that hits contributes
  const all = matched.map(index => resultOf(table.rules[index]));
  const aggregation = String(table.aggregation || '').toUpperCase();
  if (aggregation === 'SUM') return { result: all.length ? all.reduce((a, b) => clean(a + b), 0) : null, matched };
  if (aggregation === 'COUNT') return { result: all.length, matched };
  if (aggregation === 'MIN') return { result: BUILT_IN.min([all]), matched };
  if (aggregation === 'MAX') return { result: BUILT_IN.max([all]), matched };
  return { result: all, matched };
}
