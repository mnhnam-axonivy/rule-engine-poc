/**
 * The inscription view: the panel on the right where an element is configured.
 *
 * Tabs, group titles and field labels follow the real Designer. For the Business Rule Task
 * they are taken from the connector's RuleTaskEditor, including the emoji in the group titles.
 */
import { h, clear } from '../util.js';
import { icon, elementGlyph } from './icons.js';
import { section, field, note, textBox, checkbox, scriptInput, codeEditor, multiSelect } from './fields.js';
import { ruleConfig, configList, docOf, elementById } from '../model/process.js';
import { validateExpression, validateScript, positionOf } from '../model/ivyscript.js';
import { decisionOf, requiredInputs, inputTypes, describeNames, describeOutputs } from '../model/dmn.js';
import { parseFieldSelection, isFieldPath, typeOfPath } from '../model/mapping.js';
import { modelNameFor, generatedFrom } from '../model/dmn-generator.js';
import { CREATE_NEW } from '../model/runner.js';

const DEFAULT_TAB = { rule: 'configuration', script: 'output', start: 'request', task: 'task', gateway: 'condition', boundary: 'error', usertask: 'dialog' };

export class Inscription {
  /** @param {Object} app  the DesignerApp  @param {HTMLElement} root */
  constructor(app, root) {
    this.app = app;
    this.root = root;
    this.checks = [];
    this.controls = {}; // the inputs the walkthrough types into, by config key
    this.title = null;
  }

  /** Rebuilds the whole view for the current selection. */
  render() {
    const { app } = this;
    const t = app.t;
    clear(this.root);
    this.checks = [];
    this.controls = {};
    this.title = null;
    const el = app.selected();

    const closeButton = h('button.icon-btn.inscription-close', { type: 'button', title: t('toggle.inscription'), 'aria-label': t('toggle.inscription'), onclick: () => app.setInscriptionOpen(false) }, icon('close', 14));

    if (!el) {
      this.root.append(
        h('div.inscription-head', null, h('span.inscription-title.muted', null, t('inscription.empty.title')), closeButton),
        h('div.inscription-empty', null, icon('panelRight', 28), h('p', null, t('inscription.empty.text'))));
      return;
    }

    this.title = h('span.inscription-title');
    this.updateTitle();
    this.root.append(h('div.inscription-head', null, elementGlyph(el), this.title,
      h('span.spacer'),
      h('span.icon-btn.inert', { title: t('quick.inert') }, icon('help', 14)),
      closeButton));

    const tabs = this.tabsFor(el);
    const wanted = app.state.inscriptionTabs[el.id] || (el.type === 'CallSubStart' ? 'start' : el.type === 'ErrorEnd' ? 'error' : DEFAULT_TAB[el.kind]) || 'general';
    const active = tabs.find(tab => tab.id === wanted) || tabs[0];
    this.root.append(h('div.inscription-tabs', { role: 'tablist' }, tabs.map(tab =>
      h('button.inscription-tab' + (tab === active ? '.active' : ''), {
        type: 'button', role: 'tab', 'aria-selected': String(tab === active), 'data-tab': tab.id,
        onclick: () => app.setInscriptionTab(el, tab.id)
      }, icon(tab.icon, 13), h('span', null, tab.label), tab.dot ? h('span.tab-dot') : null))));
    const body = h('div.inscription-body', { role: 'tabpanel' });
    this.root.append(body);
    active.render(body);
    this.validate();
  }

  updateTitle() {
    const el = this.app.selected();
    if (this.title && el) this.title.textContent = el.typeLabel + (el.name ? ' - ' + el.name.replace(/\n/g, ' ') : '');
  }

  /** Re-runs the field checks (validation messages) without rebuilding anything. */
  validate() {
    this.checks.forEach(fn => fn());
  }

