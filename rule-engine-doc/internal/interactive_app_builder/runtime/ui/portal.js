import { h, clear } from '../util.js';
import { icon } from './icons.js';
import { textBox } from './fields.js';
import { decisionTable } from './dmn-table.js';
import { readSettings } from '../model/runner.js';
import { parseYaml } from '../model/yaml.js';
import { cloneDmn, addInput, logicsOf } from '../model/dmn.js';
import { validateCondition, validateResult, validateExpression } from '../model/feel.js';

export const PORTAL_USER = 'admin';
const PORTAL_USER_NAME = 'Portal Admin User';
const OTHER_TABS = ['portal.tab.settings', 'portal.tab.announcements', 'portal.tab.roles', 'portal.tab.passwords'];

export class PortalView {
  constructor(app, host) {
    this.app = app;
    this.host = host;
    this.root = null;
    this.edit = null;
    this.versionsOf = null;
    this.messages = [];
    this.controls = {};
    this.description = null;
  }

  settings() {
    const yaml = parseYaml(this.app.variablesText);
    return readSettings(yaml.errors.length ? {} : yaml.value || {});
  }

  reachable() {
    const settings = this.settings();
    const runtime = this.app.runtime;
    return Boolean(settings.url) && settings.url.replace(/\/+$/, '') === runtime.url && runtime.state !== 'stopped';
  }

  models() {
    const prefix = this.settings().prefix;
    const runtime = this.app.runtime;
    const rows = new Map();
    runtime.modelNames().filter(name => name.startsWith(prefix)).forEach(runtimeName => {
      const name = runtimeName.slice(prefix.length);
      const active = runtime.active(runtimeName);
      const versions = runtime.versions(runtimeName);
      const latest = versions[versions.length - 1];
      const dmn = (active || latest).dmn;
      rows.set(name, { name, onRuntime: true, active: active ? active.version : null, count: versions.length, decisions: dmn.decisions.map(d => d.name) });
    });
    this.app.dmnFiles.forEach(file => {
      const row = rows.get(file.id) || { name: file.id, onRuntime: false, active: null, count: 0, decisions: [] };
      row.file = file.current.file.split('/').pop();
      row.project = this.app.model.project;
      rows.set(file.id, row);
    });
    return [...rows.values()].sort((a, b) => a.name.localeCompare(b.name));
  }

  open(model, version = null) {
    const runtimeName = this.settings().prefix + model;
    const runtime = this.app.runtime;
    const versions = runtime.versions(runtimeName);
    let entry = version ? versions.find(candidate => candidate.version === version) : runtime.active(runtimeName);
    if (!entry && !version && versions.length) entry = versions[versions.length - 1];
    if (entry) {
      this.edit = { model, version: entry.version, status: entry.status, project: null, dmn: cloneDmn(entry.dmn), dirty: false };
    } else {
      const file = this.app.dmnFiles.find(candidate => candidate.id === model);
      if (!file) return false;
      this.edit = { model, version: null, status: null, project: this.app.model.project, dmn: cloneDmn(file.current), dirty: false };
    }
    this.versionsOf = null;
    this.messages = [];
    this.render();
    this.app.emit({ type: 'portal', action: 'open', model });
    return true;
  }

  back() {
    this.edit = null;
    this.messages = [];
    this.render();
    this.app.emit({ type: 'portal', action: 'back' });
  }

  addInput(name, type = 'string') {
    if (!this.edit || !name) return false;
    const named = this.app.def.portalTable;
    const decision = this.edit.dmn.decisions.find(candidate => candidate.kind === 'table' && (!named || candidate.name === named));
    if (!decision || !addInput(this.edit.dmn, decision.name, { name, type })) return false;
    this.edit.dirty = true;
    this.renderEditor();
    this.app.emit({ type: 'portal', action: 'input', name });
    return true;
  }

  findings() {
    const out = [];
    logicsOf(this.edit.dmn).forEach(decision => {
      if (decision.kind === 'literal') {
        const error = validateExpression(decision.expression);
        if (error) out.push(this.app.t('portal.finding.expression', { name: decision.name, message: error.message }));
        return;
      }
      if (decision.kind !== 'table') return;
      const table = decision.table;
      table.rules.forEach((rule, index) => {
        rule.inputs.forEach((condition, column) => {
          const error = validateCondition(condition);
          if (error) out.push(this.app.t('portal.finding', { rule: index + 1, column: table.inputs[column].label, message: error.message }));
        });
        rule.outputs.forEach((result, column) => {
          const error = validateResult(result);
          if (error) out.push(this.app.t('portal.finding', { rule: index + 1, column: table.outputs[column].label || decision.name, message: error.message }));
        });
      });
    });
    return out;
  }

