/**
 * Guide 02 — How the rule engine works.
 *
 * The lesson of the architecture: the blocks of the Rules Engine one by one (the Axon Ivy
 * Engine with its projects, the container with what is in its image) and the way a decision
 * takes between them. It plays on the tab "Architecture" of the simulator.
 */
import { project } from '../shared/project.js';
import { strings } from './text.js';
import { tour } from './tour.js';

export default {
  id: 'guide-02',
  ...project,
  strings,
  tour
};
