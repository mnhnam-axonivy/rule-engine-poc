/*
 * The tab "Rule Management" (Admin Settings) shows the DMN editor of the rule engine project in an iframe: its
 * dialog RuleEditor. The dialog tells this page when it closes, then the list of rules comes back.
 */
(function () {
  'use strict';

  window.addEventListener('message', function (event) {
    var frame = document.querySelector('.rule-editor-frame');
    var data = event.data || {};
    if (!frame || event.source !== frame.contentWindow || event.origin !== window.location.origin) {
      return;
    }
    if (data.type === 'axonivy-rule-editor' && data.event === 'closed' && window.ruleManagementEditorClosed) {
      window.ruleManagementEditorClosed();
    }
  });
})();
