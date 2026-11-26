/**
 * Stand-in for the decision runtime, the container of the Axon Ivy Rules Engine. It keeps the
 * versions of every model and evaluates decisions, following the rules of the real runtime
 * (rule-engine-runtime: VersionStore, VersionService):
 *
 *   - content that is already stored does not create another version
 *   - the first version of a model goes live at once
 *   - a version from a project goes live as long as the active version also came from a project
 *   - a version that was activated from the Portal is never replaced by a deployment
 *   - exactly one version of a model is active; a rollback activates the one before it
 *
 * Error codes are the ones of the runtime API: MODEL_NOT_FOUND, NO_ACTIVE_VERSION,
 * DECISION_NOT_FOUND, EVALUATION_FAILED, INVALID_STATE.
 */
import { decisionOf, evaluationOrder, requiredInputs, fingerprint, cloneDmn } from './dmn.js';
import { evaluateTable, evaluateExpression, FeelError, FeelDate, splitList, literal, same } from './feel.js';

export const RUNTIME_URL = 'http://localhost:8090';

export class RuntimeError extends Error {
  /** @param {string} code  error code of the runtime API  @param {number} status  HTTP status */
  constructor(code, status, message) {
    super(message);
    this.name = 'RuntimeError';
    this.code = code;
    this.status = status;
  }
}

const BASE_TYPES = new Set(['number', 'string', 'boolean', 'date', 'Any', '']);
const stripPrefix = (dmn, type) => {
  const name = String(type || '').trim();
  const colon = name.indexOf(':');
  return colon > 0 && !(dmn.types || {})[name] ? name.slice(colon + 1) : name;
};

/**
 * The value of an input as the DMN engine sees it, checked against its declared type (rule.engine.type-check):
 * a date becomes a FEEL date, an item definition restricts the allowed values, a structure checks its
 * components, a collection each element. A value that is not allowed throws, as the runtime does.
 */
function typed(dmn, type, value, depth = 0) {
  const name = stripPrefix(dmn, type);
  const item = (dmn.types || {})[name];
  if (value === null || value === undefined) {
    // allowed values that do not list null refuse it, as the DMN engine does
    if (item && item.allowedValues && !item.collection && !splitList(item.allowedValues).some(text => text.trim() === 'null')) {
      throw new TypeError(name);
    }
    return null;
  }
  if (!item || depth > 8) {
    if (name === 'number' && typeof value !== 'number') throw new TypeError(name);
    if (name === 'string' && typeof value !== 'string') throw new TypeError(name);
    if (name === 'boolean' && typeof value !== 'boolean') throw new TypeError(name);
    if (name === 'date') {
      if (value instanceof FeelDate) return value;
      if (typeof value !== 'string') throw new TypeError(name);
      try { return new FeelDate(value.slice(0, 10)); } catch (e) { throw new TypeError(name); }
    }
    if (!BASE_TYPES.has(name) && typeof value !== 'object') throw new TypeError(name);
    return value;
  }
  if (item.collection) {
    if (!Array.isArray(value)) throw new TypeError(name);
    const element = { ...item, collection: false };
    return value.map(entry => typedItem(dmn, element, entry, depth + 1));
  }
  return typedItem(dmn, item, value, depth);
}

function typedItem(dmn, item, value, depth) {
  if (value === null || value === undefined) return null;
  if (item.components && item.components.length) {
    if (typeof value !== 'object' || Array.isArray(value)) throw new TypeError(item.name);
    const result = { ...value };
    item.components.forEach(component => {
      const entry = value[component.name] === undefined ? null : value[component.name];
      if (!component.collection) result[component.name] = typed(dmn, component.type, entry, depth + 1);
      else if (entry === null) result[component.name] = null;
      else if (!Array.isArray(entry)) throw new TypeError(item.name);
      else result[component.name] = entry.map(element => typed(dmn, component.type, element, depth + 1));
    });
    return result;
  }
  const checked = typed(dmn, item.base, value, depth + 1);
  if (item.allowedValues) {
    const allowed = splitList(item.allowedValues).map(text => literal(text));
    if (!allowed.some(candidate => same(candidate, checked))) throw new TypeError(item.name);
  }
  return checked;
}

