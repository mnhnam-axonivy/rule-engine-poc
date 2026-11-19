/**
 * Guide 05 — the walkthrough. Four chapters, each starting from the project as the tutorial
 * ships it; the notes are in text.js. See runtime/ui/tour.js for what the director `d` can do.
 * The project is the callable of the demo with a second Business Rule Task, see shared/delivery-project.js.
 */
import { DELIVERY, NEW_MODEL } from '../shared/delivery-project.js';
import { errorText } from '../shared/project.js';

const { CREATE_TASK } = DELIVERY;
const DMN = 'dmn:' + NEW_MODEL;
const AT = DMN + '@deliveryDays/'; // a part of the generated table: + rule1, r1.i1
const CELL = DMN + ':deliveryDays.'; // a cell of the generated table: + r1.i1

const resultOf = call => 'deliveryDays = ' + JSON.stringify((call.results || {}).deliveryDays) + '   (version ' + call.version + ')';
const field = key => 'field:' + CREATE_TASK + '.' + key;
/** Only what the new task does: the pricing before it runs as always. */
const created = call => call.elementId === CREATE_TASK;

/** 1 — what the element is told: a new file, which fields go in, which field comes out. */
async function fields(d) {
  await d.open(CREATE_TASK, 'configuration');
  await d.note('create', { target: field('decision'), extra: 600 });
  await d.note('inputs', { target: field('inputs') });
  await d.note('outputs', { target: field('outputs') });
  await d.close();
}

/** 2 — the name of the file: required, and one that no other file has. */
async function name(d) {
  await d.open(CREATE_TASK, 'configuration');
  await d.note('nameField', { target: field('newModel') });
  d.focus(field('newModel'));
  await d.wait(400);
  await d.edit(CREATE_TASK + '.newModel', '');
  await d.note('nameEmpty', { target: field('newModel'), extra: 600 });
  await d.close();
  await d.run({ fast: true, on: { 'rule:failed': ({ call }) => (created(call) ? d.note('noName', { quote: errorText(call) }) : null) } });
  await d.open(CREATE_TASK, 'configuration');
  d.focus(field('newModel'));
  await d.wait(400);
  await d.edit(CREATE_TASK + '.newModel', 'invoice-pricing');
  await d.note('nameTaken', { target: field('newModel'), extra: 600 });
  await d.close();
  await d.run({ fast: true, on: { 'rule:failed': ({ call }) => (created(call) ? d.note('taken', { quote: errorText(call) }) : null) } });
  await d.note('ownName');
}

/** 3 — the run generates the file, then evaluates it. */
async function generate(d) {
  await d.run({
    on: {
      'rule:created': () => d.note('created', { target: 'el:' + CREATE_TASK }),
      'rule:registered': ({ call }) => (created(call) ? d.note('registered') : null),
      'rule:mapped': ({ call }) => (created(call) ? d.note('placeholderResult', { quote: resultOf(call) }) : null)
    }
  });
  await d.tab(DMN);
  await d.note('inputsNamed', { target: DMN + '@inputs' });
  await d.note('table', { target: AT + 'rule1', extra: 600 });
  await d.tab('process');
  await d.open(CREATE_TASK, 'configuration');
  await d.note('selected', { target: field('decision') });
  await d.close();
}

/** 4 — the rule author adds a rule above the placeholder. */
async function rules(d) {
  await d.run({ fast: true });
  await d.tab(DMN);
  await d.note('placeholder', { target: AT + 'rule1' });
  await d.click(AT + 'addRule');
  await d.note('above', { target: AT + 'rule1' });
  d.focus(AT + 'r1.i1');
  await d.edit(CELL + 'r1.i1', '"CH"');
  d.focus(AT + 'r1.o1');
  await d.edit(CELL + 'r1.o1', '2');
  await d.note('written', { target: AT + 'rule1', wait: 2200 });
  await d.tab('process');
  await d.run({
    fast: true,
    on: {
      'rule:registered': ({ call }) => (created(call) ? d.note('saved') : null),
      'rule:mapped': ({ call }) => (created(call) ? d.note('decided', { quote: resultOf(call) }) : null)
    }
  });
}

export const tour = {
  chapters: [
    { id: 'fields', play: fields },
    { id: 'name', play: name },
    { id: 'generate', play: generate },
    { id: 'rules', play: rules }
  ]
};
