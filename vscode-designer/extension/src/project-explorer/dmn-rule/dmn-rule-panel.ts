import type { ExtensionContext, Uri } from 'vscode';
import { l10n, ViewColumn, window, workspace } from 'vscode';
import { Messenger } from 'vscode-messenger';
import { executeCommand } from '../../base/commands';
import { createWebViewContent } from '../../editors/webview-helper';
import { type DataClass, DmnTypeMapper, readDataClasses, ruleFields } from './dataclass-fields';
import { type DmnField, generateDmn, toFeelName } from './dmn-generator';
import { DmnRuleApplyRequest, DmnRuleInitRequest, type DmnRuleSelection, DmnRuleSkipNotification } from './protocol';

export type DmnRule = { model: string; uri: Uri; projectDirs: string[] };

type ChosenField = { label: string; field: DmnField };

const chosenFields = (ids: string[], dataClasses: DataClass[], mapper: DmnTypeMapper): ChosenField[] =>
  ids.flatMap(id => {
    for (const dataClass of dataClasses) {
      const field = dataClass.fields.find(candidate => id === `${dataClass.project}:${dataClass.qualifiedName}.${candidate.name}`);
      if (field) {
        return [{ label: `${dataClass.simpleName}.${field.name}`, field: { name: field.name, type: mapper.toDmnType(field.type) } }];
      }
    }
    return [];
  });

/** @returns why the Business Rule Task could not map the chosen fields by name, or undefined if it can */
const problemOf = (inputs: ChosenField[], outputs: ChosenField[]) => {
  if (inputs.length === 0 || outputs.length === 0) {
    return l10n.t('Choose at least one input and one output field.');
  }
  for (const chosen of [inputs, outputs]) {
    const names = chosen.map(({ field }) => toFeelName(field.name));
    const duplicate = names.find((name, index) => names.indexOf(name) !== index);
    if (duplicate) {
      return l10n.t(
        'Two chosen fields are named "{0}". The Business Rule Task finds a field by its name: choose fields with different names.',
        duplicate
      );
    }
  }
  const inputNames = new Set(inputs.map(({ field }) => toFeelName(field.name)));
  const both = outputs.map(({ field }) => toFeelName(field.name)).find(name => inputNames.has(name));
  if (both) {
    return l10n.t('"{0}" is chosen as input and as output. A decision cannot read and fill the same name.', both);
  }
  return undefined;
};

const description = (inputs: ChosenField[], outputs: ChosenField[]) =>
  `Created with New DMN Rule. Inputs: ${inputs.map(chosen => chosen.label).join(', ')}. Outputs: ${outputs
    .map(chosen => chosen.label)
    .join(', ')}. The Business Rule Task finds inputs and results by name in the process data.`;

/**
 * Lets the author choose the fields of the data classes that the new rule reads and fills, then generates the DMN
 * from them. The file is opened when the panel closes, with or without chosen fields.
 */
export const openDmnRulePanel = (context: ExtensionContext, rule: DmnRule) => {
  const panel = window.createWebviewPanel('ivy.dmnRule', l10n.t('DMN Rule {0}', rule.model), ViewColumn.Active, {
    enableScripts: true,
    retainContextWhenHidden: true
  });
  // the webview shows a spinner while the data classes are read
  const loading = readDataClasses(rule.projectDirs);
  const messenger = new Messenger();
  messenger.registerWebviewPanel(panel);
  messenger.onRequest(DmnRuleInitRequest, async () => ({ model: rule.model, fields: ruleFields(await loading) }));
  messenger.onRequest(DmnRuleApplyRequest, async (selection: DmnRuleSelection) => {
    const dataClasses = await loading;
    const mapper = DmnTypeMapper.forProjects(dataClasses, rule.projectDirs);
    const inputs = chosenFields(selection.inputs, dataClasses, mapper);
    const outputs = chosenFields(selection.outputs, dataClasses, mapper);
    const error = problemOf(inputs, outputs);
    if (error) {
      return { error };
    }
    const dmn = generateDmn({
      modelName: rule.model,
      inputs: inputs.map(chosen => chosen.field),
      outputs: outputs.map(chosen => chosen.field),
      description: description(inputs, outputs)
    });
    await workspace.fs.writeFile(rule.uri, new TextEncoder().encode(dmn));
    // closed after the answer is sent
    setTimeout(() => panel.dispose());
    return {};
  });
  messenger.onNotification(DmnRuleSkipNotification, () => panel.dispose());
  panel.onDidDispose(() => executeCommand('vscode.open', rule.uri));
  panel.webview.html = createWebViewContent(context, panel.webview, 'dmn-rule');
};
