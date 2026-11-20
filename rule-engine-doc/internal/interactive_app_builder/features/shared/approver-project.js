/**
 * What the lesson "Create a new DMN from scratch" simulates: the callable InvoicePricing of the demo, extended the
 * way the page describes it (see approver.js). Both are built from the real files of rule-engine-demo, so the rest
 * of the process is the one of the demo.
 */
import { project, ELEMENTS } from './project.js';
import { APPROVER as TASKS, extendedProcess, withApprover } from './approver.js';

export { NEW_MODEL, INPUT_FIELDS, OUTPUT_FIELDS } from './approver.js';

/** Element ids: those of the demo, and the new Business Rule Task. */
export const APPROVER = { ...ELEMENTS, CREATE_TASK: TASKS.CREATE_TASK };

export const approverProject = {
  ...project,
  process: extendedProcess(project.process),
  classes: withApprover(project.classes)
};