  tabsFor(el) {
    const general = { id: 'general', label: 'General', icon: 'info', render: body => this.general(el, body) };
    if (el.kind === 'rule') {
      return [general,
        { id: 'javaBean', label: 'Java Bean', icon: 'code', dot: true, render: body => this.javaBean(el, body) },
        { id: 'error', label: 'Error', icon: 'spark', render: body => this.errorTab(body) },
        { id: 'configuration', label: 'Configuration', icon: 'settings', dot: Object.keys(ruleConfig(el)).length > 0, render: body => this.configuration(el, body) }];
    }
    if (el.kind === 'script') return [general, { id: 'output', label: 'Output', icon: 'arrowRight', dot: true, render: body => this.scriptOutput(el, body) }];
    if (el.type === 'CallSubStart') {
      return [general,
        { id: 'start', label: 'Start', icon: 'play', dot: true, render: body => this.subStartTab(el, body) },
        { id: 'result', label: 'Result', icon: 'arrowRight', dot: true, render: body => this.subResultTab(el, body) }];
    }
    if (el.kind === 'boundary') {
      return [general,
        { id: 'error', label: 'Error', icon: 'spark', dot: true, render: body => this.boundaryErrorTab(el, body) },
        { id: 'output', label: 'Output', icon: 'arrowRight', dot: true, render: body => this.boundaryOutputTab(el, body) }];
    }
    if (el.kind === 'gateway') return [general, { id: 'condition', label: 'Condition', icon: 'list', dot: true, render: body => this.conditionTab(el, body) }];
    if (el.kind === 'start') {
      return [general,
        { id: 'start', label: 'Start', icon: 'play', dot: true, render: body => this.startTab(el, body) },
        { id: 'request', label: 'Request', icon: 'arrowRight', dot: true, render: body => this.requestTab(el, body) }];
    }
    if (el.kind === 'task') return [general, { id: 'task', label: 'Task', icon: 'list', dot: true, render: body => this.taskTab(el, body) }];
    if (el.kind === 'usertask') {
      return [general,
        { id: 'task', label: 'Task', icon: 'list', dot: true, render: body => this.taskTab(el, body) },
        { id: 'dialog', label: 'Dialog', icon: 'play', dot: true, render: body => this.dialogTab(el, body) },
        { id: 'output', label: 'Output', icon: 'arrowRight', dot: true, render: body => this.userTaskOutput(el, body) }];
    }
    if (el.type === 'ErrorEnd') return [general, { id: 'error', label: 'Error', icon: 'spark', dot: true, render: body => this.errorEndTab(el, body) }];
    return [general];
  }

  sectionState(el, id, fallback) {
    const key = el.id + ':' + id;
    const stored = this.app.state.sections[key];
    return { open: stored === undefined ? fallback : stored, onToggle: open => { this.app.state.sections[key] = open; } };
  }

  // ── tabs shared by all elements ────────────────────────────────────────────

  general(el, body) {
    const { app } = this;
    const description = field('Description', textBox({ value: el.description, rows: 2, onInput: v => app.changeElement(el, e => { e.description = v; }) }));
    description.setAttribute('data-tour', 'description');
    const tags = el.tags || [];
    const tagList = tags.length
      ? [h('div.tags', { 'data-tour': 'tags' }, tags.map(tag => h('span.chip.plain', null, tag)))]
      : [note(app.t('inscription.orientation'))];
    body.append(
      section({ title: 'Name / Description', ...this.sectionState(el, 'name', true) },
        field('Name', textBox({ value: el.name, rows: 2, onInput: v => { app.changeElement(el, e => { e.name = v; }); this.updateTitle(); } })),
        description),
      section({ title: 'Tags', ...this.sectionState(el, 'tags', tags.length > 0) }, ...tagList));
  }

  // ── script ─────────────────────────────────────────────────────────────────