  check() {
    if (!this.edit) return;
    const findings = this.findings();
    this.messages = findings.length
      ? [{ tone: 'error', text: this.app.t('portal.msg.invalid', { model: this.edit.model }) }, ...findings.map(text => ({ tone: 'warn', text }))]
      : [{ tone: 'info', text: this.app.t('portal.msg.valid', { model: this.edit.model }) }];
    this.renderMessages();
    this.app.emit({ type: 'portal', action: 'check', valid: !findings.length });
  }

  save(activate) {
    if (!this.edit || !this.reachable()) return null;
    const findings = this.findings();
    if (findings.length) {
      this.messages = [{ tone: 'error', text: this.app.t('portal.msg.refused') }, ...findings.map(text => ({ tone: 'warn', text }))];
      this.renderMessages();
      return null;
    }
    const runtimeName = this.settings().prefix + this.edit.model;
    let result = null;
    this.app.onRuntime(() => {
      result = this.app.runtime.register(runtimeName, this.edit.dmn, { source: 'PORTAL', author: PORTAL_USER, activate: Boolean(activate) });
    });
    if (!result) return null;
    const { entry, created } = result;
    const what = this.description ? this.description.value.trim() : '';
    if (created && what) entry.description = what;
    this.edit.version = entry.version;
    this.edit.status = entry.status;
    this.edit.project = null;
    this.edit.dirty = false;
    const key = !created ? 'portal.msg.unchanged' : entry.status === 'ACTIVE' ? 'portal.msg.active' : 'portal.msg.draft';
    this.messages = [{ tone: 'info', text: this.app.t(key, { model: this.edit.model, version: entry.version }) }];
    this.render();
    this.app.emit({ type: 'portal', action: 'save', model: this.edit.model, version: entry.version, status: entry.status });
    return entry;
  }

  showVersions(model) {
    this.versionsOf = model;
    this.messages = [];
    this.render();
  }

  closeVersions() {
    this.versionsOf = null;
    this.render();
  }

  activateVersion(version) {
    const runtimeName = this.settings().prefix + this.versionsOf;
    this.app.onRuntime(() => this.app.runtime.activate(runtimeName, version));
    this.messages = [{ tone: 'info', text: this.app.t('portal.msg.activated', { model: this.versionsOf, version }) }];
    this.render();
  }

  rollback() {
    const runtimeName = this.settings().prefix + this.versionsOf;
    let back = null;
    this.app.onRuntime(() => { back = this.app.runtime.rollback(runtimeName); });
    if (back) this.messages = [{ tone: 'info', text: this.app.t('portal.msg.rolledBack', { model: this.versionsOf, version: back.version }) }];
    this.render();
    this.app.emit({ type: 'portal', action: 'rollback', model: this.versionsOf });
  }

  reset() {
    this.edit = null;
    this.versionsOf = null;
    this.messages = [];
    this.render();
  }

  render() {
    const t = this.app.t;
    this.controls = {};
    this.description = null;
    this.messagesEl = h('div.portal-messages', { 'data-tour': 'message', 'aria-live': 'polite' });
    this.body = h('div.portal-body');
    this.root = h('div.portal', null,
      h('div.portal-top', null,
        h('span.portal-logo', { 'aria-hidden': 'true' }, 'A'),
        h('span.portal-crumb', null, icon('chevronRight', 12), t('portal.crumb')),
        h('span.portal-spacer'),
        h('span.portal-user', null, h('span.portal-avatar', { 'aria-hidden': 'true' }, 'PA'), PORTAL_USER_NAME)),
      h('div.portal-card', null,
        h('h2.portal-title', null, t('portal.title')),
        h('div.portal-tabs', { role: 'tablist' },
          OTHER_TABS.map(key => h('span.portal-tab', null, t(key))),
          h('span.portal-tab.active', { 'data-tour': 'tab' }, t('portal.tab.rules'))),
        this.messagesEl,
        this.body));
    clear(this.host).append(this.root);
    if (this.edit) this.renderEditor();
    else this.renderList();
    this.renderMessages();
    if (this.versionsOf) this.root.append(this.renderVersions());
  }

