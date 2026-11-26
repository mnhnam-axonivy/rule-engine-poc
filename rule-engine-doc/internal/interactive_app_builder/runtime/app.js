import { h, clear, Emitter, openPopover, debounce, brief } from './util.js';
import { createTranslator, normalizeLanguage } from './i18n.js';
import { loadProcess, elementById, ruleConfig, originalOf, restore, startElement, nextOf, docOf, docView } from './model/process.js';
import { ProcessRunner, readSettings, VARIABLES } from './model/runner.js';
import { parseYaml } from './model/yaml.js';
import { parseDmn, cloneDmn, fingerprint, logicsOf, logicOf } from './model/dmn.js';
import { DecisionRuntime, RuntimeError } from './model/decision-runtime.js';
import { ThreeStage } from './stage/three-stage.js';
import { CanvasStage } from './stage/canvas-stage.js';
import { attachController } from './stage/controller.js';
import { connectorPath } from './stage/layout.js';
import { icon } from './ui/icons.js';
import { codeEditor, field, textBox } from './ui/fields.js';
import { evaluateTable, FeelError } from './model/feel.js';
import { decisionTable } from './ui/dmn-table.js';
import { Inscription } from './ui/inscription.js';
import { BottomPanel } from './ui/panel.js';
import { ArchitectureView } from './ui/architecture.js';
import { PortalView } from './ui/portal.js';
import { Tour } from './ui/tour.js';

const NARROW = 1000;
const SMALL = 620;
const clone = value => JSON.parse(JSON.stringify(value));
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
const freshVisual = () => ({ executed: {}, connectors: {}, active: null, error: null, breakpoints: new Set() });

export class DesignerApp {
  constructor(root, definition, options = {}) {
    this.root = root;
    this.def = definition;
    this.options = options;
    this.lang = normalizeLanguage(options.lang);
    this.t = createTranslator(this.lang, definition.strings);
    this.model = loadProcess(definition.process, definition.dataClass, {
      file: definition.file, project: definition.project, classes: definition.classes, cms: definition.cms
    });
    this.dmnSources = (definition.dmn || []).map(file => parseDmn(file.text, { model: file.model, file: file.file }));
    this.dmnFiles = this.dmnSources.map(dmn => ({ id: dmn.model, original: dmn, current: cloneDmn(dmn), generated: false }));
    this.dmnEditors = {};
    this.dmnControls = {};
    this.lastHits = {};
    this.runtime = new DecisionRuntime();
    this.ownerDialog = null;
    this.variablesText = definition.variables || '';
    this.sources = definition.sources || [];
    this.sourceText = {};
    this.sources.forEach(source => { this.sourceText[source.id] = source.text; });
    this.sourceCode = {};
    this.events = new Emitter();
    const motion = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
    this.reducedMotion = options.reducedMotion !== undefined ? options.reducedMotion : Boolean(motion && motion.matches);
    this.state = {
      tab: 'process',
      selection: null,
      hover: null,
      inscriptionOpen: true,
      inscriptionTabs: {},
      sections: {},
      panelTab: 'log',
      panelOpen: true,
      panelPinned: false,
      status: 'idle',
      statusParams: null
    };
    this.visual = freshVisual();
    this.data = null;
    this.resume = null;
    this.runGeneration = 0;
    this.caption = '';
    this.toastTimers = new Map();
    this.memo = {};
    this.mode = null;
    this.runHooks = null;

    this.runner = new ProcessRunner({
      model: this.model,
      runtime: () => this.runtime,
      project: { find: model => this.findDmn(model), add: dmn => this.addDmn(dmn) },
      io: this.createIo()
    });
    this.buildSkeleton();
    this.createStage();
    this.inscription = new Inscription(this, this.inscriptionEl);
    this.panel = new BottomPanel(this, this.panelEl);
    this.arch = new ArchitectureView(this, this.archEditor);
    this.portal = definition.portal ? new PortalView(this, this.portalEditor) : null;
    this.tour = new Tour(this, definition.tour);
    this.renderChrome();
    if (!this.tour.available) this.setMode('explore');
    else if (options.autoplay) this.tour.start(Math.max(0, Math.min(Number(options.chapter) || 0, this.tour.chapters.length - 1)));
    else this.tour.showIntro();
  }

  buildSkeleton() {
    const root = clear(this.root);
    root.classList.add('app');
    this.tabsEl = h('div.tabs');
    this.toolbarEl = h('div.toolbar');
    this.canvasHost = h('div.canvas-host');
    this.overlayEl = h('div.overlays');
    this.canvasHost.append(this.overlayEl);
    this.inscriptionEl = h('aside.inscription');
    this.processEditor = h('div.editor.editor-process', null,
      h('div.editor-main', null, this.toolbarEl, this.canvasHost), this.inscriptionEl);
    this.variablesEditor = h('div.editor.editor-variables', { hidden: true });
    this.archEditor = h('div.editor.editor-arch', { hidden: true });
    this.portalEditor = h('div.editor.editor-portal', { hidden: true });
    this.sourceEditors = {};
    this.sources.forEach(source => { this.sourceEditors[source.id] = h('div.editor.editor-source', { hidden: true }); });
    this.panelEl = h('div.panel');
    this.playerEl = h('div.player');
    this.layer = h('div.layer');
    this.workbench = h('div.workbench', null, this.archEditor, this.portalEditor, this.processEditor, this.sources.map(source => this.sourceEditors[source.id]), this.variablesEditor);
    root.append(this.tabsEl, this.workbench, this.panelEl, this.playerEl, this.layer);
  }