  scriptOutput(el, body) {
    const { app } = this;
    const editor = codeEditor({
      value: (el.config.output && el.config.output.code) || '',
      language: 'ivyscript',
      label: 'Code',
      onInput: v => app.changeElement(el, e => { e.config.output = { ...(e.config.output || {}), code: v }; }, 'code')
    });
    const codeField = field(null, editor);
    codeField.setAttribute('data-tour', 'code');
    this.controls.code = editor;
    this.checks.push(() => {
      const code = (el.config.output && el.config.output.code) || '';
      const error = validateScript(code, app.scriptContext(el));
      if (error) {
        const at = positionOf(code, error.pos);
        editor.setMarkers([{ line: at.line, message: error.message }]);
        codeField.setMessage('error', 'Line ' + at.line + ': ' + error.message);
      } else {
        editor.setMarkers([]);
        codeField.setMessage('', '');
      }
    });
    body.append(
      section({ title: 'Mapping', ...this.sectionState(el, 'mapping', false) },
        h('table.mapping', null,
          h('thead', null, h('tr', null, h('th', null, 'Attribute'), h('th', null, 'Type'), h('th', null, 'Expression'))),
          h('tbody', null, h('tr', null, h('td', null, 'out'), h('td', null, docOf(app.model, el).dataClass.simpleName), h('td', null, h('code', null, 'in'))))),
        note(app.t('inscription.orientation'))),
      section({ title: 'Code', ...this.sectionState(el, 'code', true) }, codeField),
      checkbox({ label: 'Disable Permission Checks (Execute this Script Step as SYSTEM)', disabled: true }));
  }

  // ── start and task ─────────────────────────────────────────────────────────

  startTab(el, body) {
    body.append(
      section({ title: 'Signature', ...this.sectionState(el, 'signature', true) },
        field('Name', textBox({ value: el.config.signature || 'start', readOnly: true, mono: true })),
        note(this.app.t('inscription.orientation'))),
      section({ title: 'Mapping', ...this.sectionState(el, 'mapping', false) }, note(this.app.t('inscription.orientation'))));
  }

  requestTab(el, body) {
    const request = el.config.request || {};
    body.append(
      checkbox({ label: 'Yes, this can be started with a HTTP-Request / -Link', checked: true, disabled: true }),
      section({ title: 'Name / Description', ...this.sectionState(el, 'request', true) },
        field('Name', textBox({ value: request.name || '', readOnly: true })),
        field('Description', textBox({ value: request.description || '', readOnly: true, rows: 2 })),
        field('Category', textBox({ value: request.category || '', readOnly: true })),
        note(this.app.t('inscription.orientation'))));
  }

  // ── callable sub-process start: a tool, when it carries the tag "tool" ──────

  paramTable(params, tour) {
    return h('table.mapping', { 'data-tour': tour },
      h('thead', null, h('tr', null, h('th', null, 'Name'), h('th', null, 'Type'), h('th', null, 'Description'))),
      h('tbody', null, (params || []).map(p => h('tr', null,
        h('td', null, h('code', null, p.name)), h('td', null, p.type || 'String'), h('td', null, p.desc || '')))));
  }

  mapTable(map, tour) {
    return h('table.mapping', { 'data-tour': tour },
      h('thead', null, h('tr', null, h('th', null, 'Attribute'), h('th', null, 'Expression'))),
      h('tbody', null, Object.keys(map || {}).map(key => h('tr', null,
        h('td', null, h('code', null, key)), h('td', null, h('code', null, map[key]))))));
  }

  subStartTab(el, body) {
    const { app } = this;
    const input = el.config.input || {};
    const signature = textBox({ value: el.config.signature || '', mono: true, onInput: v => app.changeElement(el, e => { e.config.signature = v; }, 'signature') });
    const signatureField = field('Name', signature);
    signatureField.setAttribute('data-tour', 'signature');
    this.controls.signature = signature;
    body.append(
      section({ title: 'Signature', ...this.sectionState(el, 'signature', true) }, signatureField,
        field('Definition', this.paramTable(input.params, 'params'))),
      section({ title: 'Mapping', ...this.sectionState(el, 'mapping', true) }, this.mapTable(input.map, 'inputMap')));
  }

  subResultTab(el, body) {
    const result = el.config.result || {};
    body.append(
      section({ title: 'Result', ...this.sectionState(el, 'result', true) }, field('Definition', this.paramTable(result.params, 'resultParams'))),
      section({ title: 'Mapping', ...this.sectionState(el, 'resultMapping', true) }, this.mapTable(result.map, 'resultMap')));
  }

