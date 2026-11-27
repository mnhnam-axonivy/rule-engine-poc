/**
 * The panel below the editor: the Axon Ivy Runtime Log, the decision runtime (the last call of
 * a Business Rule Task and the versions the runtime holds), and the process data — the three
 * places where a run becomes visible.
 */
import { h, clear } from '../util.js';
import { icon } from './icons.js';

const pad = (n, width = 2) => String(n).padStart(width, '0');
const shortType = type => String(type || 'String').split('.').pop();

export function formatTime(date) {
  return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()) + ' ' +
    pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds()) + '.' + pad(date.getMilliseconds(), 3);
}

/** Message text with URLs marked up, as the log viewer does. */
function logMessage(text) {
  const parts = [];
  let last = 0;
  const pattern = /https?:\/\/[^\s)]+/g;
  let m;
  while ((m = pattern.exec(text))) {
    if (m.index > last) parts.push(text.slice(last, m.index));
    parts.push(h('span.log-url', null, m[0]));
    last = m.index + m[0].length;
  }
  if (last < text.length) parts.push(text.slice(last));
  return parts;
}

export class BottomPanel {
  constructor(app, root) {
    this.app = app;
    this.root = root;
    this.logs = [];
    this.call = null;
    this.phase = null;
    this.data = null;
    this.changed = null;
    this.render();
  }

  render() {
    const { app } = this;
    const t = app.t;
    clear(this.root);
    const tabs = [['log', 'list', t('panel.log')], ['runtime', 'database', t('panel.runtime')], ['data', 'braces', t('panel.data')]];
    this.tabButtons = {};
    this.counter = h('span.panel-count');
    const head = h('div.panel-head', { role: 'tablist' },
      tabs.map(([id, iconName, label]) => {
        const button = h('button.panel-tab', { type: 'button', role: 'tab', 'data-tab': id, onclick: () => app.setPanelTab(id, true) },
          icon(iconName, 13), h('span', null, label), id === 'log' ? this.counter : null);
        this.tabButtons[id] = button;
        return button;
      }),
      h('span.spacer'),
      h('button.icon-btn', { type: 'button', title: t('panel.clear'), 'aria-label': t('panel.clear'), onclick: () => this.clearLog() }, icon('trash', 14)),
      h('button.icon-btn.panel-toggle', { type: 'button', title: t('panel.toggle'), 'aria-label': t('panel.toggle'), onclick: () => app.setPanelOpen(!app.state.panelOpen) }, icon('chevronDown', 14)));
    this.logView = h('div.panel-view.log', { role: 'log', tabIndex: 0 });
    this.runtimeView = h('div.panel-view.runtime', { tabIndex: 0 });
    this.dataView = h('div.panel-view.data', { tabIndex: 0 });
    this.root.append(head, h('div.panel-body', null, this.logView, this.runtimeView, this.dataView));
    this.renderLog();
    this.renderRuntime();
    this.renderData();
    this.update();
  }

  /** Reflects the app state (active tab, open/closed). */
  update() {
    const { panelTab, panelOpen } = this.app.state;
    Object.keys(this.tabButtons).forEach(id => {
      this.tabButtons[id].classList.toggle('active', id === panelTab);
      this.tabButtons[id].setAttribute('aria-selected', String(id === panelTab));
    });
    this.logView.hidden = panelTab !== 'log';
    this.runtimeView.hidden = panelTab !== 'runtime';
    this.dataView.hidden = panelTab !== 'data';
    this.root.classList.toggle('collapsed', !panelOpen);
    if (panelTab === 'log') this.logView.scrollTop = this.logView.scrollHeight;
  }

  // ── Runtime log ────────────────────────────────────────────────────────────

