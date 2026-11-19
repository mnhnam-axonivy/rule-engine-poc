/**
 * Guide 08 — the walkthrough. Four chapters, each starting from the project as the tutorial
 * ships it; the notes are in text.js. See runtime/ui/tour.js for what the director `d` can do.
 */
import { ELEMENTS } from '../shared/project.js';

const { RULE_TASK, BOUNDARY, NOT_PRICED } = ELEMENTS;

const errorOf = call => call.errorCode + '\n' + call.error;

/** Shows the Runtime Log with its newest line, then closes the panel again. */
async function showLog(d, note) {
  await d.panel('log');
  await d.note(note, { target: 'log:last' });
  await d.panel(null);
}

/** 1 — the container is down: the element throws, the boundary event catches. */
async function unreachable(d) {
  await d.panel('runtime');
  await d.note('running', { target: 'runtime:state' });
  await d.runtime('stopped');
  await d.note('stopped', { target: 'runtime:state' });
  await d.panel(null);
  // what that means for the parts: the engine is fine, the container is gone
  await d.tab('arch');
  d.arch({ lit: ['connector', 'container', 'http'] });
  await d.note('cut', { target: 'arch:http' });
  d.arch(null);
  await d.tab('process');
  await d.run({
    on: {
      'rule:failed': ({ call }) => d.note('thrown', { quote: errorOf(call) }),
      ['enter:' + BOUNDARY]: () => d.note('caught', { target: 'el:' + BOUNDARY }),
      ['enter:' + NOT_PRICED]: () => d.note('otherPath', { target: 'el:' + NOT_PRICED })
    }
  });
  await showLog(d, 'logged');
}

/** 2 — what the boundary event catches, and what happens when it does not fit. */
async function catchError(d) {
  const field = 'field:' + BOUNDARY + '.errorCode';
  await d.open(BOUNDARY, 'error');
  await d.note('code', { target: field });
  await d.open(BOUNDARY, 'output');
  await d.note('output', { target: 'field:' + BOUNDARY + '.code' });
  await d.open(BOUNDARY, 'error');
  d.focus(field);
  await d.edit(BOUNDARY + '.errorCode', 'rule:engine:timeout');
  await d.note('narrow', { target: field, wait: 2200 });
  await d.close();
  await d.runtime('stopped');
  await d.run({ fast: true });
  await d.note('unhandled', { target: 'el:' + RULE_TASK });
  await showLog(d, 'unhandledLog');
}

/** 3 — a project that does not bring its models along. */
async function missing(d) {
  await d.tab('variables');
  await d.note('autoDeploy', { target: 'vars@AutoDeploy' });
  d.focus('vars@AutoDeploy');
  await d.edit('vars', text => text.replace('AutoDeploy: "true"', 'AutoDeploy: "false"'));
  await d.note('off', { target: 'side', wait: 2200 });
  await d.tab('process');
  await d.run({
    fast: true,
    on: { 'rule:failed': ({ call }) => d.note('notLoaded', { quote: errorOf(call) }) }
  });
  await d.note('refused', { target: 'el:' + BOUNDARY });
}

/** 4 — a runtime that answers too late. */
async function timeout(d) {
  await d.tab('variables');
  await d.note('timeoutVar', { target: 'vars@TimeoutSeconds' });
  d.focus('vars@TimeoutSeconds');
  await d.edit('vars', text => text.replace('TimeoutSeconds: "10"', 'TimeoutSeconds: "2"'));
  await d.note('timeoutSet', { target: 'vars@TimeoutSeconds', wait: 1800 });
  await d.tab('process');
  await d.panel('runtime');
  await d.runtime('slow');
  await d.note('slow', { target: 'runtime:state' });
  await d.panel(null);
  await d.run({
    fast: true,
    on: { 'rule:failed': ({ call }) => d.note('timedOut', { quote: errorOf(call) }) }
  });
  await d.note('sameEvent', { target: 'el:' + BOUNDARY });
}

export const tour = {
  chapters: [
    { id: 'unreachable', play: unreachable },
    { id: 'catch', play: catchError },
    { id: 'missing', play: missing },
    { id: 'timeout', play: timeout }
  ]
};