  // ── error boundary event ───────────────────────────────────────────────────

  boundaryErrorTab(el, body) {
    const { app } = this;
    const code = textBox({ value: el.config.errorCode || '', mono: true, onInput: v => app.changeElement(el, e => { e.config.errorCode = v; }, 'errorCode') });
    const codeField = field('Error Code', code);
    codeField.setAttribute('data-tour', 'errorCode');
    this.controls.errorCode = code;
    body.append(section({ title: 'Error', ...this.sectionState(el, 'error', true) }, codeField, note(app.t('boundary.catches'))));
  }

  boundaryOutputTab(el, body) {
    const map = (el.config.output && el.config.output.map) || {};
    const text = value => (Array.isArray(value) ? value.join('\n') : value || '');
    body.append(
      section({ title: 'Mapping', ...this.sectionState(el, 'mapping', Object.keys(map).length > 0) }, this.mapTable(map, 'outputMap'), note(this.app.t('boundary.mapping'))),
      section({ title: 'Code', ...this.sectionState(el, 'code', true) },
        this.codeField(el, () => text((el.config.output || {}).code), (e, v) => { e.config.output = { ...(e.config.output || {}), code: v }; }, { error: {} }),
        note(this.app.t('boundary.mapping'))));
  }

  // ── gateway ────────────────────────────────────────────────────────────────

  conditionTab(el, body) {
    const { app } = this;
    const conditions = el.config.conditions || {};
    const keys = Object.keys(conditions);
    el.outgoing.forEach(c => { if (!keys.includes(c.rawId)) keys.push(c.rawId); });
    const rows = keys.map(key => {
      const out = el.outgoing.find(c => c.rawId === key);
      const target = out ? elementById(app.model, out.to) : null;
      return h('tr', null,
        h('td', null, target ? target.name || target.typeLabel : key),
        h('td', null, conditions[key] ? h('code', null, conditions[key]) : h('span.muted', null, app.t('condition.none'))));
    });
    body.append(section({ title: 'Condition', ...this.sectionState(el, 'condition', true) },
      h('table.mapping', { 'data-tour': 'conditions' },
        h('thead', null, h('tr', null, h('th', null, app.t('condition.path')), h('th', null, 'Expression'))),
        h('tbody', null, rows)),
      note(app.t('condition.order'))));
  }

  /** A code field of an element: editable, checked like a script step. `vars` names what the code may use besides in/out. */
  codeField(el, read, write, vars) {
    const { app } = this;
    const editor = codeEditor({ value: read(), language: 'ivyscript', label: 'Code', onInput: v => app.changeElement(el, e => write(e, v), 'code') });
    const codeField = field(null, editor);
    codeField.setAttribute('data-tour', 'code');
    this.controls.code = editor;
    this.checks.push(() => {
      const code = read();
      const error = validateScript(code, app.scriptContext(el, vars));
      if (error) {
        const at = positionOf(code, error.pos);
        editor.setMarkers([{ line: at.line, message: error.message }]);
        codeField.setMessage('error', 'Line ' + at.line + ': ' + error.message);
      } else {
        editor.setMarkers([]);
        codeField.setMessage('', '');
      }
    });
    return codeField;
  }

  // ── user task ──────────────────────────────────────────────────────────────

  dialogTab(el, body) {
    const dialog = field('Dialog', textBox({ value: el.config.dialog || '', readOnly: true, mono: true }));
    dialog.setAttribute('data-tour', 'dialog');
    body.append(
      section({ title: 'Dialog', ...this.sectionState(el, 'dialog', true) }, dialog),
      section({ title: 'Mapping', ...this.sectionState(el, 'callMapping', true) }, this.mapTable((el.config.call && el.config.call.map) || {}, 'callMap')));
  }

