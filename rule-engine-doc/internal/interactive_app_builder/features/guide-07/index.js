/**
 * Guide 07 — Edit rules in the Portal.
 *
 * The rules of the demo do not know the customer of an invoice. A rule owner adds the input customer and a key
 * account rule in the Axon Ivy Portal, at Admin Settings › Rule Management. The same process prices the same
 * invoice lower: nothing was changed in the project and nothing was deployed. A changed file of the project then
 * does not take the rule owner's place.
 */
import { portalProject } from '../shared/project.js';
import { strings } from './text.js';
import { tour } from './tour.js';

export default {
  id: 'guide-07',
  ...portalProject,
  strings,
  tour
};
