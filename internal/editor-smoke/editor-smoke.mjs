/**
 * Browser check of the DMN editor page of the decision runtime (/editor/).
 *
 *   npm run smoke            starts a throwaway runtime container from axonivy/rule-engine-runtime:dev
 *                            (build it first: docker build -t axonivy/rule-engine-runtime:dev rule-engine-runtime), protected by an API key, runs the
 *                            checks in headless Chrome/Edge and removes the container again
 *   npm run smoke -- --url=http://localhost:8090/ [--key=<api key>]
 *                            the same against a runtime that runs already
 *
 * The page is used like a person would: open a .dmn file, change a rule, let the runtime check it (API key prompt),
 * a model the runtime rejects, write the file back, a new model, a browser without file access. The first change is
 * made in the KIE editor itself, like a person does it: select the decision, "Edit", type into a cell. Later changes
 * use the editor's API (setContent). Every request outside the runtime is blocked and counted as a failure.
 * Screenshots land in .preview/. Set CHROME_PATH if no browser is found.
 */
import puppeteer from 'puppeteer-core';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const shots = resolve(here, '.preview');
const downloads = join(shots, 'downloads');
// a small model of one table: the fixture of the connector tests, not the demo
const discountFile = resolve(here, '../../rule-engine-test/dmn/discount.dmn');
const IMAGE = process.env.RULE_ENGINE_IMAGE || 'axonivy/rule-engine-runtime:dev';
const arg = name => (process.argv.find(a => a.startsWith('--' + name + '=')) || '').slice(name.length + 3);

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
mkdirSync(downloads, { recursive: true });
const failures = [];
let checks = 0;
const check = (ok, what) => {
  checks++;
  console.log((ok ? '  ok    ' : '  FAIL  ') + what);
  if (!ok) failures.push(what);
};
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ── the runtime ──────────────────────────────────────────────────────────────

let base = arg('url');
let key = arg('key');
let container = null;
if (!base) {
  key = 'smoke-test-key';
  container = execFileSync('docker', ['run', '-d', '--rm', '-e', 'RULE_ENGINE_API_KEY=' + key, '-p', '127.0.0.1::8080', IMAGE]).toString().trim();
  const port = execFileSync('docker', ['port', container, '8080/tcp']).toString().trim().split(/\r?\n/)[0].split(':').pop();
  base = 'http://127.0.0.1:' + port + '/';
  console.log('Throwaway runtime ' + container.slice(0, 12) + ' on ' + base);
}
if (!base.endsWith('/')) base += '/';
const origin = new URL(base).origin;

async function api(path) {
  const response = await fetch(new URL('api/' + path, base), { headers: key ? { 'X-API-Key': key } : {} });
  const text = await response.text();
  if (!response.ok) throw new Error('GET ' + path + ': ' + response.status + ' ' + text);
  return text ? JSON.parse(text) : null;
}

async function ready() {
  for (let i = 0; i < 90; i++) {
    try {
      if ((await fetch(new URL('q/health/ready', base))).ok) return;
    } catch (e) { /* still starting */ }
    await sleep(1000);
  }
  throw new Error('The runtime at ' + base + ' did not get ready.');
}

const original = readFileSync(discountFile, 'utf8');
const withDiscount = value => original.replace(/(<outputEntry id="_rule_1_output_discount">\s*<text>)15(<\/text>)/, '$1' + value + '$2');

// ── the browser ──────────────────────────────────────────────────────────────

let browser;
const problems = [];
const external = [];