  userTaskOutput(el, body) {
    const output = el.config.output || {};
    const text = value => (Array.isArray(value) ? value.join('\n') : value || '');
    body.append(
      section({ title: 'Mapping', ...this.sectionState(el, 'mapping', true) }, this.mapTable(output.map || {}, 'outputMap'), note(this.app.t('usertask.result'))),
      section({ title: 'Code', ...this.sectionState(el, 'code', true) },
        this.codeField(el, () => text((el.config.output || {}).code), (e, v) => { e.config.output = { ...(e.config.output || {}), code: v }; }, { result: {} })));
  }

  // ── error end ──────────────────────────────────────────────────────────────

  errorEndTab(el, body) {
    const text = value => (Array.isArray(value) ? value.join('\n') : value || '');
    const thrown = field('Error Code', textBox({ value: (el.config.throws && el.config.throws.error) || '', readOnly: true, mono: true }));
    thrown.setAttribute('data-tour', 'throws');
    body.append(
      section({ title: 'Error', ...this.sectionState(el, 'error', true) }, thrown),
      section({ title: 'Code', ...this.sectionState(el, 'code', true) },
        this.codeField(el, () => text(el.config.code), (e, v) => { e.config.code = v; }, { error: {} })));
  }

  taskTab(el, body) {
    const { app } = this;
    const tagged = (node, name) => { node.setAttribute('data-tour', name); return node; };
    const task = el.config.task || {};
    const responsible = task.responsible;
    const who = !responsible ? 'Role from Attr.  Everybody'
      : responsible.type === 'USER_FROM_ATTRIBUTE' ? 'User from Attr.  ' + (responsible.script || '')
        : (responsible.type || '') + '  ' + (responsible.script || '');
    const responsibleField = field('Responsible', textBox({ value: who, readOnly: true, mono: Boolean(responsible) }));
    responsibleField.setAttribute('data-tour', 'responsible');
    body.append(
      section({ title: 'Details', ...this.sectionState(el, 'details', true) },
        tagged(field('Name', textBox({ value: task.name || '', readOnly: true, placeholder: el.name })), 'taskName'),
        tagged(field('Description', textBox({ value: task.description || '', readOnly: true, rows: 2 })), 'taskDescription'),
        responsibleField),
      section({ title: 'Options', ...this.sectionState(el, 'options', true) },
        checkbox({
          label: 'Skip Tasklist',
          checked: Boolean(task.skipTasklist),
          onChange: checked => app.changeElement(el, e => { e.config.task = { ...(e.config.task || {}), skipTasklist: checked }; }, 'skipTasklist')
        }),
        checkbox({ label: 'Delay', disabled: true })));
  }

  // ── Business Rule Task ─────────────────────────────────────────────────────

  javaBean(el, body) {
    body.append(
      section({ title: 'Java Class', ...this.sectionState(el, 'class', true) },
        field(null, textBox({ value: el.config.javaClass || '', readOnly: true, mono: true })),
        note(this.app.t('inscription.orientation'))));
  }

  errorTab(body) {
    const el = this.app.selected();
    body.append(
      section({ title: 'Error', ...this.sectionState(el, 'error', true) },
        field('Error', textBox({ value: 'ivy:error:program:exception', readOnly: true, mono: true }))),
      section({ title: 'Timeout', ...this.sectionState(el, 'timeout', false) },
        field('Seconds', textBox({ value: '', readOnly: true })),
        field('Error', textBox({ value: 'ivy:error:program:timeout', readOnly: true, mono: true }))),
      note(this.app.t('inscription.orientation')));
  }

