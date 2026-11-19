/**
 * Guide 08 — Handle errors.
 *
 * The lesson of the BPMN errors: the runtime is stopped and slowed down, a model is missing,
 * and the Error Boundary Event decides how the process goes on.
 */
import { project } from '../shared/project.js';
import { strings } from './text.js';
import { tour } from './tour.js';

export default {
  id: 'guide-08',
  ...project,
  strings,
  tour
};