  createStage() {
    const wanted = this.options.renderer;
    this.stages = {};
    this.detachControllers = [];
    this.fellBack = false;
    this.model.docs.forEach(doc => {
      const view = docView(this.model, doc.id);
      const scene = { model: view, stateOf: id => this.stateOf(id), connectorState: route => this.connectorState(route) };
      let stage = null;
      if (wanted !== 'canvas' && ThreeStage.isSupported()) {
        try {
          stage = new ThreeStage(this.canvasHost, scene);
        } catch (e) {
          console.warn('[reInteractive] WebGL stage unavailable, using the 2D canvas instead.', e);
        }
      }
      if (!stage && wanted !== 'canvas') this.fellBack = true;
      if (!stage) stage = new CanvasStage(this.canvasHost, scene);
      this.canvasHost.insertBefore(stage.canvas, this.overlayEl);
      stage.canvas.setAttribute('role', 'application');
      stage.canvas.style.display = doc.id === 'process' ? '' : 'none';
      stage.setReducedMotion(this.reducedMotion);
      stage.needsFit = doc.id !== 'process'; // fitted when its tab is first opened
      this.stages[doc.id] = stage;

      this.detachControllers.push(attachController(stage, {
        model: () => view,
        select: el => this.select(el ? el.id : null),
        open: el => { this.select(el.id); this.setInscriptionOpen(true); },
        hover: el => {
          const previous = this.state.hover;
          this.state.hover = el ? el.id : null;
          if (previous) this.refresh(previous);
          if (el) this.refresh(el.id);
        },
        moved: el => stage.refresh(el.id),
        wheelZooms: () => true,
        wheelBlocked: () => {},
        key: key => this.canvasKey(key)
      }));
    });
    this.stage = this.stages.process;

    const onResize = debounce(() => {
      this.updateLayout();
      this.viewStage().resize();
    }, 30);
    this.resizeObserver = new ResizeObserver(onResize);
    this.resizeObserver.observe(this.canvasHost);
    this.resizeObserver.observe(this.root);
    this.updateLayout(true);
    this.stage.resize();
  }

  viewStage() {
    return this.stages[this.state.tab] || this.stage;
  }

  refresh(target) {
    const el = target === 'all' || target === 'connectors' ? null : elementById(this.model, target);
    Object.keys(this.stages).forEach(doc => {
      if (!el || el.doc === doc) this.stages[doc].refresh(target);
    });
  }

  renderChrome() {
    this.root.lang = this.lang;
    this.model.docs.forEach(doc => this.stages[doc.id].canvas.setAttribute('aria-label', this.t('canvas.label', { file: doc.file })));
    this.tour.render();
    this.renderTabs();
    this.renderToolbar();
    this.renderOverlays();
    this.inscription.render();
    this.renderVariables();
    this.renderSources();
    this.renderDmn();
    this.arch.render();
    if (this.portal) this.portal.render();
    this.panel.render();
    this.applyStatus();
    if (this.fellBack) {
      this.fellBack = false;
      this.toast(this.t('renderer.fallback'), { duration: 6000 });
    }
  }

  stateOf(id) {
    return {
      selected: this.state.selection === id,
      hover: this.state.hover === id,
      executed: this.visual.executed[id] || 0,
      active: this.visual.active === id,
      error: this.visual.error === id,
      breakpoint: false,
      ghostLabel: null
    };
  }

  connectorState(route) {
    return { executed: (this.visual.connectors[route.id] || 0) > 0, dashed: route.from.removed || route.to.removed };
  }

  scriptContext(el, vars) {
    const dataClass = el ? docOf(this.model, el).dataClass : this.model.dataClass;
    return { data: {}, fields: dataClass.fields, dataClassName: dataClass.name, classes: this.model.classes, cms: this.model.cms, vars };
  }

  emit(event) {
    if (this.arch) this.arch.update();
    if (this.portal && event.type !== 'portal') this.portal.update();
    this.events.emit(event);
  }

  async hook(name, payload) {
    const fn = this.runHooks && this.runHooks[name];
    if (fn) await fn(payload);
  }

  setMode(mode) {
    if (this.mode === mode) return;
    this.mode = mode;
    this.root.dataset.mode = mode;
    if (mode === 'explore') {
      this.setPanelOpen(true);
      if (this.root.dataset.layout !== 'narrow') this.setInscriptionOpen(true);
    } else {
      this.select(null);
      this.arch.show(null);
      this.setInscriptionOpen(false);
      this.setPanelOpen(false);
    }
    requestAnimationFrame(() => {
      if (this.disposed) return;
      this.stage.resize();
      this.stage.fit({ animate: false });
    });
  }

  renderTabs() {
    const t = this.t;
    clear(this.tabsEl);
    const tab = (id, iconName, label, extra) => h('button.tab' + (this.state.tab === id ? '.active' : ''), {
      type: 'button', role: 'tab', 'aria-selected': String(this.state.tab === id), 'data-tab': id, onclick: () => this.setTab(id)
    }, icon(iconName, 14), h('span', null, label), extra);
    const changed = this.variablesText !== (this.def.variables || '');
    this.variablesDot = h('span.tab-mark' + (changed ? '.changed' : ''));
    this.aboutButton = h('button.badge', { type: 'button', 'aria-haspopup': 'dialog', onclick: () => this.showAbout() }, icon('info', 13), t('badge'));
    this.replayButton = this.tour.available
      ? h('button.badge.replay', { type: 'button', 'aria-haspopup': 'menu', onclick: () => this.showReplay() }, icon('play', 11), t('tour.replay'))
      : null;
    this.tabsEl.append(
      h('div.tab-strip', { role: 'tablist' },
        tab('arch', 'extension', t('arch.tab')),
        this.model.docs.map(doc => tab(doc.id, 'process', doc.file)),
        this.dmnFiles.map(file => tab('dmn:' + file.id, 'list', file.current.file.split('/').pop(), h('span.tab-mark' + (this.dmnChanged(file) ? '.changed' : '')))),
        this.sources.map(source => tab('src:' + source.id, source.language === 'java' ? 'code' : 'yaml', source.file)),
        tab('variables', 'yaml', 'variables.yaml', this.variablesDot),
        this.portal ? tab('portal', 'pointer', t('portal.tabName')) : null),
      h('span.spacer'),
      this.replayButton,
      this.aboutButton);
  }