  update() {
    if (!this.root) return;
    if (!this.edit) this.renderList();
    else this.renderTitle();
    if (this.versionsOf) {
      const old = this.root.querySelector('.portal-dialog');
      if (old) old.replaceWith(this.renderVersions());
    }
  }

  renderMessages() {
    if (!this.messagesEl) return;
    clear(this.messagesEl).append(...this.messages.map(message =>
      h('div.portal-msg.' + message.tone, null, icon(message.tone === 'info' ? 'info' : message.tone === 'error' ? 'error' : 'warning', 14), h('span', null, message.text))));
  }

  renderList() {
    const t = this.app.t;
    const settings = this.settings();
    const reachable = this.reachable();
    const rows = this.models();
    clear(this.body).append(
      h('div.portal-intro', null,
        h('p', null, t('portal.intro')),
        h('div.portal-state' + (reachable ? '.ok' : '.bad'), { 'data-tour': 'state' },
          reachable ? t('portal.runtime', { url: settings.url }) : t('portal.unreachable', { url: settings.url || '?' }))),
      h('table.portal-table', { 'data-tour': 'list' },
        h('thead', null, h('tr', null,
          h('th', null, t('portal.col.rule')), h('th', null, t('portal.col.decisions')), h('th', null, t('portal.col.active')),
          h('th.num', null, t('portal.col.versions')), h('th.act', null, t('portal.col.actions')))),
        h('tbody', null, rows.map(row => h('tr', { 'data-tour': 'row:' + row.name },
          h('td', null,
            h('div.portal-rule', null, row.name),
            row.file ? h('div.portal-file', null, icon('list', 11), t('portal.fileOf', { file: row.file, project: row.project })) : null),
          h('td', null, row.decisions.join(', ')),
          h('td', null, !row.onRuntime ? h('span.portal-badge.new', null, t('portal.notOnRuntime'))
            : row.active ? h('span.portal-badge.active', null, t('portal.version', { version: row.active }))
              : h('span.portal-muted', null, t('portal.noneActive'))),
          h('td.num', null, String(row.count)),
          h('td.act', null,
            h('button.portal-icon', {
              type: 'button', 'data-tour': 'edit:' + row.name, title: t('portal.edit'), 'aria-label': t('portal.edit'), disabled: !reachable,
              onclick: () => this.open(row.name)
            }, icon('pencil', 13)),
            h('button.portal-icon', {
              type: 'button', 'data-tour': 'versions:' + row.name, title: t('portal.showVersions'), 'aria-label': t('portal.showVersions'), disabled: !reachable || !row.onRuntime,
              onclick: () => this.showVersions(row.name)
            }, icon('history', 13))))))));
  }

  renderTitle() {
    if (!this.titleEl || !this.edit) return;
    const t = this.app.t;
    const edit = this.edit;
    const state = edit.version === null
      ? t('portal.projectFile', { project: edit.project })
      : t('portal.version', { version: edit.version }) + (edit.status ? ' · ' + t('portal.status.' + edit.status) : '');
    clear(this.titleEl).append(...[
      h('span.portal-name', null, edit.model),
      h('span.portal-muted', { 'data-tour': 'title' }, state),
      edit.dirty ? h('span.portal-dirty', null, t('portal.unsaved')) : null
    ].filter(Boolean)); // append() would write a null as text
  }

