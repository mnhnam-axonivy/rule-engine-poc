/*
 * DMN editor page of the Axon Ivy engine: {application}/api/rule-engine/editor
 *
 * Shows a .dmn file in the Apache KIE DMN editor (lib/dmn-editor.js), lets the DMN engine check it and saves it back
 * to the file, so the Ivy project keeps the change. The versions of a model are kept by the application, see the tab
 * Rule Management of the Portal.
 */
(function () {
  'use strict';

  const VALIDATE = new URL('validate', location.href);
  const MODEL_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,199}$/;
  const DMN_TYPES = [{ description: 'DMN model', accept: { 'application/xml': ['.dmn'] } }];
  const $ = id => document.getElementById(id);

  const state = {
    doc: null, // what the editor shows: { kind: 'file' | 'new', model, fileName, fileHandle }
    editor: null,
    ready: false, // the editor in its frame has started and answers
    dirty: false,
    busy: false
  };
  let readyFallback = 0;
  let hinted = false; // the hint how to reach the decision table is shown once

  // ── small helpers ───────────────────────────────────────────────────────────

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

  // ── the check by the DMN engine ─────────────────────────────────────────────

  class ApiError extends Error {
    constructor(status, body) {
      super((body && body.message) || 'The engine answered with status ' + status + '.');
      this.status = status;
      this.details = (body && body.details) || [];
    }
  }

  async function validate(xml) {
    // Axon Ivy refuses a POST without this header (CSRF protection)
    const headers = { Accept: 'application/json', 'Content-Type': 'application/xml', 'X-Requested-By': 'ivy' };
    let response;
    try {
      response = await fetch(VALIDATE, { method: 'POST', headers, body: xml });
    } catch (e) {
      throw new ApiError(0, { message: 'The Axon Ivy engine does not answer at ' + VALIDATE.origin + '.' });
    }
    if (response.status === 401 || response.status === 403) {
      throw new ApiError(response.status, { message: 'Log in to the Axon Ivy engine to let the DMN engine check models.' });
    }
    const text = await response.text();
    if (!response.ok) {
      let error = null;
      try { error = JSON.parse(text); } catch (e) { /* not JSON */ }
      throw new ApiError(response.status, error || { message: text || response.statusText });
    }
    return JSON.parse(text);
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
    $('doc-kind').textContent = { file: 'File', new: 'New model' }[doc.kind];
    const name = $('doc-model');
    if (document.activeElement !== name) name.value = doc.model || '';
    $('doc-state').textContent = (doc.fileName || 'not saved yet') + (state.ready ? '' : ' · the editor is starting…');
    $('doc-dirty').hidden = !state.dirty;
    ['undo', 'redo', 'check', 'save-file'].forEach(id => { $(id).disabled = state.busy || !state.ready; });
    $('save-file').textContent = doc.fileHandle ? 'Save file' : 'Save as file…';
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

  async function check() {
    if (!state.editor) return;
    await busy(async () => {
      const result = await validate(await state.editor.getContent());
      if (!result.valid) say('error', 'The DMN engine does not accept this model:', result.messages);
      else if (result.messages.length) say('warn', 'The DMN engine accepts this model, with findings:', result.messages);
      else say('ok', 'The DMN engine accepts this model. No findings.');
    });
  }

  async function saveFile() {
    const doc = state.doc;
    if (!doc || !state.editor) return;
    const model = $('doc-model').value.trim();
    if (!MODEL_NAME.test(model)) {
      say('error', 'Name the model with letters, digits, ".", "_" or "-", starting with a letter or digit.');
      $('doc-model').focus();
      return;
    }
    const handle = doc.fileHandle;
    // ask for write access first, while the click still counts as the user's action
    if (handle && handle.requestPermission && (await handle.requestPermission({ mode: 'readwrite' })) !== 'granted') {
      say('warn', 'Not saved: the browser was not allowed to write ' + doc.fileName + '.');
      return;
    }
    await busy(async () => {
      const xml = await state.editor.getContent();
      const name = model + '.dmn';
      if (handle) {
        await write(handle, xml);
        state.editor.markAsSaved();
        say('ok', 'Saved ' + doc.fileName + '. A Business Rule Task uses the changed file with its next evaluation.');
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
        Object.assign(doc, { kind: 'file', model, fileHandle: target, fileName: target.name });
        state.editor.markAsSaved();
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

  function openFromFile(xml, fileName, handle) {
    const model = fileName.replace(/\.(dmn|xml)$/i, '');
    openDocument(xml, { kind: 'file', model: MODEL_NAME.test(model) ? model : '', fileName, fileHandle: handle });
    const saving = handle
      ? '"Save file" writes your changes back to it.'
      : 'This browser cannot write to files: "Save as file…" downloads the changed file.';
    if (hinted) say('info', 'Opened ' + fileName + '. ' + saving);
    else {
      hinted = true;
      say('info', 'Opened ' + fileName + '. ' + saving + ' To change a rule, select the decision in the diagram and click '
        + '"Edit" below it: its decision table opens.');
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
    openDocument('', { kind: 'new', model: name });
    say('info', 'An empty model. Add a decision and its inputs with the palette on the left of the editor, '
      + 'then "Save as file…".');
  }

  // ── start ───────────────────────────────────────────────────────────────────

  function start() {
    $('doc-model').addEventListener('input', event => { if (state.doc) state.doc.model = event.target.value.trim(); });
    $('undo').addEventListener('click', () => state.editor && state.editor.undo());
    $('redo').addEventListener('click', () => state.editor && state.editor.redo());
    $('check').addEventListener('click', check);
    $('save-file').addEventListener('click', saveFile);
    $('open-file').addEventListener('click', openFile);
    $('new-model').addEventListener('click', newModel);
    $('file-input').addEventListener('change', async event => {
      const file = event.target.files[0];
      if (file) openFromFile(await file.text(), file.name, null);
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
  }

  // for tests and the browser console: the editor that is open, and what the page knows
  window.ruleEditor = {
    get editor() { return state.editor; },
    get ready() { return state.ready; },
    get state() { return state; },
    open: openFromFile
  };

  start();
}());