  setTab(id) {
    if (this.state.tab === id) return;
    this.state.tab = id;
    const stage = this.stages[id];
    this.processEditor.hidden = !stage;
    this.variablesEditor.hidden = id !== 'variables';
    this.archEditor.hidden = id !== 'arch';
    this.portalEditor.hidden = id !== 'portal';
    // the architecture and the Portal are no files of the Designer: they have no bottom panel
    this.root.dataset.tab = id === 'arch' || id === 'portal' ? id : 'file';
    if (id === 'arch') this.arch.layout();
    this.sources.forEach(source => { this.sourceEditors[source.id].hidden = id !== 'src:' + source.id; });
    Object.keys(this.dmnEditors).forEach(model => { this.dmnEditors[model].hidden = id !== 'dmn:' + model; });
    this.renderTabs();
    if (stage) {
      Object.keys(this.stages).forEach(doc => { this.stages[doc].canvas.style.display = doc === id ? '' : 'none'; });
      const selected = this.selected();
      if (selected && selected.doc !== id) this.select(null);
      stage.resize();
      if (stage.needsFit) {
        stage.needsFit = false;
        stage.fit({ animate: false });
      }
      stage.invalidate();
    } else if (id === 'variables' && this.variablesCode) this.variablesCode.textarea.focus();
    this.emit({ type: 'tab', tab: id });
  }

  showAbout() {
    const t = this.t;
    const rules = t('about.rules').split('|').filter(Boolean);
    const close = openPopover(this.layer, this.aboutButton, h('div.about', null,
      h('h3', null, t('about.title')),
      h('p', null, t('about.p1')),
      h('p', null, t('about.p2', { file: this.def.file })),
      h('p', null, t('about.p3')),
      rules.length ? h('ul', null, rules.map(r => h('li', null, r))) : null,
      h('div.about-actions', null, h('button.btn', { type: 'button', onclick: () => close() }, t('about.close')))), { align: 'right' });
  }

  showReplay() {
    const t = this.t;
    const item = (label, index) => h('button.menu-item', { type: 'button', role: 'menuitem', onclick: () => { close(); this.tour.start(index); } }, label);
    const close = openPopover(this.layer, this.replayButton, h('div.menu', { role: 'menu' },
      item(t('tour.replay.all'), 0),
      h('div.menu-title', null, t('tour.replay.chapters')),
      this.tour.chapters.map((c, i) => item((i + 1) + ' · ' + t('tour.chapter.' + c.id), i))), { align: 'right' });
  }

  updateLayout(initial = false) {
    const width = this.root.clientWidth;
    if (!width) return;
    // Narrow: the inscription view slides over the canvas instead of taking space beside it,
    // so the process stays readable. Small: controls drop their labels.
    const narrow = width < NARROW;
    const was = this.root.dataset.layout;
    this.root.dataset.layout = narrow ? 'narrow' : 'wide';
    this.root.classList.toggle('small', width < SMALL);
    if ((initial || was === undefined) && narrow) this.setInscriptionOpen(false);
  }

  /** In the narrow layout the inscription view covers part of the canvas: keep the selection beside it. */
  revealSelection() {
    const el = this.selected();
    const stage = el ? this.stages[el.doc] : null;
    if (!el || this.root.dataset.layout !== 'narrow' || !this.state.inscriptionOpen || stage.view.tilt) return;
    const host = this.canvasHost.getBoundingClientRect();
    const free = this.inscriptionEl.getBoundingClientRect().left - host.left;
    if (free < 160) return;
    const x = stage.worldToClient(el.x, el.y).x - host.left;
    const half = (el.w / 2 + 28) * stage.view.zoom;
    if (x + half <= free && x - half >= 0) return;
    stage.effects.moveView({ x: stage.view.x + (x - free / 2) / stage.view.zoom }, 320);
  }

  renderToolbar() {
    const t = this.t;
    clear(this.toolbarEl);
    const inert = name => h('span.icon-btn.inert', { title: t('quick.inert') }, icon(name, 15));
    this.inscriptionToggle = h('button.icon-btn' + (this.state.inscriptionOpen ? '.active' : ''), {
      type: 'button', title: t('toggle.inscription'), 'aria-label': t('toggle.inscription'), 'aria-pressed': String(this.state.inscriptionOpen),
      onclick: () => this.setInscriptionOpen(!this.state.inscriptionOpen)
    }, icon('panelRight', 15));
    this.toolbarEl.append(
      h('div.tool-group', null, h('span.icon-btn.active', { title: 'Selection Tool' }, icon('pointer', 15)), inert('marquee'), h('span.tool-sep'), inert('undo'), inert('redo')),
      h('div.tool-group', null, inert('database'), inert('settings'), this.inscriptionToggle));
  }

  renderOverlays() {
    const t = this.t;
    clear(this.overlayEl);
    this.runButton = h('button.run-btn.primary', { type: 'button', onclick: () => this.onRunButton() });
    this.stepButton = h('button.run-btn', { type: 'button', title: t('step.tip'), onclick: () => this.run({ step: true }) }, icon('step', 14), h('span', null, t('step')));
    this.resetButton = h('button.run-btn', { type: 'button', title: t('reset.tip'), onclick: () => this.reset() }, icon('reset', 14), h('span', null, t('reset')));
    this.statusEl = h('span.run-status', { role: 'status', 'aria-live': 'polite' });
    this.captionEl = h('div.caption', { hidden: !this.caption }, this.caption);
    this.toastEl = h('div.toasts', { 'aria-live': 'polite' });
    this.overlayEl.append(
      h('div.runbar', null, this.runButton, this.stepButton, this.resetButton, this.statusEl),
      h('div.zoombar', null,
        h('button.icon-btn', { type: 'button', title: t('zoom.out'), 'aria-label': t('zoom.out'), onclick: () => { this.viewStage().effects.userMovedView(); this.viewStage().zoomBy(1 / 1.25); } }, icon('minus', 14)),
        h('button.icon-btn', { type: 'button', title: t('zoom.fit'), 'aria-label': t('zoom.fit'), onclick: () => { this.viewStage().effects.userMovedView(); this.viewStage().fit({ animate: true }); } }, icon('fit', 14)),
        h('button.icon-btn', { type: 'button', title: t('zoom.in'), 'aria-label': t('zoom.in'), onclick: () => { this.viewStage().effects.userMovedView(); this.viewStage().zoomBy(1.25); } }, icon('plus', 14))),
      this.captionEl, this.toastEl);
  }

  setCaption(text) {
    this.caption = text || '';
    if (!this.captionEl) return;
    this.captionEl.textContent = this.caption;
    this.captionEl.hidden = !this.caption;
  }

