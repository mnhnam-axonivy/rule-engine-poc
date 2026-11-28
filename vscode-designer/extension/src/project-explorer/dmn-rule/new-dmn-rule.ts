import path from 'path';
import type { ExtensionContext } from 'vscode';
import { l10n, Uri, workspace } from 'vscode';
import { logErrorMessage } from '../../base/logging-util';
import { type AddCommandSelectionContext } from '../ivy-project-explorer';
import {
  type InputStep,
  type MSStateBase,
  MultiStepCancelledError,
  MultiStepInput,
  MultiStepInvalidStateError,
  type ProjectSelection
} from '../utils/multi-step-input';
import { DMN_EXTENSION, DMN_FOLDER, dmnRuleName, dmnRuleTemplate, isValidDmnRuleName } from './dmn-rule-file';
import { openDmnRulePanel } from './dmn-rule-panel';

interface NewDmnRuleState extends MSStateBase {
  project?: ProjectSelection;
  name?: string;
  projectFromSelection?: ProjectSelection;
}

const projectSelection = (projectPath: string): ProjectSelection => ({
  label: path.basename(projectPath),
  description: projectPath,
  path: projectPath
});

const dmnRuleUri = (projectPath: string, name: string) => Uri.joinPath(Uri.file(projectPath), DMN_FOLDER, `${name}${DMN_EXTENSION}`);

const exists = (uri: Uri) =>
  workspace.fs.stat(uri).then(
    () => true,
    () => false
  );

export const addNewDmnRule = async (context: ExtensionContext, selectionContext: AddCommandSelectionContext) => {
  const validateName = async (typed: string) => {
    const name = dmnRuleName(typed);
    if (!isValidDmnRuleName(name)) {
      return l10n.t(
        'Only letters, digits, "-", "_" and "." are allowed -- Must start with a letter or digit -- No spaces -- Cannot be empty'
      );
    }
    if (state.project && (await exists(dmnRuleUri(state.project.path, name)))) {
      return l10n.t('A DMN rule with this name already exists in this project.');
    }
    return undefined;
  };

  const stepProject: InputStep<NewDmnRuleState> = async (input: MultiStepInput<NewDmnRuleState>, state: NewDmnRuleState) => {
    if (state.projectFromSelection && state.project === undefined) {
      state.project = state.projectFromSelection;
      state.projectFromSelection = undefined;
      return;
    }
    state.project = await input.showQuickPick<ProjectSelection>({
      title: state.dialogTitle,
      titleSuffix: l10n.t('Choose project'),
      placeholder: l10n.t('Select one of the available projects'),
      currentStep: state.currentStep,
      totalSteps: state.totalSteps,
      value: state.project ? state.project.label : '',
      items: selectionContext.existingIvyProjects.map(projectSelection)
    });
  };

  const stepName: InputStep<NewDmnRuleState> = async (input: MultiStepInput<NewDmnRuleState>, state: NewDmnRuleState) => {
    state.name = await input.showTextInput({
      title: state.dialogTitle,
      titleSuffix: l10n.t('Choose name'),
      placeholder: l10n.t(
        'Enter the name of the rule, e.g. member-discount. It is the name of the model and of its file in the folder dmn.'
      ),
      currentStep: state.currentStep,
      totalSteps: state.totalSteps,
      value: state.name,
      validationFunction: validateName,
      onBack: (typedValue: string) => {
        state.name = typedValue;
      }
    });
  };

  const steps: InputStep<NewDmnRuleState>[] = [stepProject, stepName];
  const projectPath = selectionContext.projectPathSelection;
  const state: NewDmnRuleState = {
    dialogTitle: l10n.t('Add New DMN Rule'),
    currentStep: 1,
    totalSteps: steps.length,
    projectFromSelection: projectPath ? projectSelection(projectPath) : undefined
  };

  try {
    await new MultiStepInput<NewDmnRuleState>().stepThrough(steps, state);
  } catch (err) {
    if (err instanceof MultiStepCancelledError) {
      if (err.message.trim()) {
        logErrorMessage(err.message);
      }
      return;
    }
    throw err;
  }

  if (state.project === undefined || state.name === undefined) {
    throw new MultiStepInvalidStateError(
      'DMN rule creation failed due to corrupted input state. name or project selection is undefined. Current input state: ' +
        JSON.stringify(state)
    );
  }
  const name = dmnRuleName(state.name);
  const uri = dmnRuleUri(state.project.path, name);
  await workspace.fs.createDirectory(Uri.joinPath(Uri.file(state.project.path), DMN_FOLDER));
  await workspace.fs.writeFile(uri, new TextEncoder().encode(dmnRuleTemplate(name)));
  openDmnRulePanel(context, { model: name, uri, projectDirs: selectionContext.existingIvyProjects });
};
