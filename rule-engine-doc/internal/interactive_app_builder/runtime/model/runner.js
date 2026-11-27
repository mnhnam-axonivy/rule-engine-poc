/**
 * Executes a process model step by step.
 *
 * The Business Rule Task follows the connector's RuleTaskExecutor (rule-engine 14.0.1), so the
 * simulation reacts to configuration the way the real element does:
 *   - "Decision"         → `model#decision`, or the whole model; nothing selected fails the
 *                          step with rule:engine:mapping
 *   - inputs             → the inputs the decision needs are read from the process data fields
 *                          with their names. An input without a field fails the step before
 *                          anything is sent
 *   - registration       → the .dmn file of the project is stored as a version of the application
 *                          when its content is new (RuleEngine.AutoDeploy), after the runtime checked
 *                          it; the store decides whether the version goes live
 *   - the version        → the active version answers: its decision says which inputs are sent.
 *                          No version: rule:engine:dmn with MODEL_NOT_FOUND or NO_ACTIVE_VERSION
 *   - the call           → runtime not reachable, a timeout and an error answer of the runtime
 *                          throw the BPMN errors rule:engine:unreachable, rule:engine:timeout
 *                          and rule:engine:dmn
 *   - results            → each result is written to the field with its name
 *   - "New DMN"          → generates the model from "Input fields" and "Output fields" when it
 *                          does not exist yet, then evaluates it; the fields are used for nothing else
 *
 * Besides the rule task it knows scripts, the Alternative gateway (the first condition that
 * holds decides), tasks that wait for a user, Error Ends and Error Boundary Events.
 *
 * The runner has no DOM or animation knowledge: everything visible goes through `io`, whose
 * async methods let the UI pace the run (token travel, the round trip to the runtime, step mode).
 */
import { runScript, evalExpression, assignTo, IvyScriptError, positionOf } from './ivyscript.js';
import { expandMacro } from './macro.js';
import { parseYaml, readVariable } from './yaml.js';
import { startElement, nextOf, ruleConfig, configList, docOf, elementById, boundaryFor } from './process.js';
import { decisionOf, requiredInputs } from './dmn.js';
import { generateDmn, modelNameFor, readableByName, generatedFrom } from './dmn-generator.js';
import { RuntimeError, RUNTIME_URL } from './decision-runtime.js';
import { parseFieldSelection, findField, readPath, toJson, fromJson, MappingError } from './mapping.js';

const BPM_ERROR = 'ch.ivyteam.ivy.bpm.error.BpmError';
const lines = value => (Array.isArray(value) ? value.join('\n') : value == null ? '' : String(value));

/** The BPMN errors of the Business Rule Task (rule-engine: RuleErrors). */
export const RULE_ERRORS = {
  UNREACHABLE: 'rule:engine:unreachable',
  TIMEOUT: 'rule:engine:timeout',
  DMN: 'rule:engine:dmn',
  MAPPING: 'rule:engine:mapping'
};

/** Entry of the decision dropdown that generates a new .dmn file (rule-engine: Conf.CREATE_NEW). */
export const CREATE_NEW = '#create';
export const VARIABLES = {
  URL: 'RuleEngine.Url',
  API_KEY: 'RuleEngine.ApiKey',
  TIMEOUT: 'RuleEngine.TimeoutSeconds',
  AUTO_DEPLOY: 'RuleEngine.AutoDeploy',
  PREFIX: 'RuleEngine.ModelPrefix'
};
const DEFAULT_TIMEOUT = 10;

/** What the connector reads from variables.yaml (rule-engine: RuleEngineConfig). */
export function readSettings(variables) {
  const read = path => readVariable(variables || {}, path).trim();
  const seconds = parseFloat(read(VARIABLES.TIMEOUT));
  return {
    url: read(VARIABLES.URL),
    apiKey: read(VARIABLES.API_KEY),
    timeout: seconds > 0 ? seconds : DEFAULT_TIMEOUT,
    autoDeploy: read(VARIABLES.AUTO_DEPLOY).toLowerCase() !== 'false',
    prefix: read(VARIABLES.PREFIX)
  };
}

class Cancelled extends Error {}

class ProcessFailure extends Error {
  constructor(element, info) {
    super(info.message);
    this.element = element;
    this.info = info;
  }
}