  toast(text, { id = text, duration = 2800, tone = 'info' } = {}) {
    if (!this.toastEl) return;
    let node = [...this.toastEl.children].find(n => n.dataset.id === id);
    if (!node) {
      node = h('div.toast.' + tone, { 'data-id': id }, text);
      this.toastEl.append(node);
    } else node.textContent = text;
    clearTimeout(this.toastTimers.get(id));
    this.toastTimers.set(id, setTimeout(() => { node.remove(); this.toastTimers.delete(id); }, duration));
  }

  selected() {
    return this.state.selection ? elementById(this.model, this.state.selection) : null;
  }

  select(id) {
    if (this.state.selection === id) return;
    const previous = this.state.selection;
    this.state.selection = id;
    if (previous) this.refresh(previous);
    if (id) this.refresh(id);
    this.inscription.render();
    const element = this.selected();
    this.emit({ type: 'select', element });
    this.emitInscription();
    this.revealSelection();
  }

  emitInscription() {
    const element = this.selected();
    if (!element || !this.state.inscriptionOpen) return;
    const active = this.inscriptionEl.querySelector('.inscription-tab.active');
    this.emit({ type: 'inscription', element, tab: active ? active.dataset.tab : null });
  }

  setInscriptionOpen(open) {
    this.state.inscriptionOpen = open;
    this.processEditor.classList.toggle('inscription-closed', !open);
    if (this.inscriptionToggle) {
      this.inscriptionToggle.classList.toggle('active', open);
      this.inscriptionToggle.setAttribute('aria-pressed', String(open));
    }
    if (open) {
      this.emitInscription();
      requestAnimationFrame(() => this.revealSelection());
    }
  }

  setInscriptionTab(el, tabId) {
    this.state.inscriptionTabs[el.id] = tabId;
    this.inscription.render();
    this.emitInscription();
  }

  changeElement(el, mutate, key = 'name') {
    mutate(el);
    this.refresh(el.id);
    this.inscription.validate();
    this.emit({ type: 'config', element: el, key });
  }

  setRuleConfig(el, key, value) {
    const cfg = ruleConfig(el);
    if (value === '' || value == null) delete cfg[key];
    else cfg[key] = value;
    this.inscription.validate();
    this.emit({ type: 'config', element: el, key });
  }

  originalConfig(el) {
    const original = originalOf(this.model, el.id);
    return original && original.config.userConfig ? original.config.userConfig : null;
  }

  fillFromOriginal(el) {
    const original = this.originalConfig(el);
    if (!original) return;
    el.config.userConfig = clone(original);
    this.inscription.render();
    this.emit({ type: 'config', element: el, key: 'all' });
  }

  canvasKey(key) {
    const el = this.selected();
    if (key === 'Enter' && el) { this.setInscriptionOpen(true); return true; }
    if (key === 'Escape' && el) { this.select(null); return true; }
    if ((key === 'ArrowRight' || key === 'ArrowLeft') && this.state.tab === 'process') {
      const order = [];
      for (let e = startElement(this.model); e && !order.includes(e); e = (nextOf(this.model, e) || {}).element) order.push(e);
      if (!order.length) return false;
      const index = el ? order.indexOf(el) : -1;
      const next = order[clamp(index + (key === 'ArrowRight' ? 1 : -1), 0, order.length - 1)] || order[0];
      this.select(next.id);
      return true;
    }
    return false;
  }

  renderVariables() {
    const t = this.t;
    clear(this.variablesEditor);
    this.variablesInfo = h('div.var-info');
    this.variablesCode = codeEditor({
      value: this.variablesText,
      language: 'yaml',
      label: 'variables.yaml',
      onInput: value => {
        this.variablesText = value;
        this.updateVariablesInfo();
        if (this.variablesDot) this.variablesDot.classList.toggle('changed', value !== (this.def.variables || ''));
        this.emit({ type: 'variables' });
      }
    });
    this.variablesField = field(null, this.variablesCode);
    this.variablesEditor.append(
      h('div.var-main', null,
        h('div.var-path', null, icon('yaml', 13), this.def.variablesPath || 'config/variables.yaml'),
        this.variablesField),
      h('aside.var-side', null,
        h('h4', null, t('variables.title')),
        this.variablesInfo,
        h('p.field-note', null, t('variables.hint')),
        h('button.link-btn', { type: 'button', onclick: () => this.setVariables(this.def.variables || '') }, icon('reset', 13), t('variables.restore'))));
    this.updateVariablesInfo();
  }

  renderSources() {
    const t = this.t;
    this.sources.forEach(source => {
      const code = codeEditor({
        value: this.sourceText[source.id],
        language: source.language || 'text',
        label: source.file,
        onInput: value => {
          this.sourceText[source.id] = value;
          this.inscription.render(); // the tools on offer may depend on it
          this.emit({ type: 'source', id: source.id });
        }
      });
      code.textarea.readOnly = Boolean(source.readOnly);
      this.sourceCode[source.id] = code;
      clear(this.sourceEditors[source.id]).append(h('div.var-main', null,
        h('div.var-path', null, icon(source.language === 'java' ? 'code' : 'yaml', 13), source.path || source.file,
          source.readOnly ? h('span.src-note', null, t('source.readOnly')) : null),
        field(null, code)));
    });
  }

  setSource(id, text) {
    this.sourceText[id] = text;
    if (this.sourceCode[id]) this.sourceCode[id].setValue(text);
    this.inscription.render();
    this.emit({ type: 'source', id });
  }

  setVariables(text) {
    this.variablesText = text;
    if (this.variablesCode) this.variablesCode.setValue(text);
    if (this.variablesDot) this.variablesDot.classList.toggle('changed', text !== (this.def.variables || ''));
    this.updateVariablesInfo();
    this.emit({ type: 'variables' });
  }

