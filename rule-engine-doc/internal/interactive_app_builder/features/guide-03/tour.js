import { ELEMENTS, inputsText, ruleText, discountText, invoiceText, errorText } from '../shared/project.js';

const { START, SUM, RULE_TASK, APPLY, BOUNDARY, NOT_PRICED } = ELEMENTS;

async function run(d) {
  await d.run({
    on: {
      ['enter:' + START]: () => d.note('called', { target: 'el:' + START }),
      ['enter:' + SUM]: () => d.note('sum', { target: 'el:' + SUM }),
      ['enter:' + RULE_TASK]: () => d.note('task', { target: 'el:' + RULE_TASK }),
      'rule:request': ({ call }) => d.note('request', { quote: inputsText(call) }),
      'rule:registered': () => d.note('registered'),
      'rule:response': ({ call }) => d.note('response', { quote: ruleText(call) }),
      'rule:mapped': ({ call }) => d.note('mapped', { quote: discountText(call) }),
      ['after:' + APPLY]: async () => {
        await d.note('applied', { target: 'el:' + APPLY, quote: invoiceText(d.app.data.invoice) });
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