  logLine(entry) {
    const line = h('div.log-line', null,
      h('span.log-time', null, formatTime(entry.time)), ' ',
      h('span.log-level.' + entry.level, null, '[' + entry.level + ']'), ' ',
      '[' + entry.project + '] ', entry.category ? '[' + entry.category + '] ' : '',
      h('span.log-ctx', null, '[' + entry.context + ']'), ' ',
      logMessage(entry.message));
    return entry.detail ? [line, h('div.log-detail', null, entry.detail)] : [line];
  }

  renderLog() {
    clear(this.logView);
    if (!this.logs.length) this.logView.append(h('div.panel-empty', null, this.app.t('log.empty')));
    else this.logs.forEach(entry => this.logView.append(...this.logLine(entry)));
    this.counter.textContent = this.logs.length ? String(this.logs.length) : '';
    this.counter.hidden = !this.logs.length;
  }

  addLog(entry) {
    if (!this.logs.length) clear(this.logView);
    this.logs.push(entry);
    this.logView.append(...this.logLine(entry));
    this.logView.scrollTop = this.logView.scrollHeight;
    this.counter.textContent = String(this.logs.length);
    this.counter.hidden = false;
  }

  clearLog() {
    this.logs = [];
    this.renderLog();
  }

  // ── Decision runtime ───────────────────────────────────────────────────────

  /** Shows the given call of a Business Rule Task in its current phase ('request', 'thinking', 'response', …). */
  setCall(call, phase) {
    this.call = call;
    this.phase = phase;
    this.renderRuntime();
  }

  renderRuntime() {
    const { app } = this;
    const t = app.t;
    const runtime = app.runtime;
    const view = clear(this.runtimeView);

    // the container: where it runs, and whether it answers
    const states = ['running', 'slow', 'stopped'];
    view.append(h('div.rt-state', { 'data-tour': 'state' },
      h('span.rt-title', null, icon('database', 14), h('strong', null, t('runtime.title')), h('code', null, runtime.url)),
      h('span.rt-switch', { role: 'group', 'aria-label': t('runtime.state') },
        states.map(state => h('button.rt-switch-btn.' + state + (runtime.state === state ? '.active' : ''), {
          type: 'button', 'aria-pressed': String(runtime.state === state), title: t('runtime.state.' + state + '.tip'),
          onclick: () => app.setRuntimeState(state)
        }, t('runtime.state.' + state)))),
      h('span.rt-hint', null, t('runtime.state.' + runtime.state + '.tip'))));

    view.append(this.callView());
    view.append(this.versionsView());
  }