const noop = () => {};
const IO_METHODS = ['travel', 'enter', 'leave', 'pause', 'rule', 'log', 'note', 'data', 'dmn'];
const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);

export class ProcessRunner {
  /**
   * @param {Object} options
   * @param {Object} options.model    process model (see process.js)
   * @param {() => Object} options.runtime  the decision runtime the project talks to (see decision-runtime.js)
   * @param {Object} options.project  the .dmn files of the project:
   *   { find(model) → the model of the file as it is now, or null; add(dmn) → a generated file }
   * @param {Object} options.io       presentation callbacks, all optional
   * @param {() => Date} [options.now]
   */
  constructor({ model, runtime, project, io = {}, now = () => new Date() }) {
    this.model = model;
    this.runtime = runtime;
    this.project = project;
    this.now = now;
    this.io = {};
    IO_METHODS.forEach(m => { this.io[m] = typeof io[m] === 'function' ? io[m].bind(io) : noop; });
    this.active = null;
    /** What this engine has already registered: runtime model → fingerprint (rule-engine: ModelDeployer). */
    this.registered = new Map();
  }

  get running() { return this.active !== null; }

  cancel() {
    if (this.active) this.active.cancelled = true;
  }

  /** Switches step mode for the run in progress (Continue vs. Step while paused). */
  setStepMode(stepMode) {
    if (this.active) this.active.stepMode = stepMode;
  }

  /** Forgets what was registered, e.g. when the simulation is reset. */
  forget() {
    this.registered.clear();
  }

  /**
   * @param {Object} options
   * @param {string} options.variablesText   contents of variables.yaml
   * @param {boolean} [options.stepMode]     pause after every element
   * @param {Set<string>} [options.breakpoints]
   * @param {string} [options.start]         id of the start element to begin at (default: the first)
   * @param {Object} [options.params]        the parameters a caller passes to a callable sub process, by name
   * @returns {Promise<Object>} summary of what happened (status, rule calls, …)
   */
  async run({ variablesText = '', stepMode = false, breakpoints = new Set(), start = null, params = null } = {}) {
    if (this.active) throw new Error('A run is already in progress');
    const run = {
      cancelled: false,
      stepMode,
      breakpoints,
      params: params ? JSON.parse(JSON.stringify(params)) : {},
      data: {},
      variables: null,
      route: null, // where a gateway sends the token
      result: { status: 'completed', visited: [], ruleCalls: [], routes: [], tasks: [], caught: [], logs: [], data: null }
    };
    this.active = run;
    try {
      const yaml = parseYaml(variablesText);
      if (yaml.errors.length) {
        run.variables = {};
        run.variablesError = yaml.errors[0];
      } else run.variables = yaml.value;

      let element = (start && elementById(this.model, start)) || startElement(this.model);
      let connector = null;
      let stepped = false;
      while (element) {
        if (element.removed) {
          run.result.status = 'incomplete';
          run.result.missing = element.id;
          this.io.note('incomplete', { element });
          break;
        }
        if (connector) await this.#wait(run, this.io.travel(connector));
        // A breakpoint halts before its element runs, unless a step already stopped right here.
        if (run.breakpoints.has(element.id) && !stepped) await this.#wait(run, this.io.pause(element, 'breakpoint'));
        stepped = false;
        await this.#wait(run, this.io.enter(element));
        run.result.visited.push(element.id);
        try {
          await this.#execute(run, element);
        } catch (e) {
          if (!(e instanceof ProcessFailure)) throw e;
          // a BPM error goes to the Error Boundary Event that catches its code, if there is one
          const boundary = e.info.errorCode ? boundaryFor(this.model, element, e.info.errorCode) : null;
          if (boundary) {
            this.io.leave(element, 'caught');
            run.result.caught.push({ elementId: element.id, boundaryId: boundary.id, errorCode: e.info.errorCode, message: e.info.message });
            await this.#wait(run, this.io.enter(boundary));
            this.#catch(run, boundary, e.info);
            this.io.note('error-caught', { element: boundary, code: e.info.errorCode });
            this.io.leave(boundary, 'done');
            run.result.visited.push(boundary.id);
            const onward = nextOf(this.model, boundary);
            if (!onward || !onward.element) break;
            connector = { id: onward.connectorId, from: boundary, to: onward.element };
            element = onward.element;
            continue;
          }
          if (e.info.errorCode) this.#log(run, 'error', element, 'Unhandled error ' + e.info.errorCode + ': ' + e.info.message, { category: 'PROCESS' });
          run.result.status = 'failed';
          run.result.failedAt = element.id;
          run.result.error = e.info;
          this.io.leave(element, 'error');
          break;
        }
        this.io.leave(element, 'done');
        if (element.kind === 'end') break;
        const next = run.route || nextOf(this.model, element);
        run.route = null;
        if (!next || !next.element) break;
        // Step mode: this element has run; wait before the token moves on to the next one.
        if (run.stepMode && !next.element.removed) {
          await this.#wait(run, this.io.pause(next.element, 'step'));
          stepped = true;
        }
        connector = { id: next.connectorId, from: element, to: next.element };
        element = next.element;
      }
    } catch (e) {
      if (e instanceof Cancelled) run.result.status = 'cancelled';
      else {
        this.active = null;
        throw e;
      }
    }
    run.result.data = { ...run.data };
    this.active = null;
    return run.result;
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  async #wait(run, value) {
    const v = await value;
    if (run.cancelled) throw new Cancelled();
    return v;
  }

