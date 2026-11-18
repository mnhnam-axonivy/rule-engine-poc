/*
 * The DMN editor of the tab "Rule Management" (Admin Settings).
 *
 * The editor is the DMN editor of Apache KIE (window.DmnEditor). Its script is served by the rule engine
 * project of the application; the link comes from the Portal (data-editor-script) and the script is loaded
 * the first time a rule is opened. The model goes in and out through the hidden field .rule-editor-dmn:
 * the Portal fills it when a rule is opened, this script fills it before a save or a check.
 */
var RuleManagement = (function () {
  'use strict';

  var READY_FALLBACK_MS = 15000;
  var state = { editor: null, ready: false, dirty: false, busy: false, loading: null, fallback: null };

  function root() {
    return document.querySelector('.rule-management');
  }

  function part(name) {
    var host = root();
    return host ? host.querySelector('.' + name) : null;
  }

  function text(name) {
    var host = root();
    return (host && host.getAttribute('data-text-' + name)) || '';
  }

  function loadEditor(url) {
    if (window.DmnEditor) {
      return Promise.resolve();
    }
    if (!url) {
      return Promise.reject(new Error('no link to the DMN editor'));
    }
    if (!state.loading) {
      state.loading = new Promise(function (resolve, reject) {
        var script = document.createElement('script');
        script.src = url;
        script.async = true;
        script.onload = function () {
          return window.DmnEditor ? resolve() : reject(new Error('the script defines no DmnEditor'));
        };
        script.onerror = function () {
          state.loading = null; // a later attempt loads it again
          script.remove();
          reject(new Error('the script was not loaded'));
        };
        document.head.appendChild(script);
      });
    }
    return state.loading;
  }

  /** Opens the model of the hidden field in the editor; called after the Portal opened a rule. */
  function open() {
    close();
    var host = root();
    var container = part('rule-editor-container');
    var source = part('rule-editor-dmn');
    if (!host || !container || !source) {
      return;
    }
    render();
    loadEditor(host.getAttribute('data-editor-script')).then(function () {
      if (part('rule-editor-container') !== container) {
        return; // the user left the editor while the script was loading
      }
      state.ready = false;
      state.dirty = false;
      clearTimeout(state.fallback);
      // the frame of the editor says when it is ready; should it not, go on anyway
      state.fallback = setTimeout(markReady, READY_FALLBACK_MS);
      state.editor = window.DmnEditor.open({
        container: container,
        initialContent: Promise.resolve(source.value),
        initialFileNormalizedPosixPathRelativeToTheWorkspaceRoot: (host.getAttribute('data-model') || 'model') + '.dmn',
        readOnly: false,
        onError: function () {
          notify(text('cannot-open'));
        }
      });
      state.editor.subscribeToContentChanges(function (dirty) {
        state.dirty = dirty;
        render();
      });
      render();
    }).catch(function () {
      notify(text('load-failed'));
      render();
    });
  }

  function markReady() {
    clearTimeout(state.fallback);
    if (state.editor) {
      state.ready = true;
      render();
    }
  }

  /** Stores what the editor holds as a new version, live or as draft. */
  function save(activate) {
    withContent(function () {
      window.ruleManagementSave([{ name: 'activate', value: activate ? 'true' : 'false' }]);
    });
  }

  /** Lets the DMN engine check what the editor holds. */
  function validate() {
    withContent(function () {
      window.ruleManagementValidate();
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

  /** oncomplete of a save or a check */
  function done(xhr, status, args) {
    state.busy = false;
    if (args && args.saved && state.editor) {
      state.editor.markAsSaved();
      state.dirty = false;
    }
    render();
  }

  /** @return false if the user wants to stay with unsaved changes */
  function leave() {
    if (state.dirty && !window.confirm(text('leave'))) {
      return false;
    }
    close();
    return true;
  }

  function close() {
    clearTimeout(state.fallback);
    if (state.editor) {
      try {
        state.editor.close();
      } catch (e) {
        // the frame is gone already
      }
    }
    state.editor = null;
    state.ready = false;
    state.dirty = false;
    state.busy = false;
  }

  function render() {
    var dirty = part('rule-editor-dirty');
    if (dirty) {
      dirty.hidden = !state.dirty;
    }
    var loading = part('rule-editor-loading');
    if (loading) {
      loading.hidden = state.ready;
    }
    var host = root();
    if (!host) {
      return;
    }
    host.querySelectorAll('.rule-editor-action').forEach(function (button) {
      var disabled = !state.ready || state.busy;
      button.disabled = disabled;
      button.classList.toggle('ui-state-disabled', disabled);
    });
  }

  function notify(message) {
    var loading = part('rule-editor-loading');
    if (loading) {
      loading.textContent = message;
      loading.classList.add('rule-editor-problem');
      loading.hidden = false;
    }
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

  return {
    open: open,
    save: save,
    validate: validate,
    done: done,
    leave: leave,
    /** for tests: what the editor holds, and whether it is ready */
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
