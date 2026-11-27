/**
 * Browser check of the DMN editor dialog of the connector (RuleEditor) on a running Axon Ivy Engine whose DMN engine
 * container runs too:
 *
 *   npm run smoke -- --url=http://localhost:8080/~Developer-<workspace>/Developer-<workspace>/ [--model=discount]
 *
 * It opens the process start RuleEditor/editRule.ivp of the project rule-engine for a model (default: discount, the
 * small model of rule-engine-test) and uses the dialog like a person would: change a rule in the KIE editor, let the
 * DMN engine check it, save it as a draft (never live), a model the DMN engine rejects. Then it opens the dialog in an
 * iframe, as the Portal does, and closes it: the parent page must hear of it. Every request outside the engine is
 * blocked and counted as a failure. Screenshots land in .preview/. Set CHROME_PATH if no browser is found.
 */
import puppeteer from 'puppeteer-core';
import { existsSync, mkdirSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const shots = resolve(here, '.preview');
const arg = name => (process.argv.find(a => a.startsWith('--' + name + '=')) || '').slice(name.length + 3);
/** process/RuleEditor.p.json of the project rule-engine */
const START = 'pro/rule-engine/023F72FE11C582F1/editRule.ivp';

const browserPath = process.env.CHROME_PATH || [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser'
].find(existsSync);
if (!browserPath) {
  console.error('No Chrome or Edge found. Set CHROME_PATH to a Chromium-based browser.');
  process.exit(2);
}

rmSync(shots, { recursive: true, force: true });
mkdirSync(shots, { recursive: true });
const failures = [];
let checks = 0;
const check = (ok, what) => {
  checks++;
  console.log((ok ? '  ok    ' : '  FAIL  ') + what);
  if (!ok) failures.push(what);
};
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ── the engine ───────────────────────────────────────────────────────────────

let base = arg('url');
if (!base) {
  console.error('Give the application of a running engine: --url=http://localhost:8080/~Developer-<workspace>/Developer-<workspace>/');
  process.exit(2);
}
if (!base.endsWith('/')) base += '/';
const origin = new URL(base).origin;
const model = arg('model') || 'discount';
const editorLink = new URL(START + '?model=' + encodeURIComponent(model), base).href;
/** a blank page of the same origin, served by this script: the parent of the iframe */
const parentPage = origin + '/__editor-smoke__/parent.html';

// ── the browser ──────────────────────────────────────────────────────────────

let browser;
const problems = [];
const external = [];

async function openPage() {
  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 1 });
  page.on('pageerror', e => problems.push('pageerror: ' + e.message));
  page.on('console', m => {
    if (m.type() === 'error') problems.push('console: ' + m.text() + ' ' + (m.location().url || ''));
  });
  page.on('dialog', d => d.accept()); // "close the editor anyway?"
  await page.setRequestInterception(true);
  page.on('request', request => {
    const url = request.url();
    if (url === parentPage) request.respond({ status: 200, contentType: 'text/html', body: '<!doctype html><html><head><link rel="icon" href="data:,"></head><body></body></html>' });
    else if (url.startsWith(origin) || /^(data|blob|about):/.test(url)) request.continue();
    else { external.push(url); request.abort(); }
  });
  return page;
}

async function shot(page, name) {
  await page.screenshot({ path: join(shots, name + '.png') });
}

/** The frame that runs the dialog: the page itself, or the iframe the Portal would show it in. */
const dialogOf = page => page.frames().find(f => f.url().includes('RuleEditor')) || page.mainFrame();

/** The content of the KIE editor, or null while it is still starting. */
const content = frame => frame.evaluate(() => {
  const editor = window.RuleEditor && window.RuleEditor.editor();
  if (!editor) return null;
  return Promise.race([editor.getContent().catch(() => null), new Promise(r => setTimeout(() => r(null), 3000))]);
});

async function waitForContent(frame, needle, timeout = 90000) {
  const end = Date.now() + timeout;
  // the dialog says when the editor has started; asking it earlier only produces errors in its frame
  await frame.waitForFunction(() => window.RuleEditor && window.RuleEditor.isReady(), { timeout, polling: 200 });
  while (Date.now() < end) {
    const text = await content(frame);
    if (text && text.includes(needle)) return text;
    await sleep(500);
  }
  return null;
}

/**
 * Changes a cell of the decision table in the KIE editor: select the decision in the diagram, "Edit", click the
 * cell, type, Enter. The selectors are those of the Apache KIE DMN editor 10.2.0.
 */
async function typeIntoTable(page, cellIndex, value) {
  const frame = page.frames().find(f => f.url() === 'about:srcdoc');
  const node = await frame.waitForSelector('.react-flow__node-node_decision', { timeout: 30000 });
  let box = await node.boundingBox();
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  const edit = await frame.waitForFunction(() => [...document.querySelectorAll('*')]
    .find(e => e.children.length === 0 && e.textContent.trim() === 'Edit'), { timeout: 15000 });
  await edit.asElement().click();
  await frame.waitForFunction(i => document.querySelectorAll('td').length > i, { timeout: 15000 }, cellIndex);
  await sleep(500);
  const cell = await frame.evaluateHandle(i => document.querySelectorAll('td')[i], cellIndex);
  box = await cell.asElement().boundingBox();
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  await sleep(300);
  await page.keyboard.type(String(value));
  await sleep(300);
  await page.keyboard.press('Enter');
}

/** Replaces the model in the editor, the way an edit in the table would change it. */
const setModel = (frame, xml) => frame.evaluate((m, x) => window.RuleEditor.editor().setContent(m + '.dmn', x), model, xml);

