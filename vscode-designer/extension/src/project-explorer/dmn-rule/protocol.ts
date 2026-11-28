import type { NotificationType, RequestType } from 'vscode-messenger-common';
import type { RuleField } from './dataclass-fields';

/** The messages between the extension and the webview of a new DMN rule, see webviews/dmn-rule/src/protocol.ts */
export type DmnRuleInit = { model: string; fields: RuleField[] };
export type DmnRuleSelection = { inputs: string[]; outputs: string[] };
export type DmnRuleApplyResult = { error?: string };

export const DmnRuleInitRequest: RequestType<void, DmnRuleInit> = { method: 'dmnRule/init' };
export const DmnRuleApplyRequest: RequestType<DmnRuleSelection, DmnRuleApplyResult> = { method: 'dmnRule/apply' };
export const DmnRuleSkipNotification: NotificationType<void> = { method: 'dmnRule/skip' };