/** A result as JSON: dates as their text. */
const plain = value => (value instanceof FeelDate ? value.iso : Array.isArray(value) ? value.map(plain)
  : value !== null && typeof value === 'object' ? Object.fromEntries(Object.entries(value).map(([key, entry]) => [key, plain(entry)])) : value);

export class DecisionRuntime {
  constructor({ url = RUNTIME_URL } = {}) {
    this.url = url;
    /** 'running' | 'slow' (answers too late) | 'stopped' (the container is down) */
    this.state = 'running';
    this.models = new Map(); // name → [{ version, status, source, author, dmn, hash, previousVersion }]
  }

  versions(model) {
    return this.models.get(model) || [];
  }

  modelNames() {
    return [...this.models.keys()];
  }

  active(model) {
    return this.versions(model).find(version => version.status === 'ACTIVE') || null;
  }

  /**
   * POST /api/models/{model}/versions
   * @param {Object} dmn  the model as read by parseDmn
   * @param {{ source?: 'PROJECT'|'PORTAL'|'API', author?: string, activate?: boolean }} [options]
   *   `activate` not set: the runtime decides by the source of truth rule
   * @returns {{ entry: Object, created: boolean }}
   */
  register(model, dmn, { source = 'API', author = '', activate } = {}) {
    const hash = fingerprint(dmn);
    const versions = this.versions(model);
    let entry = versions.find(version => version.hash === hash);
    const created = !entry;
    if (created) {
      entry = { model, version: versions.length + 1, status: 'DRAFT', source, author, dmn: cloneDmn(dmn), hash, previousVersion: null };
      this.models.set(model, [...versions, entry]);
    }
    if (entry.status !== 'ACTIVE' && this.#shouldActivate(model, source, activate)) this.#activate(entry, false);
    return { entry, created };
  }

  #shouldActivate(model, source, activate) {
    if (activate !== undefined && activate !== null) return Boolean(activate);
    const active = this.active(model);
    if (!active) return true;
    return source === 'PROJECT' && active.source === 'PROJECT';
  }