  /** The last call of a Business Rule Task: what went to the runtime and what came back. */
  callView() {
    const { app } = this;
    const t = app.t;
    const call = this.call;
    if (!call) return h('div.panel-empty', { 'data-tour': 'call' }, t('runtime.empty'));
    const json = value => (value === undefined ? '' : JSON.stringify(value));
    const box = h('div.rt-call', { 'data-tour': 'call' });
    if (call.url) box.append(h('div.rt-url', null, h('span.rt-method', null, 'POST'), h('code', null, call.url)));

    if (call.mappings && call.mappings.length) {
      box.append(h('div.msg.user', null,
        h('div.msg-role', null, t('runtime.request'), call.decision ? h('span.msg-tag', null, t('runtime.decision', { decision: call.decision })) : h('span.msg-tag', null, t('runtime.allDecisions'))),
        h('table.rt-map', null, h('tbody', null, call.mappings.map(mapping => h('tr', null,
          h('td', null, h('code', null, mapping.name)),
          h('td.rt-value', null, json(mapping.value)),
          h('td.rt-from', { title: t('runtime.from.name') }, '← ' + mapping.expression)))))));
    }
    if (call.registered && (call.registered.created || call.registered.status !== 'ACTIVE')) {
      const active = call.registered.status === 'ACTIVE';
      box.append(h('div.chat-note.' + (active ? 'info' : 'warning'), null, icon(active ? 'info' : 'warning', 14),
        h('span', null, t(active ? 'runtime.registered' : 'runtime.registered.draft', { version: call.registered.version, model: call.model }))));
    }

    if (call.status === 'failed') {
      box.append(h('div.chat-note.error', null, icon('error', 14),
        h('span', null, h('strong', null, call.errorCode), call.runtimeCode ? ' (' + call.runtimeCode + ') ' : ' ', call.error || '')));
    } else if (call.results === undefined) {
      if (this.phase === 'thinking' || this.phase === 'request' || this.phase === 'registered') {
        box.append(h('div.msg.assistant.pending', null, h('div.msg-role', null, t('runtime.response')), h('div.msg-text', null, t('runtime.thinking'))));
      }
    } else {
      const names = Object.keys(call.results);
      const foot = h('div.msg-foot');
      box.append(h('div.msg.assistant', null,
        h('div.msg-role', null, t('runtime.response'), h('span.msg-tag', null, t('runtime.version', { version: call.version }))),
        h('table.rt-map', null, h('tbody', null, names.map(name => h('tr', null,
          h('td', null, h('code', null, name)),
          h('td.rt-value', null, json(call.results[name])),
          h('td.rt-from', null, ''))))),
        call.rule ? h('div.rt-rule', { 'data-tour': 'matched' }, icon('check', 12),
          h('span', null, t('runtime.rule', { n: call.rule.index + 1 }),
            h('code', null, call.rule.inputs.join('  |  ') + '  →  ' + call.rule.outputs.join(', ')),
            call.rule.annotation ? h('span.rt-note', null, call.rule.annotation) : null)) : null,
        // a whole model: the rule each table chose, a function once per call (one per line of an invoice)
        !call.rule && (call.rules || []).length ? h('div.rt-rules', { 'data-tour': 'matched' },
          h('div.msg-role', null, t('runtime.rules')),
          h('table.rt-map', null, h('tbody', null, call.rules.slice(0, 16).map(rule => h('tr', null,
            h('td', null, h('code', null, rule.table)),
            h('td.rt-value', null, (rule.call ? t('runtime.rules.call', { n: rule.call }) + ' · ' : '') + t('runtime.rules.rule', { n: rule.index + 1 })),
            h('td.rt-from', null, rule.annotation || rule.outputs.join(', '))))))) : null,
        foot));
      (call.targets || []).forEach(target => foot.append(h('span.ok', null, icon('check', 12),
        t('runtime.mapped', { target: target.expression, value: typeof target.value === 'object' && target.value !== null ? '{…}' : json(target.value) }))));
      if (this.phase === 'done' && !(call.targets || []).length) foot.append(h('span.warn', null, icon('warning', 12), t('runtime.unmapped')));
    }
    return box;
  }

  /** The versions the runtime holds for every model, with what a rule owner can do to them. */
  versionsView() {
    const { app } = this;
    const t = app.t;
    const runtime = app.runtime;
    const names = runtime.modelNames();
    const box = h('div.rt-versions', { 'data-tour': 'versions' }, h('div.msg-role', null, t('runtime.versions')));
    if (!names.length) {
      box.append(h('div.rt-none', null, t('runtime.versions.empty')));
      return box;
    }
    names.forEach(name => {
      const versions = runtime.versions(name).slice().reverse();
      const active = runtime.active(name);
      const stopped = runtime.state === 'stopped';
      box.append(h('div.rt-model', { 'data-model': name },
        h('div.rt-model-head', null, h('code', null, name),
          active && active.previousVersion !== null
            ? h('button.link-btn', { type: 'button', disabled: stopped, 'data-tour': 'rollback', title: t('runtime.rollback.tip', { version: active.previousVersion }), onclick: () => app.rollbackModel(name) },
              icon('undo', 13), t('runtime.rollback'))
            : null,
          active
            ? h('button.link-btn', { type: 'button', disabled: stopped, 'data-tour': 'owner', title: t('runtime.owner.tip'), onclick: () => app.editAsRuleOwner(name) },
              icon('settings', 13), t('runtime.owner'))
            : null),
        h('table.rt-table', null,
          h('thead', null, h('tr', null, h('th', null, t('runtime.col.version')), h('th', null, t('runtime.col.status')), h('th', null, t('runtime.col.source')), h('th', null, t('runtime.col.author')), h('th'))),
          h('tbody', null, versions.map(version => h('tr' + (version.status === 'ACTIVE' ? '.active' : ''), { 'data-tour': 'v' + version.version },
            h('td', null, String(version.version)),
            h('td', null, h('span.rt-status.' + version.status.toLowerCase(), null, version.status.toLowerCase())),
            h('td', null, version.source.toLowerCase()),
            h('td', null, version.author || ''),
            h('td', null, version.status === 'ACTIVE' ? null
              : h('button.link-btn', { type: 'button', disabled: stopped, title: t('runtime.activate.tip'), onclick: () => app.activateVersion(name, version.version) }, t('runtime.activate')))))))));
    });
    return box;
  }