  /** Groups, labels and help texts are the ones of the connector's RuleTaskEditor. */
  configuration(el, body) {
    const { app } = this;
    const t = app.t;
    const cfg = ruleConfig(el);
    this.selectCreated(cfg);
    const layer = app.layer;
    const attributes = this.fieldPaths();
    const set = (key, value) => app.setRuleConfig(el, key, value);
    const tagged = (node, name) => { node.setAttribute('data-tour', name); return node; };

    // ⚖️ Decision
    const decisionSelect = multiSelect({
      values: configList(cfg.decision), options: this.decisionOptions(), layer, placeholder: t('decision.placeholder'),
      onChange: v => set('decision', v.join(','))
    });
    const decisionField = tagged(field('DMN decision:', decisionSelect), 'decision');
    this.checks.push(() => {
      const selected = configList(cfg.decision);
      if (!selected.length) { decisionField.setMessage('warning', t('check.decision.empty')); return; }
      if (selected.length > 1) { decisionField.setMessage('warning', t('check.decision.many')); return; }
      if (selected[0] === CREATE_NEW) {
        const ready = parseFieldSelection(cfg.inputs).length && parseFieldSelection(cfg.outputs).length;
        decisionField.setMessage(ready ? 'info' : 'warning', t(ready ? 'check.decision.create' : 'check.decision.createEmpty'));
        return;
      }
      const [model, decision] = selected[0].split('#');
      const dmn = app.findDmn(model);
      if (!dmn) decisionField.setMessage('warning', t('check.decision.noFile', { model }));
      else if (decision && !decisionOf(dmn, decision)) decisionField.setMessage('error', t('check.decision.unknown', { decision, file: dmn.file }));
      else decisionField.setMessage('', '');
    });
    const newModelBox = textBox({ value: cfg.newModel || '', mono: true, onInput: v => set('newModel', v) });
    const newModelField = tagged(field('New DMN name:', newModelBox, {
      help: 'Required, e.g. member-discount for dmn/member-discount.dmn. A name that another .dmn file has already is an error. The file is never overwritten.'
    }), 'newModel');
    this.checks.push(() => {
      if (configList(cfg.decision)[0] !== CREATE_NEW) { newModelField.setMessage('', ''); return; }
      let model;
      try {
        model = modelNameFor(cfg.newModel);
      } catch (e) {
        newModelField.setMessage('warning', t('check.newModel.empty'));
        return;
      }
      const dmn = app.findDmn(model);
      if (dmn && !generatedFrom(dmn, parseFieldSelection(cfg.inputs), parseFieldSelection(cfg.outputs))) newModelField.setMessage('error', t('check.newModel.taken', { file: dmn.file }));
      else newModelField.setMessage('', '');
    });

    // ✨ New DMN: the name above, then the fields the new file is made of
    const selectionCheck = (fieldEl, key, emptyKey) => () => {
      const selection = parseFieldSelection(cfg[key]);
      const noPath = selection.find(item => !isFieldPath(item.expression));
      const unknown = selection.find(item => isFieldPath(item.expression) && typeOfPath(app.model, item.expression) === null);
      if (noPath) fieldEl.setMessage('error', t('check.field.notPath', { entry: noPath.expression }));
      else if (unknown) fieldEl.setMessage('error', t('check.field.unknown', { path: unknown.expression, dataClass: app.model.dataClass.simpleName }));
      else if (!selection.length && configList(cfg.decision)[0] === CREATE_NEW) fieldEl.setMessage('warning', t(emptyKey));
      else fieldEl.setMessage('', '');
    };
    const inputsBox = textBox({ value: cfg.inputs || '', mono: true, placeholder: 'in.member.purchaseAmount, in.member.type', onInput: v => set('inputs', v) });
    const inputsField = tagged(field('Input fields:', inputsBox, {
      help: 'The fields the new decision reads, separated by comma or line break, e.g. in.member.purchaseAmount, in.member.type. Each becomes an input named like its field.'
    }), 'inputs');
    this.checks.push(selectionCheck(inputsField, 'inputs', 'check.inputs.empty'));
    const outputsBox = textBox({ value: cfg.outputs || '', mono: true, placeholder: 'in.member.discount', onInput: v => set('outputs', v) });
    const outputsField = tagged(field('Output fields:', outputsBox, {
      help: 'The fields the new decision fills, e.g. in.member.discount. Each becomes an output named like its field.'
    }), 'outputs');
    this.checks.push(selectionCheck(outputsField, 'outputs', 'check.outputs.empty'));

    Object.assign(this.controls, {
      decision: { setValue: v => decisionSelect.setValues(configList(v)) },
      inputs: inputsBox,
      outputs: outputsBox,
      newModel: newModelBox
    });

    const newDmn = configList(cfg.decision)[0] === CREATE_NEW || Boolean(cfg.newModel || cfg.inputs || cfg.outputs);
    body.append(
      section({ title: '⚖️ Decision', ...this.sectionState(el, 'decision', true) }, decisionField,
        note('A decision of a .dmn file of this project. Inputs and results go to the process data fields of the same name.'),
        note("Choose 'New DMN...' to create a new DMN file.")),
      tagged(section({ title: '✨ New DMN', ...this.sectionState(el, 'newDmn', newDmn) },
        note("Only used with 'New DMN...': the file to create and the fields it is made of. With an existing decision these fields are ignored."),
        newModelField, inputsField, outputsField), 'newDmnGroup'));
  }