async function openPage({ fakeFiles = false } = {}) {
  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 1 });
  page.on('pageerror', e => problems.push('pageerror: ' + e.message));
  page.on('console', m => {
    // the 401 before the API key is entered is part of the flow
    if (m.type() === 'error' && !(key && /status of 401/.test(m.text()))) problems.push('console: ' + m.text() + ' ' + (m.location().url || ''));
  });
  page.on('dialog', d => d.accept()); // "discard the unsaved changes?"
  await page.setRequestInterception(true);
  page.on('request', request => {
    const url = request.url();
    if (url.startsWith(origin) || /^(data|blob|about):/.test(url)) request.continue();
    else { external.push(url); request.abort(); }
  });
  const cdp = await page.createCDPSession();
  await cdp.send('Browser.setDownloadBehavior', { behavior: 'allow', downloadPath: downloads });
  if (fakeFiles) {
    // a .dmn file on disk, as the File System Access API of Chrome and Edge hands it to the page
    await page.evaluateOnNewDocument(xml => {
      window.__written = null;
      const handle = {
        name: 'discount.dmn',
        getFile: async () => new File([xml], 'discount.dmn', { type: 'application/xml' }),
        requestPermission: async () => 'granted',
        createWritable: async () => {
          let text = '';
          return { write: async t => { text += t; }, close: async () => { window.__written = text; } };
        }
      };
      window.showOpenFilePicker = async () => [handle];
    }, original);
  } else {
    // a browser without the File System Access API (e.g. Firefox): file input and download
    await page.evaluateOnNewDocument(() => { delete window.showOpenFilePicker; delete window.showSaveFilePicker; });
  }
  await page.goto(base + 'editor/', { waitUntil: 'load' });
  return page;
}

async function shot(page, name) {
  await page.screenshot({ path: join(shots, name + '.png') });
}

/** The content of the open editor, or null while the editor is still starting. */
const content = page => page.evaluate(() => {
  const editor = window.ruleEditor && window.ruleEditor.editor;
  if (!editor) return null;
  // while the editor starts, getContent() waits or fails: both mean "not yet"
  return Promise.race([editor.getContent().catch(() => null), new Promise(r => setTimeout(() => r(null), 3000))]);
});

