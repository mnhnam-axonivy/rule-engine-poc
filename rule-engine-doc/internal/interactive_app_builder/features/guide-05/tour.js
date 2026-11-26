import { APPROVER, NEW_MODEL } from '../shared/approver-project.js';
import { MODEL, errorText } from '../shared/project.js';

const { CREATE_TASK } = APPROVER;
const DMN = 'dmn:' + NEW_MODEL;
const AT = DMN + '@approver/';
const CELL = DMN + ':approver.';

const resultOf = call => 'approver = ' + JSON.stringify((call.results || {}).approver) + '   (version ' + call.version + ')';
const field = key => 'field:' + CREATE_TASK + '.' + key;
const created = call => call.elementId === CREATE_TASK;

async function fields(d) {
  await d.open(CREATE_TASK, 'configuration');
  await d.note('create', { target: field('decision'), extra: 600 });
  await d.note('inputs', { target: field('inputs') });
  await d.note('outputs', { target: field('outputs') });
  await d.close();
}

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
  await d.edit(CREATE_TASK + '.newModel', MODEL);
  await d.note('nameTaken', { target: field('newModel'), extra: 600 });
  await d.close();
  await d.run({ fast: true, on: { 'rule:failed': ({ call }) => (created(call) ? d.note('taken', { quote: errorText(call) }) : null) } });
  await d.note('ownName');
}

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

async function rules(d) {
  await d.run({ fast: true });
  await d.tab(DMN);
  await d.note('placeholder', { target: AT + 'rule1' });
  await d.click(AT + 'addRule');
  await d.note('above', { target: AT + 'rule1' });
  d.focus(AT + 'r1.i1');
  await d.edit(CELL + 'r1.i1', '>= 5000');
  d.focus(AT + 'r1.o1');
  await d.edit(CELL + 'r1.o1', '"Sales manager"');
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