  /**
   * Once the file of "New DMN..." exists, the element refers to its decision like to any other.
   * In the Designer that happens when the mask is opened again (rule-engine:
   * DesignTimeCreation.selectCreated), and so it does here. Another file of that name is not
   * selected: running the element reports it.
   */
  selectCreated(cfg) {
    if (configList(cfg.decision)[0] !== CREATE_NEW) return;
    const inputs = parseFieldSelection(cfg.inputs);
    const outputs = parseFieldSelection(cfg.outputs);
    if (!inputs.length || !outputs.length) return;
    let dmn = null;
    try {
      dmn = this.app.findDmn(modelNameFor(cfg.newModel));
    } catch (e) {
      return; // no name yet
    }
    if (!dmn || !dmn.decisions.length || !generatedFrom(dmn, inputs, outputs)) return;
    const wanted = outputs.length === 1 ? outputs[0].name : dmn.model;
    const decision = dmn.decisions.find(d => d.name.toLowerCase() === wanted.toLowerCase() || d.name.toLowerCase() === (wanted + 'Result').toLowerCase()) || dmn.decisions[0];
    cfg.decision = dmn.model + '#' + decision.name;
  }

  /**
   * What the "Decision" dropdown offers, as in RuleTaskEditor: every decision of every .dmn
   * file of the project, the whole model when it has several decisions, and the entry that
   * creates a new file.
   */
  decisionOptions() {
    const options = [];
    this.app.dmnFiles.forEach(file => {
      const dmn = file.current;
      const types = inputTypes(dmn);
      dmn.decisions.forEach(decision => options.push({
        value: dmn.model + '#' + decision.name,
        label: dmn.model + ' › ' + decision.name,
        icon: '📋',
        description: dmn.file + ' · in: ' + describeNames(requiredInputs(dmn, decision.name) || [], types) + ' · out: ' + describeOutputs(dmn, decision)
      }));
      if (dmn.decisions.length > 1) {
        options.push({
          value: dmn.model, label: dmn.model + ' › all decisions', icon: '🗂️',
          description: dmn.file + ' · evaluates every decision of the model · in: ' + describeNames(dmn.inputs.map(input => input.name), types)
            + ' · out: ' + dmn.decisions.map(decision => describeOutputs(dmn, decision)).join(', ')
        });
      }
    });
    options.push({
      value: CREATE_NEW, label: 'New DMN...', icon: '✨',
      description: 'Generates a .dmn file from the group New DMN when you open this mask again or run the process. You only fill in the rules.'
    });
    return options;
  }

  /** The fields of the process data as paths, nested data classes included (in.member.type). */
  fieldPaths() {
    const { model } = this.app;
    const paths = [];
    const walk = (cls, prefix, depth) => {
      cls.fields.forEach(f => {
        paths.push({ code: prefix + '.' + f.name, type: String(f.type || 'String').split('.').pop(), comment: f.comment });
        const nested = model.classes[f.type];
        if (nested && depth < 2) walk(nested, prefix + '.' + f.name, depth + 1);
      });
    };
    walk(model.dataClass, 'in', 0);
    return paths;
  }
}