  updateVariablesInfo() {
    const t = this.t;
    const info = clear(this.variablesInfo);
    const yaml = parseYaml(this.variablesText);
    const row = (label, value, hint, state) => h('div.var-row' + (state ? '.' + state : ''), null,
      h('span.var-label', null, label), h('span.var-value', null, value), hint ? h('span.var-hint', null, hint) : null);
    if (yaml.errors.length) {
      const first = yaml.errors[0];
      this.variablesCode.setMarkers(yaml.errors.map(e => ({ line: e.line, message: e.message })));
      this.variablesField.setMessage('error', t('variables.parseError', { line: first.line, message: first.message }));
      info.append(h('div.var-effect.bad', null, icon('error', 14), t('variables.effect.bad')));
      return;
    }
    this.variablesCode.setMarkers([]);
    this.variablesField.setMessage('', '');
    const settings = readSettings(yaml.value);
    const rightUrl = settings.url.replace(/\/+$/, '') === this.runtime.url;
    const effect = !settings.url ? 'variables.effect.noUrl'
      : !rightUrl ? 'variables.effect.wrongUrl'
        : this.runtime.state === 'stopped' ? 'variables.effect.stopped'
          : this.runtime.state === 'slow' ? 'variables.effect.slow' : 'variables.effect.ok';
    const ok = effect === 'variables.effect.ok';
    info.append(
      row(t('variables.url'), settings.url || '—', VARIABLES.URL, rightUrl ? 'ok' : 'bad'),
      row(t('variables.timeout'), settings.timeout + ' s', VARIABLES.TIMEOUT),
      row(t('variables.autoDeploy'), t(settings.autoDeploy ? 'variables.on' : 'variables.off'), VARIABLES.AUTO_DEPLOY),
      row(t('variables.prefix'), settings.prefix || t('variables.none'), VARIABLES.PREFIX),
      row(t('variables.key'), t(settings.apiKey ? 'variables.key.set' : 'variables.key.none'), t('variables.key.hint')),
      h('div.var-effect.' + (ok ? 'ok' : 'bad'), null, icon(ok ? 'check' : 'error', 14), t(effect, { url: this.runtime.url, timeout: settings.timeout })));
  }

  setPanelTab(id, byUser = false) {
    this.state.panelTab = id;
    if (byUser) { this.state.panelPinned = true; this.state.panelOpen = true; }
    this.panel.update();
  }

  setPanelOpen(open) {
    this.state.panelOpen = open;
    this.panel.update();
  }

  setStatus(status, params = null) {
    this.state.status = status;
    this.state.statusParams = params;
    this.applyStatus();
  }

  applyStatus() {
    if (!this.runButton) return;
    const t = this.t;
    const { status, statusParams } = this.state;
    const running = status === 'running';
    const paused = status === 'paused' || status === 'waiting' || status === 'waiting-user';
    clear(this.runButton).append(icon(running ? 'stop' : 'play', 14), h('span', null, t(running ? 'stop' : paused ? 'continue' : 'run')));
    this.runButton.title = running ? t('stop') : t('run.tip');
    this.runButton.classList.toggle('stop', running);
    this.stepButton.disabled = running;
    this.statusEl.textContent = status === 'idle' ? '' : t('status.' + status, statusParams || {});
    this.statusEl.className = 'run-status ' + status;
  }

  onRunButton() {
    if (this.state.status === 'running') this.stop();
    else this.run();
  }

  async run({ step = false, start = null } = {}) {
    if (this.runner.running) {
      if (!this.resume) return;
      this.runner.setStepMode(step);
      const resume = this.resume;
      this.resume = null;
      this.setStatus('running');
      resume();
      return;
    }
    if (this.state.tab !== 'process') this.setTab('process');
    const generation = ++this.runGeneration;
    Object.assign(this.visual, { active: null, error: null });
    this.cancelEffects();
    this.data = {};
    this.panel.setData(this.data);
    this.state.panelPinned = false;
    this.setCaption('');
    this.refresh('all');
    this.setStatus('running');
    let result;
    try {
      const selected = this.selected();
      const from = start || (selected && selected.type === 'RequestStart' ? selected.id : null);
      // a callable sub process gets its parameters from its caller; the lesson says what the caller passes
      result = await this.runner.run({ variablesText: this.variablesText, stepMode: step, breakpoints: this.visual.breakpoints, start: from, params: this.def.params });
    } catch (e) {
      console.error('[reInteractive] The simulated run stopped unexpectedly.', e);
      result = { status: 'failed', error: { kind: 'internal', message: String((e && e.message) || e) }, ruleCalls: [], logs: [], visited: [] };
    }
    if (generation !== this.runGeneration) return;
    this.resume = null;
    const failedElement = result.failedAt ? elementById(this.model, result.failedAt) : null;
    if (result.status === 'failed') {
      this.visual.error = result.failedAt || null;
      if (failedElement) this.refresh(failedElement.id);
      if (result.error && result.error.kind === 'script') this.setCaption(this.t('note.script.failed', { message: result.error.message }));
      this.setStatus('failed', { name: failedElement ? failedElement.name || failedElement.typeLabel : '' });
    } else this.setStatus(result.status);
    if (!this.state.panelPinned && result.status !== 'cancelled') this.setPanelTab('log');
    this.refreshRuntimeViews();
    this.emit({ type: 'run', result });
    return result;
  }

  stop() {
    if (!this.runner.running) return;
    this.runner.cancel();
    const resume = this.resume;
    this.resume = null;
    this.cancelEffects();
    if (resume) resume();
  }

  cancelEffects() {
    Object.keys(this.stages).forEach(doc => this.stages[doc].effects.cancelAll());
  }

  reset() {
    this.runGeneration++;
    this.stop();
    this.cancelEffects();
    restore(this.model, this.model.original);
    this.variablesText = this.def.variables || '';
    this.sources.forEach(source => { this.sourceText[source.id] = source.text; });
    if (this.ownerDialog) this.ownerDialog.close();
    this.dmnFiles = this.dmnSources.map(dmn => ({ id: dmn.model, original: dmn, current: cloneDmn(dmn), generated: false }));
    this.lastHits = {};
    this.runtime = new DecisionRuntime();
    this.runner.forget();
    if (this.state.tab.startsWith('dmn:') && !this.findDmn(this.state.tab.slice(4))) this.setTab('process');
    this.visual = freshVisual();
    this.data = null;
    this.panel.clearLog();
    this.panel.setCall(null, null);
    this.arch.setFocus(null);
    this.arch.setCall(null);
    this.panel.setData(null);
    this.state.panelPinned = false;
    this.setPanelTab('log');
    this.setCaption('');
    this.setStatus('idle');
    this.refresh('all');
    Object.keys(this.stages).forEach(doc => {
      if (this.stages[doc] === this.viewStage()) this.stages[doc].fit({ animate: true });
      else this.stages[doc].needsFit = true;
    });
    this.renderTabs();
    this.renderVariables();
    this.renderSources();
    this.renderDmn();
    this.panel.renderRuntime();
    this.inscription.render();
    if (this.portal) this.portal.reset();
    this.emit({ type: 'reset' });
  }

