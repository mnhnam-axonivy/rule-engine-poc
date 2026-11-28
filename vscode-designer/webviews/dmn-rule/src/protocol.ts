import type { NotificationType, RequestType } from 'vscode-messenger-common';

/** The messages between the extension and this webview, see extension/src/project-explorer/dmn-rule/protocol.ts */
export type RuleField = {
  id: string;
  name: string;
  dataClass: string;
  namespace: string;
  project: string;
  type: string;
};

export type DmnRuleInit = { model: string; fields: RuleField[] };
export type DmnRuleSelection = { inputs: string[]; outputs: string[] };
export type DmnRuleApplyResult = { error?: string };

export const DmnRuleInitRequest: RequestType<void, DmnRuleInit> = { method: 'dmnRule/init' };
export const DmnRuleApplyRequest: RequestType<DmnRuleSelection, DmnRuleApplyResult> = { method: 'dmnRule/apply' };
export const DmnRuleSkipNotification: NotificationType<void> = { method: 'dmnRule/skip' };