  renderEditor() {
    const t = this.app.t;
    const edit = this.edit;
    this.controls = {};
    this.titleEl = h('div.portal-edit-title');
    this.renderTitle();
    const changed = kind => {
      if (kind === 'note') return; // a description decides nothing
      if (!edit.dirty) {
        edit.dirty = true;
        this.renderTitle();
      }
      if (kind === 'rules') this.renderEditor();
    };
    const named = this.app.def.portalTable;
    const logics = logicsOf(edit.dmn).sort((a, b) => (b.name === named) - (a.name === named));
    const tables = logics.map(decision => decisionTable({ decision, t, controls: this.controls, onChange: changed }));
    this.description = this.description || textBox({ placeholder: t('portal.description') });
    this.description.setAttribute('aria-label', t('portal.description'));
    const button = (tour, label, iconName, onclick, primary) => h('button.portal-btn' + (primary ? '.primary' : ''), {
      type: 'button', 'data-tour': tour, disabled: !this.reachable(), onclick
    }, icon(iconName, 13), h('span', null, label));
    clear(this.body).append(
      h('div.portal-edit-bar', null,
        h('button.portal-link', { type: 'button', 'data-tour': 'back', onclick: () => this.back() }, icon('chevronLeft', 13), t('portal.back')),
        this.titleEl,
        h('span.portal-spacer'),
        h('span.portal-field', { 'data-tour': 'description' }, this.description),
        button('check', t('portal.check'), 'check', () => this.check()),
        button('draft', t('portal.draft'), 'list', () => this.save(false)),
        button('activate', t('portal.activate'), 'check', () => this.save(true), true)),
      h('div.portal-kie', { 'data-tour': 'kie' },
        h('div.portal-kie-tabs', null,
          h('span.active', null, t('portal.kie.editor')), h('span', null, t('portal.kie.types')), h('span', null, t('portal.kie.included'))),
        h('div.portal-kie-inputs', { 'data-tour': 'inputs' },
          h('span.dmn-kind', null, t('dmn.inputs')),
          edit.dmn.inputs.map(input => h('span.chip.plain', null, input.name, input.type ? h('span.dmn-col-type', null, ' ' + input.type) : null)),
          this.addInputForm()),
        h('p.portal-kie-note', null, t('portal.kie.note')),
        tables));
  }

  addInputForm() {
    const t = this.app.t;
    const name = h('input.input.portal-input-name', { type: 'text', placeholder: t('portal.input.name'), spellcheck: false, 'aria-label': t('portal.input.name') });
    const type = h('select.input.portal-input-type', { 'aria-label': t('portal.input.type') },
      ['string', 'number', 'boolean'].map(value => h('option', { value }, value)));
    const add = () => {
      const value = name.value.trim();
      if (/^[A-Za-z_][A-Za-z0-9_]*$/.test(value)) this.addInput(value, type.value);
    };
    return h('span.portal-add-input', null, name, type,
      h('button.portal-link', { type: 'button', 'data-tour': 'addInput', onclick: add }, icon('plus', 12), t('portal.input.add')));
  }

  renderVersions() {
    const t = this.app.t;
    const runtimeName = this.settings().prefix + this.versionsOf;
    const versions = this.app.runtime.versions(runtimeName).slice().reverse();
    const active = this.app.runtime.active(runtimeName);
    return h('div.portal-dialog-layer', null, h('div.portal-dialog', { 'data-tour': 'dialog', role: 'dialog', 'aria-label': t('portal.versionsOf', { model: this.versionsOf }) },
      h('div.portal-dialog-head', null,
        h('strong', null, t('portal.versionsOf', { model: this.versionsOf })),
        h('button.portal-icon', { type: 'button', 'data-tour': 'close', 'aria-label': t('portal.close'), onclick: () => this.closeVersions() }, icon('close', 13))),
      h('table.portal-table', null,
        h('thead', null, h('tr', null,
          h('th.num', null, t('portal.col.version')), h('th', null, t('portal.col.status')), h('th', null, t('portal.col.source')),
          h('th', null, t('portal.col.author')), h('th', null, t('portal.col.changed')), h('th.act', null, t('portal.col.actions')))),
        h('tbody', null, versions.map(version => h('tr', { 'data-tour': 'v' + version.version },
          h('td.num', null, String(version.version)),
          h('td', null, h('span.portal-badge.' + version.status.toLowerCase(), null, t('portal.status.' + version.status))),
          h('td', null, t('portal.source.' + version.source)),
          h('td', null, version.author || ''),
          h('td', null, version.description || ''),
          h('td.act', null,
            h('button.portal-icon', { type: 'button', title: t('portal.openVersion'), 'aria-label': t('portal.openVersion'), onclick: () => this.open(this.versionsOf, version.version) }, icon('pencil', 13)),
            version.status === 'ACTIVE' ? null : h('button.portal-icon', {
              type: 'button', title: t('portal.activateVersion'), 'aria-label': t('portal.activateVersion'), onclick: () => this.activateVersion(version.version)
            }, icon('play', 12))))))),
      h('div.portal-dialog-foot', null,
        h('button.portal-btn', {
          type: 'button', 'data-tour': 'rollback', disabled: !active || active.previousVersion === null, onclick: () => this.rollback()
        }, icon('reset', 13), h('span', null, t('portal.rollback'))),
        h('span.portal-spacer'),
        h('button.portal-link', { type: 'button', onclick: () => this.closeVersions() }, t('portal.close')))));
  }
}
