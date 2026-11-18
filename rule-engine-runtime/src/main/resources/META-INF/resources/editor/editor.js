/*
 * DMN editor page of the decision runtime: /editor/
 *
 * Shows a model in the Apache KIE DMN editor (lib/dmn-editor.js) and saves it
 *  - on the runtime, as a new version (source PORTAL: a change by a person, which a project deployment never
 *    replaces), as draft or activated, or
 *  - back to the .dmn file it was opened from, so the Ivy project keeps the change.
 * Talks only to the API of this runtime (../api/). Deep links: #<model> or #<model>/<version>.
 */
(function () {
  'use strict';

  const API = new URL('../api/', location.href);
  const MODEL_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,199}$/;
  const KEY_STORE = 'ruleEngine.apiKey';
  const AUTHOR_STORE = 'ruleEngine.editor.author';
  const DMN_TYPES = [{ description: 'DMN model', accept: { 'application/xml': ['.dmn'] } }];
  const $ = id => document.getElementById(id);

  const state = {
    models: [],
    selected: null, // the model whose versions are listed
    versions: [],
    doc: null, // what the editor shows: { kind: 'runtime' | 'file' | 'new', model, version, status, source, fileName, fileHandle, savedAs }
    editor: null,
    ready: false, // the editor in its frame has started and answers
    dirty: false,
    busy: false
  };
  let readyFallback = 0;
  let hinted = false; // the hint how to reach the decision table is shown once

  // ── small helpers ───────────────────────────────────────────────────────────

  const store = {
    get(area, key) {
      try { return window[area].getItem(key) || ''; } catch (e) { return ''; }
    },
    set(area, key, value) {
      try { window[area].setItem(key, value); } catch (e) { /* not kept, e.g. in a private window */ }
    }
  };

  function el(tag, props, ...children) {
    const node = document.createElement(tag);
    Object.entries(props || {}).forEach(([key, value]) => {
      if (value === undefined || value === null || value === false) return;
      if (key === 'text') node.textContent = value;
      else if (key === 'className') node.className = value;
      else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
      else node.setAttribute(key, value === true ? '' : value);
    });
    node.append(...children.filter(child => child !== null && child !== undefined));
    return node;
  }

  const date = value => (value ? new Date(value).toLocaleString() : '');
  const modelPath = model => 'models/' + encodeURIComponent(model);

  // ── messages ────────────────────────────────────────────────────────────────

  function say(kind, text, findings) {
    const box = $('messages');
    box.className = 'messages ' + kind;
    const close = el('button', { type: 'button', className: 'link close', text: 'Close', onclick: () => { box.hidden = true; } });
    const list = findings && findings.length
      ? el('ul', null, ...findings.map(m => el('li', { text: (m.severity ? m.severity + ': ' : '') + m.message })))
      : null;
    box.replaceChildren(...[close, el('p', { text }), list].filter(Boolean));
    box.hidden = false;
  }

  const quiet = () => { $('messages').hidden = true; };

  // ── the API of the runtime ──────────────────────────────────────────────────

  let apiKey = store.get('sessionStorage', KEY_STORE);
  let keyPrompt = null;

  class ApiError extends Error {
    constructor(status, body) {
      super((body && body.message) || 'The runtime answered with status ' + status + '.');
      this.status = status;
      this.code = body && body.code;
      this.details = (body && body.details) || [];
    }
  }

  async function api(path, { method = 'GET', body, type, as = 'json', query } = {}) {
    const url = new URL(path, API);
    Object.entries(query || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') url.searchParams.set(key, String(value));
    });
    for (;;) {
      const headers = { Accept: as === 'text' ? 'application/xml' : 'application/json' };
      if (type) headers['Content-Type'] = type;
      if (apiKey) headers['X-API-Key'] = apiKey;
      let response;
      try {
        response = await fetch(url, { method, headers, body });
      } catch (e) {
        throw new ApiError(0, { message: 'The decision runtime does not answer at ' + API.origin + '.' });
      }
      if (response.status === 401) {
        if (await askKey(Boolean(apiKey))) continue;
        throw new ApiError(401, { message: 'The runtime needs its API key.' });
      }
      const text = await response.text();
      if (!response.ok) {
        let error = null;
        try { error = JSON.parse(text); } catch (e) { /* not JSON */ }
        throw new ApiError(response.status, error || { message: text || response.statusText });
      }
      if (as === 'text') return text;
      return text ? JSON.parse(text) : null;
    }
  }

  /** Asks for the API key once, however many requests were refused at the same time. */
  function askKey(wrong) {
    if (keyPrompt) return keyPrompt;
    const dialog = $('key-dialog');
    const input = $('key-input');
    input.value = '';
    $('key-wrong').hidden = !wrong;
    keyPrompt = new Promise(resolve => {
      dialog.addEventListener('close', () => {
        const ok = dialog.returnValue === 'ok' && input.value.trim() !== '';
        if (ok) {
          apiKey = input.value.trim();
          store.set('sessionStorage', KEY_STORE, apiKey);
        }
        keyPrompt = null;
        resolve(ok);
      }, { once: true });
      dialog.showModal();
    });
    return keyPrompt;
  }

  const validate = xml => api('validate', { method: 'POST', type: 'application/xml', body: xml });

  // ── models and versions ─────────────────────────────────────────────────────

  async function loadModels() {
    const hint = $('runtime-hint');
    try {
      state.models = await api('models');
      hint.classList.remove('error');
      hint.textContent = state.models.length
        ? 'The models the runtime holds. Select one to change its rules.'
        : 'The runtime holds no model yet. A Business Rule Task registers the .dmn file of its project when it '
          + 'runs. You can also open a .dmn file or start a new model.';
    } catch (e) {
      state.models = [];
      hint.classList.add('error');
      hint.textContent = e.message;
    }
    renderModels();
  }

  function renderModels() {
    $('models').replaceChildren(...state.models.map(model => el('li', null,
      el('button', {
        type: 'button',
        className: 'model' + (model.name === state.selected ? ' selected' : ''),
        'data-model': model.name,
        onclick: () => selectModel(model.name, { open: true })
      },
      el('span', { className: 'model-name', text: model.name }),
      el('span', { className: 'model-meta', text: model.activeVersion ? 'v' + model.activeVersion + ' active' : 'no active version' })))));
  }

  async function selectModel(name, { open = false, version = null } = {}) {
    if (open && !confirmDiscard()) return;
    state.selected = name;
    renderModels();
    await loadVersions();
    if (!open) return;
    const model = state.models.find(m => m.name === name);
    const wanted = version || (model && (model.activeVersion || model.latestVersion));
    if (wanted) await openVersion(name, wanted, { confirmed: true });
  }

  async function loadVersions() {
    if (state.selected) {
      try {
        state.versions = await api(modelPath(state.selected) + '/versions');
      } catch (e) {
        state.versions = [];
        if (e.status === 404) state.selected = null;
        else say('error', e.message);
      }
    } else state.versions = [];
    renderVersions();
  }

  function renderVersions() {
    $('versions-box').hidden = !state.selected;
    $('versions-model').textContent = state.selected || '';
    const versions = state.versions.slice().sort((a, b) => b.version - a.version);
    const active = versions.find(v => v.status === 'ACTIVE');
    const rollback = $('rollback');
    rollback.hidden = !(active && active.previousVersion);
    rollback.title = active && active.previousVersion ? 'Make version ' + active.previousVersion + ' the active one again' : '';
    $('versions').replaceChildren(...versions.map(v => {
      const opened = state.doc && state.doc.kind === 'runtime' && state.doc.model === v.model && state.doc.version === v.version;
      const tip = [v.description, 'created ' + date(v.createdAt),
        v.activatedAt ? 'activated ' + date(v.activatedAt) + (v.activatedBy ? ' by ' + v.activatedBy : '') : ''].filter(Boolean).join('\n');
      return el('tr', { className: opened ? 'opened' : null, title: tip, 'data-version': String(v.version) },
        el('td', { text: String(v.version) }),
        el('td', null, el('span', { className: 'status ' + v.status.toLowerCase(), text: v.status.toLowerCase() })),
        el('td', { text: v.source.toLowerCase() }),
        el('td', { className: 'author', text: v.author || '', title: v.author || null }),
        el('td', { className: 'act' },
          el('button', { type: 'button', className: 'link', text: 'Open', onclick: () => openVersion(v.model, v.version) }),
          v.status === 'ACTIVE' ? null : ' ',
          v.status === 'ACTIVE' ? null
            : el('button', { type: 'button', className: 'link', text: 'Activate', 'data-activate': String(v.version), onclick: () => activateVersion(v.model, v.version) })));
    }));
  }

  async function openVersion(model, version, { confirmed = false } = {}) {
    if (!confirmed && !confirmDiscard()) return;
    try {
      const xml = await api(modelPath(model) + '/versions/' + version + '/content', { as: 'text' });
      const info = state.versions.find(v => v.model === model && v.version === version) || {};
      openDocument(xml, { kind: 'runtime', model, version, status: info.status, source: info.source });
      setHash(model + '/' + version);
      if (hinted) quiet();
      else {
        hinted = true;
        say('info', 'To change a rule, select the decision in the diagram and click "Edit" below it: its decision table opens. '
          + 'Then save it as a new version.');
      }
    } catch (e) {
      say('error', e.message);
    }
  }

  async function activateVersion(model, version) {
    const author = requireAuthor();
    if (!author) return;
    await busy(async () => {
      await api(modelPath(model) + '/versions/' + version + '/activate',
        { method: 'POST', type: 'application/json', body: JSON.stringify({ user: author }) });
      await refresh();
      say('ok', 'Version ' + version + ' of ' + model + ' is active. The next evaluation uses it.');
    });
  }

  async function rollback() {
    const model = state.selected;
    const author = requireAuthor();
    if (!model || !author) return;
    await busy(async () => {
      const version = await api(modelPath(model) + '/rollback', { method: 'POST', query: { user: author } });
      await refresh();
      say('ok', 'Rolled back: version ' + version.version + ' of ' + model + ' is active again.');
    });
  }

  /** Reloads models and versions after a change, and what the toolbar says about the opened version. */
  async function refresh() {
    await loadModels();
    await loadVersions();
    const doc = state.doc;
    if (doc && doc.kind === 'runtime') {
      const info = state.versions.find(v => v.model === doc.model && v.version === doc.version);
      if (info) Object.assign(doc, { status: info.status, source: info.source });
    }
    renderToolbar();
    renderVersions();
  }

  // ── the editor ──────────────────────────────────────────────────────────────

  function openDocument(xml, doc) {
    if (!window.DmnEditor) {
      say('error', 'The DMN editor could not be loaded (lib/dmn-editor.js).');
      return;
    }
    if (state.editor) state.editor.close();
    $('editor').replaceChildren();
    state.doc = doc;
    state.dirty = false;
    state.ready = false;
    // the frame says when it is ready (see the 'message' listener); should a later editor not say so, go on anyway
    clearTimeout(readyFallback);
    readyFallback = setTimeout(markReady, 15000);
    const editor = window.DmnEditor.open({
      container: $('editor'),
      initialContent: Promise.resolve(xml),
      initialFileNormalizedPosixPathRelativeToTheWorkspaceRoot: doc.fileName || doc.model + '.dmn',
      readOnly: false,
      onError: () => say('error', 'The editor cannot open this model. Is it a DMN file?')
    });
    editor.subscribeToContentChanges(dirty => {
      state.dirty = dirty;
      renderToolbar();
    });
    state.editor = editor;
    $('empty').hidden = true;
    renderToolbar();
    renderVersions();
  }

  function markReady() {
    clearTimeout(readyFallback);
    state.ready = true;
    renderToolbar();
  }

  function renderToolbar() {
    const doc = state.doc;
    $('toolbar').hidden = !doc;
    if (!doc) return;
    $('doc-kind').textContent = { runtime: 'Runtime', file: 'File', new: 'New model' }[doc.kind];
    const name = $('doc-model');
    if (document.activeElement !== name) name.value = doc.model || '';
    name.readOnly = doc.kind === 'runtime';
    $('doc-state').textContent = describe(doc) + (state.ready ? '' : ' · the editor is starting…');
    $('doc-dirty').hidden = !state.dirty;
    ['undo', 'redo', 'check', 'save-file', 'save-draft', 'save-activate'].forEach(id => { $(id).disabled = state.busy || !state.ready; });
    $('save-file').textContent = doc.fileHandle ? 'Save file' : 'Save as file…';
  }

  function describe(doc) {
    if (doc.kind === 'runtime') {
      return 'version ' + doc.version + (doc.status ? ' · ' + doc.status.toLowerCase() : '')
        + (doc.source ? ' · from ' + doc.source.toLowerCase() : '');
    }
    const saved = doc.savedAs ? 'saved on the runtime as version ' + doc.savedAs : '';
    if (doc.kind === 'file') return [doc.fileName, saved].filter(Boolean).join(' · ');
    return saved || 'not on the runtime yet';
  }

  function confirmDiscard() {
    return !state.dirty || window.confirm('Discard the unsaved changes of ' + (state.doc.model || 'this model') + '?');
  }

  async function busy(work) {
    if (state.busy) return;
    state.busy = true;
    renderToolbar();
    try {
      await work();
    } catch (e) {
      say('error', e.message, e.details);
    } finally {
      state.busy = false;
      renderToolbar();
    }
  }

  function requireAuthor() {
    const input = $('author');
    const author = input.value.trim();
    input.classList.toggle('missing', !author);
    if (!author) {
      say('warn', 'Enter your name at the top right. The runtime stores it with every version.');
      input.focus();
    }
    return author;
  }

  async function check() {
    if (!state.editor) return;
    await busy(async () => {
      const result = await validate(await state.editor.getContent());
      if (!result.valid) say('error', 'The runtime does not accept this model:', result.messages);
      else if (result.messages.length) say('warn', 'The runtime accepts this model, with findings:', result.messages);
      else say('ok', 'The runtime accepts this model. No findings.');
    });
  }

  async function saveToRuntime(activate) {
    const doc = state.doc;
    if (!doc || !state.editor || state.busy) return;
    const author = requireAuthor();
    if (!author) return;
    const model = $('doc-model').value.trim();
    if (!MODEL_NAME.test(model)) {
      say('error', 'Name the model with letters, digits, ".", "_" or "-", starting with a letter or digit.');
      $('doc-model').focus();
      return;
    }
    await busy(async () => {
      const xml = await state.editor.getContent();
      const result = await validate(xml);
      if (!result.valid) {
        say('error', 'Not saved. The runtime does not accept this model:', result.messages);
        return;
      }
      let version = await api(modelPath(model) + '/versions', {
        method: 'POST',
        type: 'application/xml',
        body: xml,
        query: { author, description: $('description').value.trim(), source: 'PORTAL', activate: String(activate) }
      });
      let text;
      if (version.created) {
        text = activate
          ? 'Saved as version ' + version.version + ' of ' + model + ' and activated. The next evaluation uses it.'
          : 'Saved as version ' + version.version + ' of ' + model + ', a draft. Activate it in the list when it should go live.';
      } else {
        text = 'Nothing new: this content is version ' + version.version + ' of ' + model + ' already.';
        if (activate && version.status !== 'ACTIVE') {
          version = await api(modelPath(model) + '/versions/' + version.version + '/activate',
            { method: 'POST', type: 'application/json', body: JSON.stringify({ user: author }) });
          text += ' It is active now.';
        }
      }
      state.editor.markAsSaved();
      $('description').value = '';
      if (doc.kind === 'file') {
        Object.assign(doc, { model, savedAs: version.version });
        text += ' The file ' + doc.fileName + ' is unchanged: use "Save file" to keep the change in the project.';
      } else {
        Object.assign(doc, { kind: 'runtime', model, version: version.version, status: version.status, source: version.source });
        setHash(model + '/' + version.version);
      }
      state.selected = model;
      await refresh();
      say('ok', text, result.messages);
    });
  }

  async function saveFile() {
    const doc = state.doc;
    if (!doc || !state.editor) return;
    const handle = doc.fileHandle;
    // ask for write access first, while the click still counts as the user's action
    if (handle && handle.requestPermission && (await handle.requestPermission({ mode: 'readwrite' })) !== 'granted') {
      say('warn', 'Not saved: the browser was not allowed to write ' + doc.fileName + '.');
      return;
    }
    await busy(async () => {
      const xml = await state.editor.getContent();
      const name = (doc.model || 'model') + '.dmn';
      if (handle) {
        await write(handle, xml);
        state.editor.markAsSaved();
        say('ok', 'Saved ' + doc.fileName + '. A Business Rule Task sends the changed file to the runtime with its next evaluation.');
        return;
      }
      if (window.showSaveFilePicker) {
        let target;
        try {
          target = await window.showSaveFilePicker({ suggestedName: name, types: DMN_TYPES });
        } catch (e) {
          if (e.name === 'AbortError') return;
          throw e;
        }
        await write(target, xml);
        if (doc.kind !== 'runtime') {
          Object.assign(doc, { kind: 'file', fileHandle: target, fileName: target.name });
          state.editor.markAsSaved();
        }
        say('ok', 'Saved as ' + target.name + '.');
        return;
      }
      download(xml, name);
      say('info', 'Downloaded ' + name + '. Put it into the dmn folder of the Ivy project to replace the file there.');
    });
  }

  async function write(handle, text) {
    const out = await handle.createWritable();
    await out.write(text);
    await out.close();
  }

  function download(text, name) {
    const url = URL.createObjectURL(new Blob([text], { type: 'application/xml' }));
    const link = el('a', { href: url, download: name });
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  // ── files and new models ────────────────────────────────────────────────────

  async function openFile() {
    if (!confirmDiscard()) return;
    if (!window.showOpenFilePicker) {
      const input = $('file-input');
      input.value = '';
      input.click();
      return;
    }
    let handle;
    try {
      [handle] = await window.showOpenFilePicker({ types: DMN_TYPES });
    } catch (e) {
      if (e.name !== 'AbortError') say('error', e.message);
      return;
    }
    const file = await handle.getFile();
    openFromFile(await file.text(), file.name, handle);
  }

  async function openFromFile(xml, fileName, handle) {
    const model = fileName.replace(/\.(dmn|xml)$/i, '');
    openDocument(xml, { kind: 'file', model: MODEL_NAME.test(model) ? model : '', fileName, fileHandle: handle });
    setHash('');
    say('info', handle
      ? 'Opened ' + fileName + '. "Save file" writes your changes back to it.'
      : 'Opened ' + fileName + '. This browser cannot write to files: "Save as file…" downloads the changed file.');
    if (state.models.some(m => m.name === model)) {
      state.selected = model;
      renderModels();
      await loadVersions();
    }
  }

  function askName() {
    const dialog = $('name-dialog');
    const input = $('name-input');
    input.value = '';
    return new Promise(resolve => {
      dialog.addEventListener('close', () => resolve(dialog.returnValue === 'ok' ? input.value.trim() : ''), { once: true });
      dialog.showModal();
    });
  }

  async function newModel() {
    if (!confirmDiscard()) return;
    const name = await askName();
    if (!name) return;
    if (state.models.some(m => m.name === name)) {
      say('warn', 'The runtime has a model "' + name + '" already. Select it on the left to change it.');
      return;
    }
    openDocument('', { kind: 'new', model: name });
    setHash('');
    say('info', 'An empty model. Add a decision and its inputs with the palette on the left of the editor, '
      + 'then "Save and activate".');
  }

  // ── start ───────────────────────────────────────────────────────────────────

  function setHash(value) {
    const next = value ? '#' + value : location.pathname + location.search;
    if ((value ? '#' + value : '') !== location.hash) history.replaceState(null, '', next);
  }

  function fromHash() {
    const [model, version] = decodeURIComponent(location.hash.slice(1)).split('/');
    return model ? { model, version: Number(version) || null } : null;
  }

  async function start() {
    const author = $('author');
    author.value = store.get('localStorage', AUTHOR_STORE);
    author.addEventListener('input', () => {
      store.set('localStorage', AUTHOR_STORE, author.value.trim());
      if (author.classList.contains('missing')) {
        author.classList.remove('missing');
        quiet(); // the warning that asked for the name
      }
    });
    $('doc-model').addEventListener('input', event => { if (state.doc && state.doc.kind !== 'runtime') state.doc.model = event.target.value.trim(); });
    $('undo').addEventListener('click', () => state.editor && state.editor.undo());
    $('redo').addEventListener('click', () => state.editor && state.editor.redo());
    $('check').addEventListener('click', check);
    $('save-file').addEventListener('click', saveFile);
    $('save-draft').addEventListener('click', () => saveToRuntime(false));
    $('save-activate').addEventListener('click', () => saveToRuntime(true));
    $('rollback').addEventListener('click', rollback);
    $('open-file').addEventListener('click', openFile);
    $('new-model').addEventListener('click', newModel);
    $('file-input').addEventListener('change', async event => {
      const file = event.target.files[0];
      if (file) await openFromFile(await file.text(), file.name, null);
    });
    window.addEventListener('message', event => {
      const frame = $('editor').querySelector('iframe');
      if (frame && event.source === frame.contentWindow && event.data && event.data.type === 'kogitoEditor_ready') markReady();
    });
    window.addEventListener('beforeunload', event => {
      if (!state.dirty) return;
      event.preventDefault();
      event.returnValue = '';
    });

    await loadModels();
    const wanted = fromHash();
    if (!wanted) return;
    if (state.models.some(m => m.name === wanted.model)) {
      await selectModel(wanted.model, { open: true, version: wanted.version });
    } else if (state.models.length || !$('runtime-hint').classList.contains('error')) {
      say('warn', 'The runtime has no model "' + wanted.model + '" yet. A Business Rule Task registers it with its '
        + 'first evaluation. You can also open its .dmn file with "Open .dmn file…".');
    }
  }

  // for tests and the browser console: the editor that is open, and what the page knows
  window.ruleEditor = {
    get editor() { return state.editor; },
    get ready() { return state.ready; },
    get state() { return state; }
  };

  start();
}());