async function waitForContent(page, needle, timeout = 90000) {
  const end = Date.now() + timeout;
  // the page says when the editor has started; asking it earlier only produces errors in its frame
  await page.waitForFunction(() => window.ruleEditor && window.ruleEditor.ready, { timeout, polling: 200 });
  while (Date.now() < end) {
    const text = await content(page);
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
const setModel = (page, xml, path = 'discount.dmn') => page.evaluate((p, x) => window.ruleEditor.editor.setContent(p, x), path, xml);

const message = page => page.$eval('#messages', box => (box.hidden ? '' : box.textContent.replace(/^Close/, '').replace(/\s+/g, ' ').trim()));

/** Clicks a button of the page and waits for the message it shows; enters the API key if the page asks for it. */
async function act(page, selector, kind) {
  await page.$eval('#messages', box => { box.hidden = true; });
  await page.click(selector);
  await page.waitForFunction(k => {
    const box = document.querySelector('#messages');
    return document.querySelector('#key-dialog').open || (!box.hidden && box.classList.contains(k));
  }, { timeout: 30000, polling: 100 }, kind);
  if (await page.$eval('#key-dialog', d => d.open)) {
    check(true, 'the protected runtime asks for its API key');
    await shot(page, '02-api-key');
    await page.type('#key-input', key);
    await page.click('#key-dialog button.primary');
    await page.waitForFunction(k => { const box = document.querySelector('#messages'); return !box.hidden && box.classList.contains(k); },
      { timeout: 30000, polling: 100 }, kind);
  }
  return message(page);
}

// ── checks ───────────────────────────────────────────────────────────────────

try {
  await ready();
  browser = await puppeteer.launch({ executablePath: browserPath, headless: true, args: ['--hide-scrollbars', '--force-color-profile=srgb'] });

  console.log('\nA .dmn file of a project, with file access (Chrome, Edge)');
  let page = await openPage({ fakeFiles: true });
  await page.click('#open-file');
  check(Boolean(await waitForContent(page, '_rule_1_output_discount')), 'the file opens in the KIE DMN editor');
  await sleep(2500); // let the editor draw
  const opened = await page.evaluate(() => ({
    kind: document.querySelector('#doc-kind').textContent,
    model: document.querySelector('#doc-model').value,
    button: document.querySelector('#save-file').textContent
  }));
  check(opened.kind === 'File' && opened.model === 'discount' && opened.button === 'Save file',
    'the model is named like the file, and Save file writes it itself: ' + JSON.stringify(opened));
  await shot(page, '01-opened');

  console.log('\nChange a rule, check it, write it back');
  await typeIntoTable(page, 4, 20); // rule 1, column discount
  check(Boolean(await waitForContent(page, '>20</', 20000)), 'a rule changed in the decision table of the KIE editor: 15 → 20');
  check(await page.$eval('#doc-dirty', n => !n.hidden), 'the page shows that there are unsaved changes');
  check((await act(page, '#check', 'ok')).includes('accepts this model'), 'Check: the runtime accepts the model');
  await shot(page, '03-checked');
  await act(page, '#save-file', 'ok');
  const written = await page.evaluate(() => window.__written);
  check(Boolean(written) && /<(\w+:)?text>20<\/(\w+:)?text>/.test(written), 'Save file writes the changed model back to discount.dmn');
  check(await page.$eval('#doc-dirty', n => n.hidden), 'after saving, nothing is unsaved');

  console.log('\nA model the runtime rejects');
  await setModel(page, withDiscount('15 +'));
  await waitForContent(page, '15 +', 20000);
  const text = await act(page, '#check', 'error');
  check(text.includes('does not accept'), 'Check shows the findings of the DMN engine: ' + text.slice(0, 120));
  await shot(page, '04-rejected');

  console.log('\nA new model, saved as a file');
  await page.evaluate(() => { delete window.showSaveFilePicker; });
  await page.click('#new-model');
  await page.waitForSelector('#name-dialog[open]');
  await page.type('#name-input', 'member-bonus');
  await page.click('#name-dialog button.primary');
  await page.waitForFunction(() => document.querySelector('#doc-kind').textContent === 'New model', { timeout: 15000 });
  await page.waitForFunction(() => window.ruleEditor.ready, { timeout: 60000, polling: 200 });
  check(await page.$eval('#save-file', b => !b.disabled && b.textContent === 'Save as file…'), 'the buttons work once the editor has started');
  await setModel(page, original.replace(/name="discount" namespace/, 'name="member-bonus" namespace'), 'member-bonus.dmn');
  await waitForContent(page, 'member-bonus', 20000);
  await act(page, '#save-file', 'info');
  let file = null;
  for (let i = 0; i < 20 && !file; i++) {
    await sleep(250);
    file = readdirSync(downloads).find(name => name === 'member-bonus.dmn');
  }
  check(Boolean(file) && readFileSync(join(downloads, file), 'utf8').includes('member-bonus'), 'Save as file… downloads member-bonus.dmn');
  await shot(page, '05-new-model');
  await page.close();

  console.log('\nA .dmn file of a project, without file access: open with a file input, save as download');
  page = await openPage();
  const [chooser] = await Promise.all([page.waitForFileChooser(), page.click('#open-file')]);
  await chooser.accept([discountFile]);
  check(Boolean(await waitForContent(page, '_rule_1_output_discount')), 'the file opens in the editor');
  check(await page.$eval('#save-file', n => n.textContent) === 'Save as file…', 'the button says it downloads the file');
  await act(page, '#save-file', 'info');
  file = null;
  for (let i = 0; i < 20 && !file; i++) {
    await sleep(250);
    file = readdirSync(downloads).find(name => name === 'discount.dmn');
  }
  check(Boolean(file) && readFileSync(join(downloads, file), 'utf8').includes('_rule_1_output_discount'), 'Save as file… downloads discount.dmn');
  await page.close();

  check((await api('models')).length === 0, 'the page stores nothing on the runtime');
  check(problems.length === 0, 'no errors in the browser' + (problems.length ? ': ' + problems.slice(0, 3).join(' | ') : ''));
  check(external.length === 0, 'nothing is loaded from outside the runtime' + (external.length ? ': ' + external.slice(0, 3).join(', ') : ''));
} catch (e) {
  failures.push(String(e && e.stack || e));
  console.error(e);
} finally {
  if (browser) await browser.close();
  if (container) execFileSync('docker', ['stop', container]);
}

console.log('\n' + (checks - failures.length) + ' of ' + checks + ' checks passed. Screenshots: ' + shots);
if (failures.length) {
  console.log('Failed:\n' + failures.map(f => '  - ' + f).join('\n'));
  process.exit(1);
}
