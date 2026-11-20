/**
 * Guide 05 — Create a new DMN from scratch.
 *
 * The lesson of "New DMN...": on the callable of the demo with a second Business Rule Task, the fields are
 * selected, the new DMN gets a name of its own, the file is generated, and a first rule is written into the
 * generated table.
 */
import { approverProject } from '../shared/approver-project.js';
import { strings } from './text.js';
import { tour } from './tour.js';

export default {
  id: 'guide-05',
  ...approverProject,
  strings,
  tour
};