  createIo() {
    // Animation happens on the canvas of the element's file, and only while that file is the
    // open tab: a tool that runs behind the process tab runs at once.
    const shown = el => (this.state.tab === el.doc ? this.stages[el.doc].effects : null);
    const named = params => {
      const el = params && params.element;
      const target = params && params.target;
      return {
        name: el ? el.name || el.typeLabel : '',
        dataClass: this.model.dataClass.name,
        condition: (params && params.condition) || '',
        target: target ? target.name || target.typeLabel : '',
        user: (params && params.user) || '',
        code: (params && params.code) || ''
      };
    };
    return {
      travel: async connector => {
        await this.hook('after:' + connector.from.id, connector.from);
        const fx = shown(connector.from);
        if (fx) await fx.travel(connectorPath(connector));
        this.visual.connectors[connector.id] = (this.visual.connectors[connector.id] || 0) + 1;
        this.refresh('connectors');
      },
      enter: async el => {
        const previous = this.visual.active;
        this.visual.active = el.id;
        if (previous) this.refresh(previous);
        this.refresh(el.id);
        const fx = shown(el);
        if (fx) {
          fx.pulse(el, 'assistant');
          await fx.wait(el.kind === 'start' ? 320 : 220);
        }
        await this.hook('enter:' + el.id, el);
      },
      leave: (el, outcome) => {
        if (outcome === 'done') this.visual.executed[el.id] = (this.visual.executed[el.id] || 0) + 1;
        else this.visual.error = el.id;
        this.refresh(el.id);
      },
      pause: async (el, reason, info = {}) => {
        const waiting = new Promise(resolve => { this.resume = resolve; });
        const status = reason !== 'task' ? 'paused' : info.user ? 'waiting-user' : 'waiting';
        this.setStatus(status, { name: info.name || el.name || el.typeLabel, user: info.user || '' });
        if (this.data) this.panel.setData(this.data);
        if (this.runHooks) {
          // the walkthrough explains the stop, then carries on by itself
          await this.hook('pause:' + el.id, { element: el, reason, ...info });
          if (this.resume) {
            const go = this.resume;
            this.resume = null;
            this.setStatus('running');
            go();
          }
        }
        return waiting;
      },
      note: (key, params) => this.setCaption(this.t('note.' + key, named(params))),
      log: entry => {
        this.panel.addLog(entry);
        if (entry.category === 'USER' && !this.state.panelPinned) this.setPanelTab('log');
      },
      data: (name, value) => {
        if (!this.data) return;
        this.data[name] = value;
        this.panel.setData(this.data, name);
      },
      rule: (phase, payload) => this.rulePhase(phase, payload)
    };
  }

  async rulePhase(phase, { call, element, ...p }) {
    const fx = this.stage.effects;
    const t = this.t;
    const showRuntime = () => { if (!this.state.panelPinned) this.setPanelTab('runtime'); };
    const json = value => JSON.stringify(value);
    this.arch.setCall(call);
    switch (phase) {
      case 'created':
        this.setCaption(t('note.rule.created', { file: p.dmn.file }));
        await fx.chip(element, '✨ ' + p.dmn.file, 'tool');
        break;
      case 'request': {
        showRuntime();
        this.panel.setCall(call, 'request');
        this.setCaption(t('note.rule.request', { model: call.runtimeModel }));
        await fx.remoteOpen(element, { title: t('remote.title'), subtitle: call.runtimeModel + (call.decision ? ' › ' + call.decision : '') });
        const packets = (call.mappings || []).map(m => ({ tone: 'user', tag: 'input', text: m.name + ' = ' + json(m.value) }));
        if (!packets.length) packets.push({ tone: 'user', tag: 'input', text: t('remote.noInputs') });
        await fx.remoteSend(packets);
        break;
      }
      case 'registered': {
        this.panel.setCall(call, 'registered');
        this.refreshRuntimeViews();
        this.setCaption(t(p.status === 'ACTIVE' ? 'note.rule.registered' : 'note.rule.registered.draft', { version: p.version, model: call.runtimeModel }));
        const file = this.findDmn(call.model);
        await fx.remoteSend([{ tone: 'tool', tag: 'dmn', text: t('remote.register', { file: file ? file.file.split('/').pop() : call.model, version: p.version }) }]);
        break;
      }
      case 'thinking':
        this.panel.setCall(call, 'thinking');
        this.setCaption(t(p.failing ? 'note.rule.noAnswer' : 'note.rule.thinking'));
        await fx.remoteThink(p.failing ? 800 : 1000, { failing: Boolean(p.failing) });
        break;
      case 'response': {
        this.panel.setCall(call, 'response');
        this.showHits(call);
        this.setCaption(call.rule ? t('note.rule.response', { n: call.rule.index + 1, version: call.version })
          : (call.rules || []).length ? t('note.rule.response.model', { version: call.version, count: Object.keys(call.results).length })
            : t('note.rule.response.none', { version: call.version }));
        const names = Object.keys(call.results);
        await fx.remoteReceive({ tone: 'assistant', tag: 'v' + call.version, text: names.map(name => name + ' = ' + brief(call.results[name])).join(', ') });
        break;
      }
      case 'mapped':
        this.panel.setCall(call, 'done');
        this.setCaption(t('note.rule.mapped', { target: call.targets.map(target => target.expression).join(', ') }));
        await fx.chip(element, '→ ' + call.targets[0].expression + (call.targets.length > 1 ? ' +' + (call.targets.length - 1) : ''), 'assistant');
        break;
      case 'unmapped':
        this.panel.setCall(call, 'done');
        this.setCaption(t('note.rule.unmapped'));
        await fx.chip(element, t('chip.unmapped'), 'neutral');
        break;
      case 'failed':
        showRuntime();
        this.panel.setCall(call, 'failed');
        this.setCaption(t('note.rule.failed', { code: p.errorCode, message: p.message || '' }));
        if (fx.remote) { await fx.wait(900); await fx.remoteClose(); }
        await fx.chip(element, p.errorCode, 'error');
        break;
      case 'end':
        await fx.remoteClose();
        break;
      default:
    }
    await this.hook('rule:' + phase, { call, element, ...p });
  }