  #activate(entry, rollback) {
    const current = this.active(entry.model);
    if (current === entry) return;
    if (current) current.status = 'RETIRED';
    // a rollback keeps the history of its target, so repeated rollbacks walk further back
    if (!rollback) entry.previousVersion = current ? current.version : null;
    entry.status = 'ACTIVE';
  }

  #require(model, version) {
    if (!this.models.has(model)) throw new RuntimeError('MODEL_NOT_FOUND', 404, "Model '" + model + "' is not loaded.");
    const entry = this.versions(model).find(candidate => candidate.version === version);
    if (!entry) throw new RuntimeError('VERSION_NOT_FOUND', 404, "Model '" + model + "' has no version " + version + '.');
    return entry;
  }

  /** POST /api/models/{model}/versions/{version}/activate */
  activate(model, version) {
    const entry = this.#require(model, version);
    this.#activate(entry, false);
    return entry;
  }

  /** POST /api/models/{model}/rollback */
  rollback(model) {
    if (!this.models.has(model)) throw new RuntimeError('MODEL_NOT_FOUND', 404, "Model '" + model + "' is not loaded.");
    const active = this.active(model);
    if (!active) throw new RuntimeError('NO_ACTIVE_VERSION', 409, "Model '" + model + "' has no active version.");
    if (active.previousVersion === null) {
      throw new RuntimeError('INVALID_STATE', 409, 'Version ' + active.version + " of '" + model + "' is the first active version, there is nothing to roll back to.");
    }
    const previous = this.#require(model, active.previousVersion);
    this.#activate(previous, true);
    return previous;
  }

  /**
   * POST /api/models/{model}/evaluate
   * @param {string|null} decision  the decision to evaluate, or null for all decisions of the model
   * @param {Object} input          values by the name of the DMN input
   * @returns {{ model, version, decision, results, matched, calls }}  `matched`: by decision name, the indexes of
   *   the rules that hit; `calls`: by function name, one entry per call: its arguments and the rules that hit
   * @throws {RuntimeError}
   */
  evaluate(model, decision, input) {
    if (!this.models.has(model)) throw new RuntimeError('MODEL_NOT_FOUND', 404, "Model '" + model + "' is not loaded.");
    const active = this.active(model);
    if (!active) throw new RuntimeError('NO_ACTIVE_VERSION', 409, "Model '" + model + "' has no active version. Activate one of its versions first.");
    const dmn = active.dmn;
    if (decision && !decisionOf(dmn, decision)) {
      throw new RuntimeError('DECISION_NOT_FOUND', 404, "Model '" + model + "' version " + active.version + " has no decision '" + decision +
        "'. Available decisions: [" + dmn.decisions.map(d => d.name).join(', ') + ']');
    }
    const failed = message => new RuntimeError('EVALUATION_FAILED', 422, "Evaluation of model '" + model + "' version " + active.version + ' failed: ' + message);
    const context = {};
    (requiredInputs(dmn, decision) || []).forEach(name => {
      if (!Object.prototype.hasOwnProperty.call(input || {}, name)) throw failed("Required dependency '" + name + "' not found");
      const declared = dmn.inputs.find(candidate => candidate.name === name);
      try {
        context[name] = declared ? typed(dmn, declared.type, input[name]) : (input[name] === undefined ? null : input[name]);
      } catch (e) {
        if (!(e instanceof TypeError)) throw e;
        const shown = JSON.stringify(input[name]);
        throw failed("the value '" + (shown.length > 60 ? shown.slice(0, 57) + '...' : shown) + "' of '" + name + "' is not allowed by the declared type (" + declared.type + ')');
      }
    });
    // the functions of the model, callable from every decision; each call is recorded for the explanation
    const calls = {};
    // a decision may call a function twice with the same arguments (pricedLines asks for the percent and for the
    // reason): that is one call for the explanation
    const record = (name, made) => {
      const list = calls[name];
      const last = list[list.length - 1];
      if (!last || JSON.stringify(last.args) !== JSON.stringify(made.args)) list.push(made);
    };
    (dmn.functions || []).forEach(fn => {
      calls[fn.name] = [];
      context[fn.name] = {
        __feelFunction: true,
        name: fn.name,
        invoke: args => {
          const scope = { ...context };
          fn.params.forEach((param, i) => { scope[param.name] = args[i] === undefined ? null : args[i]; });
          if (fn.kind === 'table') {
            const outcome = evaluateTable(fn.table, scope);
            record(fn.name, { args: fn.params.map(param => plain(scope[param.name])), matched: outcome.matched, result: plain(outcome.result) });
            return outcome.result;
          }
          if (fn.kind === 'literal') {
            const result = evaluateExpression(fn.expression, scope);
            record(fn.name, { args: fn.params.map(param => plain(scope[param.name])), matched: [], result: plain(result) });
            return result;
          }
          throw new FeelError("The function '" + fn.name + "' is neither a decision table nor a literal expression.", 'unsupported');
        }
      };
    });
    const results = {};
    const matched = {};
    evaluationOrder(dmn, decision).forEach(step => {
      if (step.kind === 'other') {
        throw new RuntimeError('NOT_SIMULATED', 501, "The decision '" + step.name + "' is neither a decision table nor a literal expression. The simulation does not evaluate it.");
      }
      try {
        if (step.kind === 'table') {
          const outcome = evaluateTable(step.table, context);
          results[step.name] = outcome.result;
          matched[step.name] = outcome.matched;
        } else {
          results[step.name] = evaluateExpression(step.expression, context);
          matched[step.name] = [];
        }
        context[step.name] = results[step.name];
      } catch (e) {
        if (!(e instanceof FeelError)) throw e;
        if (e.kind === 'unsupported') throw new RuntimeError('NOT_SIMULATED', 501, e.message);
        throw failed("Error while evaluating node '" + step.name + "': " + e.message);
      }
    });
    Object.keys(results).forEach(name => { results[name] = plain(results[name]); });
    return { model, version: active.version, decision: decision || null, results, matched, calls, dmn };
  }
}