  // ── Process data ───────────────────────────────────────────────────────────

  setData(data, changedField = null) {
    this.data = data;
    this.changed = changedField;
    this.renderData();
  }

  /**
   * A value of the process data: text, the fields of a data class object, a list one element per line.
   * Objects inside objects and the elements of a list are shown in one line each.
   */
  dataValue(value, depth = 0) {
    if (Array.isArray(value)) {
      if (!value.length) return h('span.muted', null, '[ ]');
      return h('ol.data-list', null, value.map(element => h('li', null, this.dataLine(element))));
    }
    if (!(value && typeof value === 'object')) return h('pre.data-value', null, String(value));
    if (depth > 0) return this.dataLine(value);
    const cls = value.__type ? this.app.model.classes[value.__type] : null;
    const fields = cls ? cls.fields : Object.keys(value).filter(k => !k.startsWith('__')).map(name => ({ name, type: '' }));
    return h('table.data-object', null, h('tbody', null, fields.map(f => {
      const v = value[f.name];
      return h('tr', null,
        h('td', null, h('code', null, f.name)),
        h('td', { title: f.type }, f.type ? shortType(f.type) : ''),
        h('td', null, v === null || v === undefined ? h('span.muted', null, 'null') : this.dataValue(v, depth + 1)));
    })));
  }

  /** An object or a list in one line: its simple values joined, e.g. Laptop 14" · 4 · 1200. */
  dataLine(value) {
    const text = v => (v === null || v === undefined ? 'null' : Array.isArray(v) ? '[' + v.length + ']'
      : typeof v === 'object' ? '{…}' : String(v));
    if (!(value && typeof value === 'object')) return h('span', null, text(value));
    const parts = Object.keys(value).filter(key => !key.startsWith('__')).map(key => key + ' ' + text(value[key]));
    return h('span.data-line', { title: parts.join('\n') }, parts.join(' · '));
  }

  renderData() {
    const { app } = this;
    const t = app.t;
    clear(this.dataView);
    if (!this.data) { this.dataView.append(h('div.panel-empty', null, t('data.empty'))); return; }
    const dataClass = app.model.dataClass;
    this.dataView.append(
      h('div.data-class', null, icon('braces', 13), h('code', null, 'in'), h('span', null, dataClass.name)),
      h('table.data-table', null,
        h('thead', null, h('tr', null, h('th', null, t('data.field')), h('th', null, t('data.type')), h('th', null, t('data.value')))),
        h('tbody', null, dataClass.fields.map(f => {
          const value = this.data[f.name];
          const empty = value === undefined || value === null || value === '';
          return h('tr' + (this.changed === f.name ? '.changed' : ''), { 'data-field': f.name },
            h('td', null, h('code', null, f.name), f.comment ? h('div.data-comment', null, f.comment) : null),
            h('td', { title: f.type }, shortType(f.type)),
            h('td', null, empty ? h('span.muted', null, t('data.unset')) : this.dataValue(value)));
        }))));
  }
}
