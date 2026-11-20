/**
 * Guide 01 — What is DMN?
 *
 * A decision table, without any process: the walkthrough plays on the editor of dmn/invoice-discount.dmn,
 * on its table discount. The table is read, tried with other inputs in "Try it", its hit policy explained, its
 * conditions read, and a rule of your own added.
 */
import { project } from '../shared/project.js';
import { strings } from './text.js';
import { tour } from './tour.js';

export default {
  id: 'guide-01',
  ...project,
  strings,
  tour
};