  findDmn(model) {
    const file = this.dmnFiles.find(candidate => candidate.id === model);
    return file ? file.current : null;
  }

  addDmn(dmn) {
    this.dmnFiles.push({ id: dmn.model, original: null, current: dmn, generated: true });
    this.renderDmn();
    this.renderTabs();
    this.inscription.render();
    this.emit({ type: 'dmn', model: dmn.model, generated: true });
  }

  dmnChanged(file) {
    return file.generated || fingerprint(file.current) !== fingerprint(file.original);
  }

  restoreDmn(file) {
    if (!file.original) return;
    file.current = cloneDmn(file.original);
    delete this.lastHits[file.id];
    this.renderDmn();
    this.renderTabs();
    this.inscription.validate();
    this.emit({ type: 'dmn', model: file.id });
  }

  dmnEdited(file, kind) {
    delete this.lastHits[file.id]; // the marked rule belongs to the table as it was
    if (kind === 'rules') this.renderDmn();
    else {
      const editor = this.dmnEditors[file.id];
      if (editor) editor.querySelectorAll('tr.hit').forEach(row => { row.classList.remove('hit'); const mark = row.querySelector('.dmn-hit'); if (mark) mark.remove(); });
      this.updateDmnInfo(file);
      if (file.tryEvaluate) file.tryEvaluate();
    }
    this.renderTabs();
    this.inscription.validate();
    this.emit({ type: 'dmn', model: file.id });
  }

  renderDmn() {
    const t = this.t;
    Object.keys(this.dmnEditors).forEach(model => this.dmnEditors[model].remove());
    this.dmnEditors = {};
    this.dmnControls = {};
    this.dmnFiles.forEach(file => {
      const dmn = file.current;
      const hits = this.lastHits[file.id] || {};
      const controls = {};
      const tables = logicsOf(dmn).map(decision => decisionTable({
        decision, hits: hits[decision.name] || [], t, controls, onChange: kind => this.dmnEdited(file, kind)
      }));
      const trial = this.tryStrip(file, controls);
      Object.keys(controls).forEach(key => { this.dmnControls[file.id + ':' + key] = controls[key]; });
      file.info = h('div.var-info', { 'data-tour': 'side' });
      const editor = h('div.editor.editor-dmn', { hidden: this.state.tab !== 'dmn:' + file.id, 'data-model': file.id },
        h('div.var-main.dmn-main', null,
          h('div.var-path', null, icon('list', 13), dmn.file, file.generated ? h('span.src-note', null, t('dmn.generated')) : null),
          h('div.dmn-inputs', { 'data-tour': 'inputs' }, h('span.dmn-kind', null, t('dmn.inputs')),
            dmn.inputs.map(input => h('span.chip.plain', null, input.name, input.type ? h('span.dmn-col-type', null, ' ' + input.type) : null))),
          tables),
        h('aside.var-side', null,
          trial ? trial.node : null,
          h('h4', null, t('dmn.side.title')),
          file.info,
          h('p.field-note', null, t('dmn.side.hint')),
          file.generated ? null : h('button.link-btn', { type: 'button', onclick: () => this.restoreDmn(file) }, icon('reset', 13), t('dmn.restore'))));
      this.dmnEditors[file.id] = editor;
      this.workbench.insertBefore(editor, this.variablesEditor);
      this.updateDmnInfo(file);
      file.tryEvaluate = trial ? trial.evaluate : null;
      if (trial) trial.evaluate();
    });
  }

  tryStrip(file, controls) {
    const t = this.t;
    const named = this.def.tryTable ? logicOf(file.current, this.def.tryTable) : null;
    const decision = named && named.kind === 'table' ? named
      : logicsOf(file.current).find(candidate => candidate.kind === 'table' && candidate.table);
    if (!decision) return null;
    const table = decision.table;
    const values = file.tryValues || (file.tryValues = {});
    const result = h('div.try-result', { 'data-tour': 'tryResult', role: 'status' });
    const typed = column => {
      const text = String(values[column.expression] == null ? '' : values[column.expression]).trim();
      if (!text) return null;
      if (column.type === 'number' && /^-?(\d+\.?\d*|\.\d+)$/.test(text)) return Number(text);
      if (column.type === 'boolean' && (text === 'true' || text === 'false')) return text === 'true';
      return text.replace(/^"(.*)"$/, '$1');
    };
    const markHits = matched => {
      const editor = this.dmnEditors[file.id];
      const tried = editor ? editor.querySelector('[data-decision="' + decision.name + '"]') : null;
      if (!tried) return;
      tried.querySelectorAll('tr[data-tour^="rule"]').forEach(row => {
        const hit = matched.includes(Number(row.getAttribute('data-tour').slice(4)) - 1);
        row.classList.toggle('hit', hit);
        const num = row.querySelector('td.num');
        const mark = num ? num.querySelector('.dmn-hit') : null;
        if (hit && !mark && num) num.prepend(h('span.dmn-hit', { title: t('dmn.hit') }, icon('check', 11)));
        if (!hit && mark) mark.remove();
      });
    };
    const evaluate = () => {
      clear(result);
      if (!table.inputs.some(column => typed(column) !== null)) {
        result.className = 'try-result';
        result.append(t('dmn.try.empty'));
        return;
      }
      const context = {};
      table.inputs.forEach(column => {
        const path = String(column.expression).split('.');
        let node = context;
        path.slice(0, -1).forEach(part => { node = node[part] || (node[part] = {}); });
        node[path[path.length - 1]] = typed(column);
      });
      let answer;
      try {
        answer = evaluateTable(table, context);
      } catch (e) {
        if (!(e instanceof FeelError)) throw e;
        result.className = 'try-result bad';
        result.append(t('dmn.try.invalid', { message: e.message }));
        markHits([]);
        return;
      }
      const value = answer.result === null || answer.result === undefined ? 'null' : JSON.stringify(answer.result);
      result.className = 'try-result' + (answer.matched.length ? ' ok' : ' bad');
      result.append(answer.matched.length
        ? t('dmn.try.result', { decision: decision.name, value, rule: answer.matched.map(index => index + 1).join(', ') })
        : t('dmn.try.none', { decision: decision.name }));
      (this.lastHits[file.id] || (this.lastHits[file.id] = {}))[decision.name] = answer.matched;
      markHits(answer.matched);
    };
    const fields = table.inputs.map(column => {
      const box = textBox({
        value: values[column.expression] == null ? '' : String(values[column.expression]),
        mono: true,
        placeholder: column.type || '',
        onInput: value => { values[column.expression] = value; evaluate(); }
      });
      const setValue = box.setValue;
      box.setValue = value => { setValue(value); values[column.expression] = value; };
      box.setAttribute('aria-label', t('dmn.try.title') + ': ' + (column.label || column.expression));
      controls['try.' + column.expression] = box;
      return h('label.try-field', { 'data-tour': column.expression },
        h('span.try-label', null, column.label || column.expression), box);
    });
    const node = h('div.try', { 'data-tour': 'try' },
      h('h4', null, t('dmn.try.title')),
      h('p.field-note', null, t('dmn.try.hint', { decision: decision.name })),
      fields,
      result);
    return { node, evaluate };
  }