/** The messages of the dialog, as "severity: text". */
const notices = frame => frame.$$eval('[id$=":notices"] .ui-message', boxes => boxes.map(box => {
  const severity = ['info', 'warn', 'error'].find(s => box.className.includes('ui-message-' + s)) || '?';
  return severity + ': ' + box.textContent.replace(/\s+/g, ' ').trim();
}));

/** Clicks a button of the dialog and waits until it is done: the buttons are enabled again. */
async function act(frame, id) {
  const said = () => [...document.querySelectorAll('[id$=":notices"] .ui-message')].map(n => n.textContent).join('|');
  const before = await frame.evaluate(said);
  await frame.click('[id$=":' + id + '"]');
  await frame.waitForFunction((b, read) => {
    const button = document.querySelector('[id$=":check"]');
    const now = new Function('return (' + read + ')()')();
    return button && !button.disabled && now.length > 0 && now !== b;
  }, { timeout: 30000, polling: 100 }, before, said.toString());
  return notices(frame);
}

const state = frame => frame.$eval('[id$=":state"]', n => n.textContent.trim());
const dirty = frame => frame.$eval('.rule-editor-dirty', n => !n.hidden);

// ── checks ───────────────────────────────────────────────────────────────────

try {
  browser = await puppeteer.launch({ executablePath: browserPath, headless: true, args: ['--hide-scrollbars', '--force-color-profile=srgb'] });

  console.log('\nThe dialog, started by its process: ' + editorLink);
  let page = await openPage();
  await page.goto(editorLink, { waitUntil: 'load' });
  let frame = dialogOf(page);
  const opened = await waitForContent(frame, '<definitions');
  check(Boolean(opened), 'the model ' + model + ' opens in the KIE DMN editor');
  const openedState = await state(frame);
  check(/^Version \d+( · \w+)?$|^File of project/.test(openedState), 'the dialog says what is open: ' + openedState);
  check(await page.title() === 'Rule ' + model, 'the title names the rule: ' + await page.title());
  await sleep(2500); // let the editor draw
  await shot(page, '01-opened');

  console.log('\nChange a rule, check it, save it as a draft');
  const cellText = /<outputEntry id="_rule_1_output_discount">\s*<text>(\d+)</.exec(opened || '');
  const changed = cellText ? String(Number(cellText[1]) + 1) : '21';
  await typeIntoTable(page, 4, changed); // rule 1, column discount
  check(Boolean(await waitForContent(frame, '>' + changed + '</', 20000)), 'a rule changed in the decision table of the KIE editor: ' + (cellText ? cellText[1] : '?') + ' → ' + changed);
  check(await dirty(frame), 'the dialog shows that there are unsaved changes');
  let said = await act(frame, 'check');
  check(said.some(n => n.startsWith('info:') && n.includes('is valid')), 'Check: the DMN engine accepts the model: ' + said.slice(0, 2).join(' | '));
  await shot(page, '02-checked');
  said = await act(frame, 'save-draft');
  check(said.some(n => n.startsWith('info:') && n.includes('saved as draft')), 'Save as draft stores a new version: ' + said[0]);
  check(/^Version \d+ · Draft$/.test(await state(frame)), 'the dialog shows the draft: ' + await state(frame));
  check(!await dirty(frame), 'after saving, nothing is unsaved');
  await shot(page, '03-saved');

  console.log('\nA model the DMN engine rejects');
  await setModel(frame, (await content(frame)).replace(/(<outputEntry id="_rule_1_output_discount">\s*<text>)\d+(<\/text>)/, '$115 +$2'));
  await waitForContent(frame, '15 +', 20000);
  said = await act(frame, 'check');
  check(said[0] && said[0].startsWith('error:') && said[0].includes('has errors') && said.length > 1,
    'Check shows the findings of the DMN engine: ' + said.slice(0, 2).join(' | ').slice(0, 160));
  await shot(page, '04-rejected');
  await page.close();

  console.log('\nIn an iframe, as the Portal shows it');
  page = await openPage();
  await page.goto(parentPage, { waitUntil: 'load' });
  await page.evaluate(link => {
    window.__messages = [];
    window.addEventListener('message', event => window.__messages.push(event.data));
    document.body.innerHTML = '';
    const iframe = document.createElement('iframe');
    iframe.src = link;
    iframe.style.cssText = 'width:1400px;height:860px;border:0';
    document.body.appendChild(iframe);
  }, editorLink);
  await page.waitForFunction(() => document.querySelector('iframe').contentWindow.RuleEditor, { timeout: 30000 });
  frame = dialogOf(page);
  check(Boolean(await waitForContent(frame, '<definitions')), 'the dialog runs in the iframe');
  await shot(page, '05-iframe');
  await frame.click('[id$=":close"]');
  await page.waitForFunction(() => window.__messages.some(m => m && m.type === 'axonivy-rule-editor' && m.event === 'closed'),
    { timeout: 15000, polling: 100 }).catch(() => null);
  const heard = await page.evaluate(() => window.__messages.filter(m => m && m.type === 'axonivy-rule-editor'));
  check(heard.some(m => m.event === 'closed' && m.model === model), 'Close tells the parent page: ' + JSON.stringify(heard));
  await page.close();

  check(problems.length === 0, 'no errors in the browser' + (problems.length ? ': ' + problems.slice(0, 3).join(' | ') : ''));
  check(external.length === 0, 'nothing is loaded from outside the engine' + (external.length ? ': ' + external.slice(0, 3).join(', ') : ''));
} catch (e) {
  failures.push(String(e && e.stack || e));
  console.error(e);
} finally {
  if (browser) await browser.close();
}

console.log('\n' + (checks - failures.length) + ' of ' + checks + ' checks passed. Screenshots: ' + shots);
if (failures.length) {
  console.log('Failed:\n' + failures.map(f => '  - ' + f).join('\n'));
  process.exit(1);
}
