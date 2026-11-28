/*
 * The dialog RuleEditor: the DMN editor of Apache KIE (window.DmnEditor, from dmn-editor-standalone-<version>) for one
 * rule. The model goes in and out through the hidden field .rule-editor-dmn: the dialog fills it when it starts, this
 * script fills it before a check or a save.
 *
 * In an iframe, the page tells its parent when a version is saved and when the editor closes, with a message
 * { type: 'axonivy-rule-editor', event: 'saved' | 'closed', model } to the same origin.
 */
var RuleEditor = (function () {
  'use strict';

  var MESSAGE = 'axonivy-rule-editor';
  var READY_FALLBACK_MS = 15000;
  var state = { editor: null, ready: false, dirty: false, busy: false, fallback: null };

  function root() {
    return document.querySelector('.rule-editor');
  }

  function part(name) {
    return document.querySelector('.' + name);
  }

  function text(name) {
    var host = root();
    return (host && host.getAttribute('data-text-' + name)) || '';
  }

  function open() {
    var container = part('rule-editor-container');
    var source = part('rule-editor-dmn');
    if (!container || !source) {
      render(); // nothing to edit: the dialog shows why
      return;
    }
    if (!window.DmnEditor) {
      problem(text('not-loaded'));
      return;
    }
    // the frame of the editor says when it is ready; should it not, go on anyway
    state.fallback = setTimeout(markReady, READY_FALLBACK_MS);
    state.editor = window.DmnEditor.open({
      container: container,
      initialContent: Promise.resolve(source.value),
      initialFileNormalizedPosixPathRelativeToTheWorkspaceRoot: (root().getAttribute('data-model') || 'model') + '.dmn',
      readOnly: false,
      onError: function () {
        problem(text('cannot-open'));
      }
    });
    state.editor.subscribeToContentChanges(function (dirty) {
      state.dirty = dirty;
      render();
    });
    render();
  }

  function markReady() {
    clearTimeout(state.fallback);
    if (state.editor) {
      state.ready = true;
      render();
    }
  }

  /** Lets the DMN engine check what the editor holds. */
  function check() {
    withContent(function () {
      window.ruleEditorCheck();
    });
  }

  /** Stores what the editor holds as a new version, live or as draft. */
  function save(activate) {
    withContent(function () {
      window.ruleEditorSave([{ name: 'activate', value: activate ? 'true' : 'false' }]);
    });
  }

  function withContent(send) {
    if (!state.editor || !state.ready || state.busy) {
      return;
    }
    state.busy = true;
    render();
    state.editor.getContent().then(function (xml) {
      part('rule-editor-dmn').value = xml;
      send();
    }).catch(function () {
      state.busy = false;
      render();
    });
  }

  /** oncomplete of a check or a save: the updated messages say whether a version was stored */
  function done() {
    var notices = document.querySelector('[id$=":notices"]');
    var saved = notices && notices.getAttribute('data-saved') === 'true';
    state.busy = false;
    if (saved && state.editor) {
      state.editor.markAsSaved();
      state.dirty = false;
      tell('saved');
    }
    render();
  }

  /** Ends the dialog; asks first if there are unsaved changes. */
  function close() {
    if (state.dirty && !window.confirm(text('leave'))) {
      return;
    }
    state.dirty = false;
    tell('closed');
    window.ruleEditorClose();
  }

  function tell(event) {
    if (window.parent !== window) {
      window.parent.postMessage({ type: MESSAGE, event: event, model: root().getAttribute('data-model') }, window.location.origin);
    }
  }

  function render() {
    var dirty = part('rule-editor-dirty');
    if (dirty) {
      dirty.hidden = !state.dirty;
    }
    var loading = part('rule-editor-loading');
    if (loading && !loading.classList.contains('text-red-600')) {
      loading.hidden = state.ready || !state.editor;
    }
    document.querySelectorAll('.rule-editor-action').forEach(function (button) {
      var disabled = !state.ready || state.busy;
      button.disabled = disabled;
      button.classList.toggle('ui-state-disabled', disabled);
    });
  }

  function problem(message) {
    var loading = part('rule-editor-loading');
    if (loading) {
      loading.textContent = message;
      loading.classList.remove('text-color-secondary');
      loading.classList.add('text-red-600');
      loading.hidden = false;
    }
    render();
  }

  window.addEventListener('message', function (event) {
    var container = part('rule-editor-container');
    var frame = container ? container.querySelector('iframe') : null;
    if (frame && event.source === frame.contentWindow && event.data && event.data.type === 'kogitoEditor_ready') {
      markReady();
    }
  });

  window.addEventListener('beforeunload', function (event) {
    if (state.dirty) {
      event.preventDefault();
      event.returnValue = '';
    }
  });

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', open);
  } else {
    open();
  }

  return {
    check: check,
    save: save,
    done: done,
    close: close,
    /** for tests: the KIE editor, and whether it is ready */
    editor: function () {
      return state.editor;
    },
    isReady: function () {
      return state.ready;
    },
    isDirty: function () {
      return state.dirty;
    }
  };
})();