  updateDmnInfo(file) {
    if (!file.info) return;
    const t = this.t;
    const info = clear(file.info);
    const settings = readSettings(parseYaml(this.variablesText).value || {});
    const model = settings.prefix + file.id;
    const active = this.runtime.active(model);
    const stored = this.runtime.versions(model).find(version => version.hash === fingerprint(file.current));
    const row = (label, value, hint, state) => h('div.var-row' + (state ? '.' + state : ''), null,
      h('span.var-label', null, label), h('span.var-value', null, value), hint ? h('span.var-hint', null, hint) : null);
    info.append(row(t('dmn.side.model'), model, settings.prefix ? VARIABLES.PREFIX : ''));
    info.append(row(t('dmn.side.active'), active ? t('dmn.side.version', { version: active.version, source: active.source.toLowerCase() }) : t('dmn.side.none'), '', active ? 'ok' : 'warn'));
    let effect;
    let tone = 'ok';
    if (!settings.autoDeploy) { effect = 'dmn.effect.off'; tone = 'bad'; }
    else if (stored && stored.status === 'ACTIVE') effect = 'dmn.effect.live';
    // this engine registered exactly this content before: it is not sent again, whatever is active now
    else if (stored && active && this.runner.registered.get(model) === JSON.stringify(file.current)) effect = 'dmn.effect.registered';
    else if (active && active.source !== 'PROJECT') { effect = 'dmn.effect.draft'; tone = 'bad'; }
    else if (stored) effect = 'dmn.effect.reactivate';
    else effect = active ? 'dmn.effect.next' : 'dmn.effect.first';
    info.append(h('div.var-effect.' + tone, null, icon(tone === 'ok' ? 'check' : 'warning', 14),
      t(effect, { version: stored ? stored.version : '', active: active ? active.version : '' })));
  }

  showHits(call) {
    this.lastHits[call.model] = call.matched || {};
    this.renderDmn();
  }

  refreshRuntimeViews() {
    this.arch.update();
    if (this.portal) this.portal.update();
    this.panel.renderRuntime();
    this.dmnFiles.forEach(file => this.updateDmnInfo(file));
    if (this.variablesInfo && this.variablesCode) this.updateVariablesInfo();
  }

  setRuntimeState(state) {
    this.runtime.state = state;
    this.refreshRuntimeViews();
    this.emit({ type: 'runtime', state });
  }

  onRuntime(change) {
    try {
      change();
    } catch (e) {
      if (!(e instanceof RuntimeError)) throw e;
      this.toast(e.message, { tone: 'error', duration: 5000 });
    }
    this.refreshRuntimeViews();
    this.emit({ type: 'runtime' });
  }

  activateVersion(model, version) {
    this.onRuntime(() => this.runtime.activate(model, version));
  }

  rollbackModel(model) {
    this.onRuntime(() => this.runtime.rollback(model));
  }

  editAsRuleOwner(model) {
    const t = this.t;
    const active = this.runtime.active(model);
    if (!active || this.ownerDialog) return this.ownerDialog;
    const dmn = cloneDmn(active.dmn);
    const controls = {};
    const body = h('div.owner-body');
    const draw = () => {
      Object.keys(controls).forEach(key => delete controls[key]);
      clear(body).append(...logicsOf(dmn).map(decision => decisionTable({ decision, t, controls, onChange: kind => { if (kind === 'rules') draw(); } })));
    };
    const box = h('div.owner-dialog', { role: 'dialog', 'aria-label': t('owner.title', { model }) });
    const close = () => {
      if (this.ownerDialog !== handle) return;
      this.ownerDialog = null;
      box.remove();
    };
    const activate = () => {
      this.onRuntime(() => this.runtime.register(model, dmn, { source: 'PORTAL', author: 'rule.owner', activate: true }));
      close();
    };
    const handle = { model, dmn, controls, box, activate, close };
    box.append(
      h('div.owner-head', null, icon('settings', 14), h('strong', null, t('owner.title', { model })), h('span.owner-version', null, t('owner.from', { version: active.version }))),
      h('p.field-note', null, t('owner.text')),
      body,
      h('div.owner-actions', null,
        h('button.run-btn', { type: 'button', onclick: close }, t('owner.cancel')),
        h('button.run-btn.primary', { type: 'button', 'data-tour': 'ownerActivate', onclick: activate }, icon('check', 13), h('span', null, t('owner.activate')))));
    draw();
    this.layer.append(box);
    this.ownerDialog = handle;
    return handle;
  }

  setLanguage(lang) {
    const next = normalizeLanguage(lang);
    if (next === this.lang) return;
    this.lang = next;
    this.t = createTranslator(next, this.def.strings);
    this.setCaption('');
    this.renderChrome();
    this.refresh('all');
  }

  dispose() {
    this.disposed = true;
    this.runGeneration++;
    this.stop();
    this.toastTimers.forEach(timer => clearTimeout(timer));
    this.detachControllers.forEach(detach => detach());
    if (this.resizeObserver) this.resizeObserver.disconnect();
    if (this.ownerDialog) this.ownerDialog.close();
    this.arch.dispose();
    this.tour.dispose();
    Object.keys(this.stages).forEach(doc => this.stages[doc].dispose());
    clear(this.root);
  }
}
