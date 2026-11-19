/**
 * Guide 03 — Set up and run your first decision.
 *
 * Steps 3 and 4 of the page and its trouble shooting, in the simulated Designer: the demo process
 * runs its first decision element by element, the runtime shows what it holds afterwards, and a
 * stopped runtime throws the error the page explains. Steps 1 and 2 happen on the learner's machine.
 */
import { project } from '../shared/project.js';
import { strings } from './text.js';
import { tour } from './tour.js';

export default {
  id: 'guide-03',
  ...project,
  strings,
  tour
};
