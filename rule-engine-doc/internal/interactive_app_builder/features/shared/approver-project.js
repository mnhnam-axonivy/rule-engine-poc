import { project, ELEMENTS } from './project.js';
import { APPROVER as TASKS, extendedProcess, withApprover } from './approver.js';

export { NEW_MODEL, INPUT_FIELDS, OUTPUT_FIELDS } from './approver.js';

export const APPROVER = { ...ELEMENTS, CREATE_TASK: TASKS.CREATE_TASK };

export const approverProject = {
  ...project,
  process: extendedProcess(project.process),
  classes: withApprover(project.classes)
};
