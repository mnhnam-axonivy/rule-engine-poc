/**
 * Guide 03 — the walkthrough. Three chapters, each starting from the project as the tutorial ships
 * it; the notes are in text.js. See runtime/ui/tour.js for what the director `d` can do. The runs
 * play at the normal speed: they are what the page is about.
 */
import { ELEMENTS, inputsText, lineRulesText, pricedText, errorText } from '../shared/project.js';

const { START, PREPARE, RULE_TASK, LOG, BOUNDARY, NOT_PRICED } = ELEMENTS;

/** Step 3: the demo process, element by element, then the parts it went through. */
async function run(d) {
  await d.run({
    on: {
      ['enter:' + START]: () => d.note('called', { target: 'el:' + START }),
      ['enter:' + PREPARE]: () => d.note('prepare', { target: 'el:' + PREPARE }),
      ['enter:' + RULE_TASK]: () => d.note('task', { target: 'el:' + RULE_TASK }),
      'rule:request': ({ call }) => d.note('request', { quote: inputsText(call) }),
      'rule:registered': () => d.note('registered'),
      'rule:response': ({ call }) => d.note('response', { quote: lineRulesText(call) }),
      'rule:mapped': ({ call }) => d.note('mapped', { quote: pricedText(call) }),
      ['after:' + LOG]: async () => {
        await d.panel('log');
        await d.note('log', { target: 'log:last' });
        await d.panel(null);
      }
    }
  });
  await d.tab('arch');
  d.arch({ lit: ['demo', 'connector', 'api', 'engine', 'db'], flow: ['http>', 'http<'] });
  await d.note('where', { target: 'arch:http' });
  d.arch(null);
}

/** Step 4: what the runtime holds after the first run. */
async function holds(d) {
  await d.run({ fast: true });
  await d.panel('runtime');
  await d.note('versionOne', { target: 'runtime:versions', extra: 600 });
  await d.note('lastCall', { target: 'runtime:call', extra: 600 });
  await d.panel(null);
  await d.tab('arch');
  d.arch({ lit: ['db', 'volume'] });
  await d.note('stored', { target: 'arch:db' });
  d.arch(null);
  await d.tab('process');
}

/** If it does not work: the runtime is down. */
async function fails(d) {
  await d.panel('runtime');
  await d.runtime('stopped');
  await d.note('stopped', { target: 'runtime:state' });
  await d.panel(null);
  await d.run({
    on: {
      'rule:failed': ({ call }) => d.note('unreachable', { quote: errorText(call) }),
      ['enter:' + BOUNDARY]: () => d.note('caught', { target: 'el:' + BOUNDARY }),
      ['enter:' + NOT_PRICED]: () => d.note('notPriced', { target: 'el:' + NOT_PRICED })
    }
  });
}

export const tour = {
  chapters: [
    { id: 'run', play: run },
    { id: 'holds', play: holds },
    { id: 'fails', play: fails }
  ]
};
