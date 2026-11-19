/**
 * Guide 06 — Change rules without deployment.
 *
 * The lesson of versions: a saved file is live with the next evaluation, the runtime keeps
 * every version, the case knows which one decided, and a rollback is one call.
 */
import { project } from '../shared/project.js';
import { strings } from './text.js';
import { tour } from './tour.js';

export default {
  id: 'guide-06',
  ...project,
  strings,
  tour
};