  #log(run, level, element, message, { category = 'USER', detail = '' } = {}) {
    const entry = {
      time: this.now(),
      level,
      project: this.model.project,
      category,
      context: docOf(this.model, element).processId + '-' + element.rawId,
      message,
      detail
    };
    run.result.logs.push(entry);
    this.io.log(entry);
  }

  #scriptContext(run, element, vars) {
    const dataClass = docOf(this.model, element).dataClass;
    return {
      data: run.data,
      fields: dataClass.fields,
      dataClassName: dataClass.name,
      classes: this.model.classes,
      cms: this.model.cms,
      vars,
      log: (level, message) => this.#log(run, level, element, message),
      onAssign: (field, value) => this.io.data(field, value, element)
    };
  }

  async #execute(run, element) {
    switch (element.kind) {
      case 'start':
        if (element.type === 'CallSubStart') this.#callSubStart(run, element);
        this.io.note('start', { element });
        return;
      case 'script':
        this.io.note('script', { element });
        this.#runCode(run, element, (element.config.output && element.config.output.code) || '');
        return;
      case 'task':
        if (element.config.task && element.config.task.skipTasklist) this.io.note('task-skip', { element });
        else {
          // without "Skip Tasklist" the task waits in the task list until a user starts it
          const user = this.#responsible(run, element);
          const task = element.config.task || {};
          const ctx = this.#scriptContext(run, element);
          const expand = text => {
            const out = expandMacro(String(text).replace(/\bin\d+\./g, 'in.'), ctx);
            return out.error ? String(text) : out.text;
          };
          const name = task.name ? expand(task.name).trim() : element.name;
          const description = task.description ? expand(task.description) : '';
          run.result.tasks.push({ elementId: element.id, name, description, user });
          this.io.note(user ? 'task-user' : 'task', { element, user });
          await this.#wait(run, this.io.pause(element, 'task', { user, name, description }));
        }
        return;
      case 'gateway':
        run.route = this.#route(run, element);
        return;
      case 'rule':
        await this.#rule(run, element);
        return;
      case 'end':
        if (element.type === 'ErrorEnd') this.#errorEnd(run, element);
        this.io.note('end', { element });
        return;
      default:
        this.io.note('passthrough', { element });
    }
  }

  /** The start of a callable sub process: its input mapping copies the parameters of the caller into the data. */
  #callSubStart(run, element) {
    const input = element.config.input || {};
    const map = input.map || {};
    const vars = { param: run.params };
    const ctx = this.#scriptContext(run, element, vars);
    Object.keys(map).forEach(target => {
      try {
        const value = evalExpression(map[target], ctx);
        if (target === 'out' || target === 'in') {
          if (isObject(value)) Object.keys(value).filter(key => !key.startsWith('__')).forEach(key => assignTo('in.' + key, value[key], ctx));
        } else assignTo(target.replace(/^out\./, 'in.'), value, ctx);
      } catch (e) {
        if (!(e instanceof IvyScriptError)) throw e;
        const message = 'Failed to map ' + target + ': ' + e.message;
        this.#log(run, 'error', element, message, { category: 'PROCESS' });
        throw new ProcessFailure(element, { kind: 'script', message });
      }
    });
    const code = lines(input.code);
    if (code.trim()) this.#runCode(run, element, code, vars);
  }

  /** An Error End throws a BPM error; its code can attach attributes to the error first. */
  #errorEnd(run, element) {
    const errorCode = (element.config.throws && element.config.throws.error) || 'ivy:error';
    const error = { __type: BPM_ERROR, __bpmError: true, errorCode, message: errorCode, attributes: {} };
    const code = lines(element.config.code);
    if (code.trim()) this.#runCode(run, element, code, { error });
    this.io.note('error-end', { element, code: errorCode });
    throw new ProcessFailure(element, { kind: 'bpm', errorCode, message: errorCode, attributes: error.attributes });
  }

  /** Runs code of an element; a script error stops the process there. */
  #runCode(run, element, code, vars) {
    try {
      runScript(code, this.#scriptContext(run, element, vars));
    } catch (e) {
      if (!(e instanceof IvyScriptError)) throw e;
      const at = positionOf(code, e.pos);
      const message = 'IvyScript error in line ' + at.line + ': ' + e.message;
      this.#log(run, 'error', element, message, { category: 'PROCESS' });
      throw new ProcessFailure(element, { kind: 'script', message, line: at.line });
    }
  }

  /**
   * Alternative gateway: the conditions are tried in the order they are stored, the first
   * that holds decides, and a connector without a condition always holds.
   */
  #route(run, element) {
    const conditions = element.config.conditions || {};
    const order = Object.keys(conditions);
    element.outgoing.forEach(c => { if (!order.includes(c.rawId)) order.push(c.rawId); });
    const ctx = this.#scriptContext(run, element);
    for (const key of order) {
      const out = element.outgoing.find(c => c.rawId === key);
      if (!out) continue;
      const condition = String(conditions[key] == null ? '' : conditions[key]).trim();
      let holds = true;
      if (condition !== '') {
        try {
          holds = evalExpression(condition, ctx) === true;
        } catch (e) {
          if (!(e instanceof IvyScriptError)) throw e;
          const message = 'The condition "' + condition + '" could not be evaluated: ' + e.message;
          this.#log(run, 'error', element, message, { category: 'PROCESS' });
          throw new ProcessFailure(element, { kind: 'gateway', message });
        }
      }
      if (!holds) continue;
      const target = elementById(this.model, out.to);
      run.result.routes.push({ gateway: element.id, connectorId: out.id, condition, to: out.to });
      this.io.note(condition ? 'gateway' : 'gateway-default', { element, condition, target });
      return { connectorId: out.id, element: target };
    }
    const message = 'No condition of the gateway "' + (element.name || element.rawId) + '" is true';
    this.#log(run, 'error', element, message, { category: 'PROCESS' });
    throw new ProcessFailure(element, { kind: 'gateway', message });
  }

  /**
   * What an Error Boundary Event does with the error it caught: its output mapping and its
   * output code, where `error` is the BPM error (e.g. in.errorCode = error.getErrorCode()).
   */
  #catch(run, boundary, info) {
    const error = { __type: BPM_ERROR, __bpmError: true, errorCode: info.errorCode, message: info.message, attributes: info.attributes || {} };
    const output = boundary.config.output || {};
    const map = output.map || {};
    const ctx = this.#scriptContext(run, boundary, { error });
    Object.keys(map).forEach(target => {
      if (target === 'out' || target === 'in') return; // out ← in: the data goes on as it is
      try {
        assignTo(target, evalExpression(map[target], ctx), ctx);
      } catch (e) {
        if (!(e instanceof IvyScriptError)) throw e;
        this.#log(run, 'error', boundary, 'Failed to map ' + target + ': ' + e.message, { category: 'PROCESS' });
      }
    });
    const code = lines(output.code);
    if (code.trim()) this.#runCode(run, boundary, code, { error });
  }

  /** The user a task goes to when its responsible is "User from Attr.": a script over the process data. */
  #responsible(run, element) {
    const responsible = (element.config.task || {}).responsible;
    if (!responsible || responsible.type !== 'USER_FROM_ATTRIBUTE' || !responsible.script) return '';
    try {
      // in a task element the data of the incoming path is called in1
      const value = evalExpression(String(responsible.script).replace(/\bin\d+\./g, 'in.'), this.#scriptContext(run, element));
      return value == null ? '' : String(value);
    } catch (e) {
      if (!(e instanceof IvyScriptError)) throw e;
      return '';
    }
  }

  // ── Business Rule Task ─────────────────────────────────────────────────────

  async #rule(run, element) {
    const cfg = ruleConfig(element);
    const ctx = this.#scriptContext(run, element);
    const call = { elementId: element.id, name: element.name, status: 'started', mappings: [], targets: [] };
    run.result.ruleCalls.push(call);
    const tell = (phase, payload = {}) => this.#wait(run, this.io.rule(phase, { call, element, ...payload }));
    const fail = async (errorCode, message, extra = {}) => {
      Object.assign(call, { status: 'failed', error: message, errorCode, runtimeCode: extra.runtimeCode || null });
      this.#log(run, 'error', element, extra.log || message, { category: extra.category || 'PROCESS', detail: extra.detail || '' });
      await tell('failed', { errorCode, message });
      throw new ProcessFailure(element, {
        kind: 'rule', errorCode, message,
        attributes: { ruleEngineModel: call.model || '', ...(extra.runtimeCode ? { ruleEngineCode: extra.runtimeCode } : {}) }
      });
    };

    // 1 — settings (RuleEngineConfig)
    if (run.variablesError) {
      await fail(RULE_ERRORS.MAPPING, 'variables.yaml could not be read (line ' + run.variablesError.line + ': ' + run.variablesError.message + ')');
    }
    const settings = readSettings(run.variables);
    call.timeout = settings.timeout;

    // 2 — the selected decision, or a new DMN from the selected fields
    const selected = configList(cfg.decision);
    const value = (selected[0] || '').trim();
    if (!value) {
      await fail(RULE_ERRORS.MAPPING, "No decision selected. Choose a decision of a .dmn file in the Business Rule Task '" + element.name.replace(/\n/g, ' ') + "'.");
    }
    if (selected.length > 1) this.#log(run, 'warn', element, 'Only one decision is evaluated. Using ' + value + ' and ignoring the others of [' + selected.join(', ') + ']');
    let model;
    let decision;
    if (value === CREATE_NEW) {
      try {
        const created = this.#create(run, element, cfg, parseFieldSelection(cfg.inputs), parseFieldSelection(cfg.outputs));
        model = created.dmn.model;
        decision = created.decision;
        if (created.generated) await tell('created', { dmn: created.dmn, decision });
      } catch (e) {
        if (!(e instanceof MappingError)) throw e;
        await fail(RULE_ERRORS.MAPPING, e.message,
          { log: "Rule engine: the Business Rule Task '" + element.name.replace(/\s+/g, ' ') + "' cannot create its new DMN. " + e.message });
      }
    } else {
      const separator = value.indexOf('#');
      model = (separator < 0 ? value : value.slice(0, separator)).trim();
      decision = separator < 0 ? null : value.slice(separator + 1).trim() || null;
    }
    const runtime = this.runtime();
    const runtimeModel = settings.prefix + model;
    const dmn = this.project.find(model); // the file of the project, as it is saved now
    Object.assign(call, { model, runtimeModel, decision });

    // 3 — the version that answers: a new or changed file of the project is stored first, the runtime checks it
    // (ModelDeployer); then the active version decides which inputs are needed, also a version from the Portal
    let registered = null;
    if (settings.autoDeploy && dmn && this.#isNew(runtime, runtimeModel, dmn)) {
      const down = this.#unreachable(settings, runtime);
      if (down) {
        await tell('request', {});
        await tell('thinking', { failing: true });
        await fail(down.errorCode, down.message, { category: 'REST_CLIENT' });
      }
      registered = this.#register(run, element, call, runtime, runtimeModel, dmn);
    }
    const active = runtime.active(runtimeModel);
    if (!active) {
      const missing = runtime.versions(runtimeModel).length
        ? { code: 'NO_ACTIVE_VERSION', message: "Model '" + model + "' has no active version. Activate one of its versions first." }
        : { code: 'MODEL_NOT_FOUND', message: "There is no model '" + model + "'." };
      await fail(RULE_ERRORS.DMN, missing.message, { runtimeCode: missing.code });
    }
    call.url = settings.url + '/api/models/' + runtimeModel + '/versions/' + active.version + '/evaluate';
    const names = requiredInputs(active.dmn, decision);
    if (names === null) {
      await fail(RULE_ERRORS.MAPPING, 'Version ' + active.version + " of model '" + model + "' has no decision '" + decision +
        "'. Its decisions are: [" + active.dmn.decisions.map(d => d.name).join(', ') + ']');
    }
    const input = {};
    const unmapped = [];
    for (const name of names) {
      const found = findField(this.model, name);
      if (!found) { unmapped.push(name); continue; }
      input[name] = toJson(readPath(run.data, found.expression));
      call.mappings.push({ name, expression: found.expression, value: input[name] });
    }
    if (unmapped.length) {
      const of = decision ? "of the decision '" + decision + "' in model '" + model + "'" : "of the model '" + model + "'";
      await fail(RULE_ERRORS.MAPPING, 'No process data field found for the input(s) [' + unmapped.join(', ') + '] ' + of +
        '. The process data needs a field named like each input, e.g. in.' + unmapped[0] + ', set before the Business Rule Task.');
    }
    call.input = input;
    await tell('request', {});
    if (registered) await tell('registered', registered);

    // 4 — the round trip to the runtime; it gets the version into its memory first, if it does not hold it
    const down = this.#unreachable(settings, runtime);
    if (down) {
      await tell('thinking', { failing: true });
      await fail(down.errorCode, down.message, { category: 'REST_CLIENT' });
    }
    await tell('thinking', {});
    let response;
    try {
      response = runtime.evaluate(runtimeModel, decision, input);
    } catch (e) {
      if (!(e instanceof RuntimeError)) throw e;
      await fail(RULE_ERRORS.DMN, e.message, { runtimeCode: e.code, category: 'REST_CLIENT' });
    }
    this.#log(run, 'info', element, 'REST service ruleEngine (ruleEngine) call to POST ' + call.url + ' successful executed in 1 [s]. Response status was 200 OK', { category: 'REST_CLIENT' });
    // one decision asked, or a model of one decision: the rule that decided explains the result
    const decided = decision || (response.dmn.decisions.length === 1 ? response.dmn.decisions[0].name : null);
    const hits = decided ? response.matched[decided] || [] : [];
    const table = decided ? (decisionOf(response.dmn, decided) || {}).table : null;
    const describe = (tbl, index) => ({ index, inputs: tbl.rules[index].inputs, outputs: tbl.rules[index].outputs, annotation: tbl.rules[index].annotation });
    // a whole model: what every table decided, a table that is a function once per call (e.g. per line)
    const rules = [];
    const matched = { ...response.matched };
    response.dmn.decisions.forEach(d => {
      if (d.kind === 'table') (response.matched[d.name] || []).forEach(index => rules.push({ table: d.name, ...describe(d.table, index) }));
    });
    (response.dmn.functions || []).forEach(fn => {
      if (fn.kind !== 'table') return;
      const calls = (response.calls || {})[fn.name] || [];
      calls.forEach((made, n) => made.matched.forEach(index => rules.push({ table: fn.name, call: n + 1, ...describe(fn.table, index) })));
      matched[fn.name] = [...new Set(calls.flatMap(made => made.matched))].sort((a, b) => a - b);
    });
    Object.assign(call, {
      status: 'answered',
      version: response.version,
      results: response.results,
      matched,
      calls: response.calls || {},
      // the rule that decided, for the explanation next to the result
      rule: table && hits.length ? describe(table, hits[0]) : null,
      rules: decided ? [] : rules
    });
    await tell('response', {});

    // 5 — results back into the process data
    try {
      this.#mapResults(run, element, call, decision, response);
    } catch (e) {
      if (!(e instanceof MappingError)) throw e;
      await fail(RULE_ERRORS.MAPPING, e.message);
    }
    await tell(call.targets.length ? 'mapped' : 'unmapped', {});
    await tell('end', {});
  }

  /** Why the runtime cannot answer, or null: a wrong URL, a stopped container, or one that is too slow. */
  #unreachable(settings, runtime) {
    if (!settings.url) {
      return { errorCode: RULE_ERRORS.MAPPING, message: 'The URL of the rule engine is not configured. Set the variable ' + VARIABLES.URL + '.' };
    }
    if (settings.url.replace(/\/+$/, '') !== (runtime.url || RUNTIME_URL) || runtime.state === 'stopped') {
      return {
        errorCode: RULE_ERRORS.UNREACHABLE,
        message: 'The rule engine is not reachable at ' + settings.url + '. Check the variable ' + VARIABLES.URL + ' and that the runtime container is running.'
      };
    }
    if (runtime.state === 'slow') {
      return { errorCode: RULE_ERRORS.TIMEOUT, message: 'The rule engine at ' + settings.url + ' did not answer in time: Read timed out after ' + settings.timeout + ' seconds' };
    }
    return null;
  }

  /** ModelDeployer.ensureRegistered: the file is stored, unless exactly this content already was. */
  #isNew(runtime, runtimeModel, dmn) {
    return this.registered.get(runtimeModel) !== JSON.stringify(dmn) || !runtime.versions(runtimeModel).length;
  }

  /** @returns what the run tells about the stored file, or null if there is nothing to tell */
  #register(run, element, call, runtime, runtimeModel, dmn) {
    const { entry, created } = runtime.register(runtimeModel, dmn, { source: 'PROJECT', author: this.model.project });
    this.registered.set(runtimeModel, JSON.stringify(dmn));
    call.registered = { version: entry.version, status: entry.status, created };
    if (created) {
      this.#log(run, 'info', element, 'Rule engine: stored version ' + entry.version + " of model '" + call.model + "' (source PROJECT, " + entry.status + ')');
    }
    if (entry.status !== 'ACTIVE') {
      this.#log(run, 'info', element, 'Rule engine: version ' + entry.version + " of model '" + call.model + "' from project " + this.model.project +
        ' is not active (' + entry.status + '). A newer version was activated, it stays the source of truth.');
    }
    return created || entry.status !== 'ACTIVE' ? { version: entry.version, status: entry.status, created } : null;
  }

  /**
   * DmnCreator: generates the file when it does not exist yet. The file these fields generated before is used and
   * never overwritten; another file of that name is an error, and so is an empty name.
   */
  #create(run, element, cfg, inputs, outputs) {
    const spec = generateDmn({ modelName: cfg.newModel, inputs, outputs }, this.model);
    const existing = this.project.find(modelNameFor(cfg.newModel));
    if (existing) {
      if (!generatedFrom(existing, inputs, outputs)) {
        throw new MappingError("A DMN named '" + spec.model + "' exists already (" + (existing.file || spec.file).split('/').pop() + "). Give the new DMN another name in 'New DMN name'.");
      }
      const wanted = decisionOf(existing, spec.decisions[0].name);
      return { dmn: existing, decision: (wanted || existing.decisions[0]).name, generated: false };
    }
    readableByName(inputs, outputs, this.model);
    this.project.add(spec);
    this.#log(run, 'info', element, 'Rule engine: generated ' + spec.file + " with decision '" + spec.decisions[0].name + "'. Open it in the DMN editor and fill in the rules.");
    return { dmn: spec, decision: spec.decisions[0].name, generated: true };
  }

  #mapResults(run, element, call, decision, response) {
    const results = response.results;
    const names = Object.keys(results);
    const main = decision ? results[decision] : names.length === 1 ? results[names[0]] : results;
    const assign = (expression, value, type) => {
      let converted;
      try {
        converted = fromJson(value, type, this.model.classes);
        assignTo(expression, converted, this.#scriptContext(run, element));
      } catch (e) {
        if (!(e instanceof MappingError) && !(e instanceof IvyScriptError)) throw e;
        throw new MappingError('Cannot write the result to ' + expression + ': ' + e.message);
      }
      call.targets.push({ expression, value: converted });
    };

    // each result to the field with its name
    const named = decision ? { [decision]: main } : results;
    Object.keys(named).forEach(name => {
      const field = findField(this.model, name);
      if (field) assign(field.expression, named[name], field.type);
      else if (isObject(named[name])) {
        // a structured result without a field of its own is unfolded: each entry to the field with its name
        Object.keys(named[name]).forEach(key => {
          const target = findField(this.model, key);
          if (target) assign(target.expression, named[name][key], target.type);
        });
      }
    });
  }
}
