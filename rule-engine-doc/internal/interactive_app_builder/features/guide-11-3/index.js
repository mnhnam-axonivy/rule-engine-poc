/**
 * Guide 11, part 3 — the questions and answers about rules that change.
 *
 * One chapter per question of the part "When rules change" of the page Frequently asked questions,
 * on the invoice discount of the demo project with its Portal: whether the process has to change,
 * an input added in the Portal, a changed file that does not decide, a roll back, and a runtime
 * that is down.
 */
import { portalProject } from '../shared/project.js';
import { strings } from './text.js';
import { tour } from './tour.js';

export default {
  id: 'guide-11-3',
  ...portalProject,
  strings,
  tour
};
