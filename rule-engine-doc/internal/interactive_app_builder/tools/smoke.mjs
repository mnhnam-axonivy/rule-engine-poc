/**
 * Browser check of the built walkthroughs inside the (previewed) tutorial.
 *
 *   npm run smoke            opens the tutorial in headless Chrome/Edge, reads every page, plays
 *                            every walkthrough from its button, then uses the simulator like a
 *                            learner, and reports pass/fail
 *   npm run smoke -- --url=<link of the process start "Rule Engine Tutorial">
 *                            the same against the page an Axon Ivy Engine serves, with its theme
 *
 * Every request that leaves the server of the page is blocked and counted as a failure: the
 * tutorial must work offline. Screenshots of each stage land in .preview/ (.preview/engine/
 * with --url).
 * Set CHROME_PATH if the browser is not found automatically.
 */
import puppeteer from 'puppeteer-core';
import { existsSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { start } from '../preview/server.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const engineUrl = (process.argv.find(arg => arg.startsWith('--url=')) || '').slice('--url='.length);
const shots = resolve(here, engineUrl ? '../.preview/engine' : '../.preview');
const PACE = 6; // the walkthrough plays this much faster than a learner sees it

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

mkdirSync(shots, { recursive: true });
const failures = [];
let checks = 0;
const check = (ok, what) => {
  checks++;
  console.log((ok ? '  ok    ' : '  FAIL  ') + what);
  if (!ok) failures.push(what);
};

const server = engineUrl ? { url: engineUrl, close: async () => {} } : await start(0);
const origin = new URL(server.url).origin;
const browser = await puppeteer.launch({
  executablePath: browserPath,
  headless: true,
  args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--ignore-gpu-blocklist', '--hide-scrollbars', '--force-color-profile=srgb']
});

/** Opens the tutorial; collects console problems and blocks anything that is not the preview server. */
async function openTutorial(query = '', viewport = { width: 1440, height: 900 }) {
  const page = await browser.newPage();
  await page.setViewport({ ...viewport, deviceScaleFactor: 1 });
  const problems = [];
  const external = [];
  page.on('console', m => { if (['error', 'warning'].includes(m.type())) problems.push(m.type() + ': ' + m.text()); });
  page.on('pageerror', e => problems.push('pageerror: ' + e.message));
  await page.setRequestInterception(true);
  page.on('request', request => {
    const url = request.url();
    if (url.startsWith(origin) || url.startsWith('data:') || url.startsWith('blob:')) request.continue();
    else { external.push(url); request.abort(); }
  });
  await page.goto(server.url + query, { waitUntil: 'load' });
  await page.waitForFunction(() => typeof window.showPage === 'function' && window.reInteractive, { timeout: 30000 });
  return { page, problems, external };
}

/** Runs `fn(app, ...args)` in the page against the mounted simulation. */
const withApp = (page, fn, ...args) => page.evaluate((source, params) => {
  const handle = window.reInteractive.instances()[0];
  return new Function('app', 'handle', 'args', 'return (' + source + ')(app, handle, ...args)')(handle.app, handle, params);
}, fn.toString(), args);

const waitForStatus = (page, statuses, timeout = 45000) => page.waitForFunction(
  wanted => { const h = window.reInteractive.instances()[0]; return h && wanted.includes(h.app.state.status); },
  { timeout, polling: 100 }, statuses);

const settle = (page, ms = 350) => new Promise(r => setTimeout(r, ms));

/** Screenshot of the walkthrough dialog, or of the visible page when it is closed. */
async function shot(page, name) {
  const clip = await page.evaluate(() => {
    const target = document.querySelector('.re-walkthrough') || document.documentElement;
    const r = target === document.documentElement ? { left: 0, top: 0, width: window.innerWidth, height: window.innerHeight } : target.getBoundingClientRect();
    return { x: Math.round(r.left + window.scrollX), y: Math.round(r.top + window.scrollY), width: Math.round(r.width), height: Math.round(r.height) };
  });
  await page.screenshot({ path: join(shots, name + '.png'), clip, captureBeyondViewport: false });
}

async function showPage(page, id) {
  await page.evaluate((pace, pageId) => {
    window.reInteractiveOptions = { ...(window.reInteractiveOptions || {}), tourPace: pace };
    window.showPage(pageId);
  }, PACE, id);
  await page.waitForSelector('#re-article h1', { timeout: 10000 });
}

/**
 * Opens the walkthrough dialog with a button of the page, by default the one next to the title;
 * the walkthrough starts at once.
 */
async function openWalkthrough(page, button = '#re-article .re-title-row .re-walkthrough-btn') {
  await page.click(button);
  await page.waitForSelector('.re-walkthrough .re-interactive-host', { timeout: 10000 });
  await page.waitForFunction(() => { const h = window.reInteractive.instances()[0]; return h && h.app.stage.width > 0; }, { timeout: 10000 });
  // record every note the walkthrough shows, in order
  await page.evaluate(() => {
    const tour = window.reInteractive.instances()[0].app.tour;
    window.__notes = [];
    window.__noteTimer = setInterval(() => {
      const key = tour.current && tour.current.key;
      if (key && window.__notes[window.__notes.length - 1] !== key) window.__notes.push(key);
    }, 15);
  });
}

/** Waits until the walkthrough shows the given note, and pauses it there. */
async function atNote(page, key) {
  await page.waitForFunction(k => {
    const tour = window.reInteractive.instances()[0].app.tour;
    if (!tour.current || tour.current.key !== k) return false;
    tour.setPaused(true);
    return true;
  }, { timeout: 90000, polling: 20 }, key);
  await settle(page, 400);
}

const resume = page => page.evaluate(() => window.reInteractive.instances()[0].app.tour.setPaused(false));

const waitForEnd = page => page.waitForFunction(
  () => window.reInteractive.instances()[0].app.tour.state === 'done', { timeout: 240000, polling: 100 });

const inApp = (page, selector) => page.evaluate(sel => {
  const el = window.reInteractive.instances()[0].host.shadowRoot.querySelector(sel);
  if (!el) throw new Error('not found in simulation: ' + sel);
  el.click();
}, selector);

/** The ### questions of the n-th ## part of the open page. */
const questionsOfPart = (page, n) => page.evaluate(part => {
  const out = [];
  let index = 0;
  document.querySelectorAll('#re-article h2, #re-article h3').forEach(heading => {
    if (heading.tagName === 'H2') index++;
    else if (index === part) out.push(heading.textContent);
  });
  return out;
}, n);

/**
 * Plays the walkthrough of a page from its button to its end.
 * @param {string} id        page id, e.g. '04', or page and part, e.g. '11-3' for the walkthrough
 *                           next to the third ## heading of page 11
 * @param {string[]} stops   notes to stop at for a screenshot, in the order they appear
 * @param {string[]} wanted  notes that must have been shown
 * @param {(page, key: string) => Promise<void>} [atStop]  looks at the page at every stop
 */
async function playWalkthrough(id, stops, wanted, atStop = null) {
  console.log('\nGuide ' + id + ': walkthrough');
  const [pageId, part] = id.split('-');
  const { page, problems, external } = await openTutorial();
  await showPage(page, pageId);
  const button = part ? '#re-article .re-part-row .re-walkthrough-btn[data-walkthrough="guide-' + id + '"]' : '#re-article .re-title-row .re-walkthrough-btn';
  check(Boolean(await page.$(button)), 'page ' + pageId + ' has a Walkthrough button next to ' + (part ? 'the heading of part ' + part : 'its title'));
  const questions = part ? await questionsOfPart(page, Number(part)) : null;
  await openWalkthrough(page, button);
  if (questions) {
    const titles = await withApp(page, app => app.tour.chapters.map(chapter => app.t('tour.chapter.' + chapter.id)));
    check(titles.length === questions.length && titles.every((title, i) => title === questions[i]),
      'one chapter per section of part ' + part + ', in their order: ' + titles.length + ' of ' + questions.length);
  }
  let n = 0;
  for (const key of stops) {
    await atNote(page, key);
    await shot(page, id + '-' + String(++n).padStart(2, '0') + '-' + key);
    if (atStop) await atStop(page, key);
    await resume(page);
  }
  await waitForEnd(page);
  const notes = await page.evaluate(() => { clearInterval(window.__noteTimer); return window.__notes; });
  const missing = wanted.filter(key => !notes.includes(key));
  check(missing.length === 0, 'every chapter played (' + notes.length + ' notes)' + (missing.length ? ', missing: ' + missing.join(', ') : ''));
  const chapters = await withApp(page, app => app.tour.chapters.length);
  check(chapters >= 3, chapters + ' chapters');
  check(problems.length === 0, 'no console errors or warnings' + (problems.length ? ': ' + problems.slice(0, 3).join(' | ') : ''));
  check(external.length === 0, 'no request left the page' + (external.length ? ': ' + external[0] : ''));
  return page;
}

/** Closes the end card of the walkthrough, which leaves the editable simulator. */
async function explore(page) {
  await inApp(page, '.tour-card .btn, .tour-card button');
  await settle(page, 500);
  return withApp(page, app => app.mode);
}

// ── The path and the pages ───────────────────────────────────────────────────

const PAGE_IDS = ['01', '02', '03', '04', '05', '06', '07', '08', '09', '10', '11'];
/** The pages with a walkthrough next to their title. */
const TITLE_WALKTHROUGHS = ['01', '02', '03', '04', '05', '06', '07', '08'];
/** The pages in parts: their ## headings, and the walkthroughs of the parts that have one, by the number of the part. */
const PARTS = {
  '11': { headings: ['Setup', 'Mapping and fields', 'When rules change', 'Errors'], walkthroughs: { 3: 'guide-11-3' } }
};

console.log('Tutorial: the path and the pages');
{
  const { page, problems, external } = await openTutorial();
  const steps = await page.$$eval('#re-steps .re-step', rows => rows.map(row => ({
    page: row.getAttribute('data-page'),
    title: (row.querySelector('.re-step-title') || {}).textContent,
    sections: [...row.querySelectorAll('.re-step-section')].map(item => item.getAttribute('data-section')),
    chapterPlays: [...row.querySelectorAll('.re-step-chapter-play')].map(button => button.getAttribute('data-walkthrough') + '#' + button.getAttribute('data-chapter')),
    play: row.querySelector('.re-step-play') ? row.querySelector('.re-step-play').getAttribute('data-walkthrough') : null,
    duration: (row.querySelector('.re-step-duration') || {}).textContent || '',
    read: Boolean(row.querySelector('.re-step-read'))
  })));
  const levels = await page.$$eval('#re-steps .re-level', nodes => nodes.map(node => node.textContent));
  check(steps.map(step => step.page).join() === PAGE_IDS.join(), 'the overview is the path of the ' + steps.length + ' pages, in order: ' + steps.map(step => step.title).join(' · '));
  check(levels.join() === 'Basics,First steps,Changing rules,Production,Expert,Questions and answers', 'grouped from easy to hard: ' + levels.join(' › '));
  check(!await page.$('.re-card') && !await page.$('#re-walkthrough-list'), 'no step cards and no separate list of walkthroughs any more');
  const withPlay = steps.filter(step => step.play).map(step => step.page + ':' + step.play);
  check(withPlay.join() === TITLE_WALKTHROUGHS.map(id => id + ':guide-' + id).concat('11:guide-11-3').join(), 'a step with a walkthrough has Play: ' + withPlay.join(', '));
  check(steps.every(step => step.read && step.sections.length >= 3 && !step.sections.includes('See also') && !step.sections.includes('What comes next')),
    'each step lists the sections of its page, and links to the page');
  const s04 = steps.find(step => step.page === '04');
  check(s04.chapterPlays.join() === 'guide-04#0,guide-04#1,guide-04#2,guide-04#3,guide-04#4' && s04.duration === 'about 5 min',
    'every chapter of a walkthrough has a ▶ on its section: ' + s04.chapterPlays.join(', '));
  const s11 = steps.find(step => step.page === '11');
  check(s11.sections.join() === PARTS['11'].headings.join() && s11.chapterPlays.join() === 'guide-11-3#0', 'the questions list their groups, and the group with a walkthrough has a ▶');
  check(steps.filter(step => !step.play).map(step => step.page).join() === '09,10', 'the expert pages are to read: ' + steps.filter(step => !step.play).map(step => step.title).join(', '));
  await shot(page, '00-overview');
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await settle(page, 200);
  await shot(page, '00-overview-end');

  // a section opens its page right there
  const section = '[data-page="04"] [data-section="What is sent when a field is empty?"]';
  await page.click('#re-steps ' + section + ' .re-step-section-link');
  await settle(page, 300);
  const opened = await page.evaluate(() => {
    const heading = [...document.querySelectorAll('#re-article h2')].find(h => h.textContent === 'What is sent when a field is empty?');
    return { page: window.reTutorial.current(), top: heading ? Math.round(heading.getBoundingClientRect().top) : null };
  });
  check(opened.page === '04' && opened.top !== null && opened.top >= 0 && opened.top < 120, 'a section opens its page at that section (heading at ' + opened.top + ' px)');
  await page.evaluate(() => window.showIndex());

  // ▶ on a section plays the walkthrough from that chapter, Play from the start
  await page.click('#re-steps ' + section + ' .re-step-chapter-play');
  await page.waitForSelector('.re-walkthrough .re-interactive-host', { timeout: 10000 });
  await page.waitForFunction(() => { const h = window.reInteractive.instances()[0]; return h && h.app.tour.state === 'playing'; }, { timeout: 10000 });
  const fromChapter = await page.evaluate(() => ({
    app: document.querySelector('.re-walkthrough .re-interactive-host').getAttribute('data-app'),
    index: window.reInteractive.instances()[0].app.tour.index
  }));
  check(fromChapter.app === 'guide-04' && fromChapter.index === 2, '▶ on a section plays its walkthrough from that chapter: ' + fromChapter.app + ', chapter ' + (fromChapter.index + 1));
  await page.evaluate(() => window.closeWalkthrough());
  await page.click('#re-steps [data-page="06"] .re-step-play');
  await page.waitForSelector('.re-walkthrough .re-interactive-host', { timeout: 10000 });
  const playing = await page.evaluate(() => ({
    title: document.querySelector('.re-walkthrough-title').textContent,
    app: document.querySelector('.re-walkthrough .re-interactive-host').getAttribute('data-app')
  }));
  check(playing.app === 'guide-06' && playing.title === 'Change rules without deployment — Walkthrough', 'Play starts the walkthrough of the step at once: ' + playing.title);
  await page.evaluate(() => window.closeWalkthrough());

  for (const id of PAGE_IDS) {
    await showPage(page, id);
    const info = await page.evaluate(() => ({
      title: document.querySelector('#re-article h1').textContent,
      text: document.querySelector('#re-article').textContent,
      sections: [...document.querySelectorAll('#re-article h2')].map(h => h.textContent),
      questions: [...document.querySelectorAll('#re-article h3')].map(h => h.textContent),
      titleButton: document.querySelector('#re-article .re-title-row:not(.re-part-row) .re-walkthrough-btn'),
      partButtons: [...document.querySelectorAll('#re-article .re-part-row .re-walkthrough-btn')].map(b => b.getAttribute('data-walkthrough')),
      toc: document.querySelectorAll('#re-toc ul a').length,
      code: document.querySelectorAll('#re-article pre code.hljs').length,
      tables: document.querySelectorAll('#re-article table').length,
      links: [...document.querySelectorAll('#re-article .re-page-link')].length,
      pager: [...document.querySelectorAll('.re-pager-title')].map(node => node.textContent),
      unresolved: (document.querySelector('#re-article').textContent.match(/\[[A-Z][^\]\n]+\](?!\()/g) || [])
    }));
    info.titleButton = info.titleButton ? await page.$eval('#re-article .re-title-row:not(.re-part-row) .re-walkthrough-btn', b => b.getAttribute('data-walkthrough')) : null;
    const parts = PARTS[id];
    if (parts) {
      check(parts.headings.every((heading, i) => info.sections[i] === heading), 'page ' + id + ' "' + info.title + '" has ' + parts.headings.length + ' parts: ' + info.sections.join(' | '));
      check(info.toc === info.sections.length + info.questions.length, 'the contents list the parts and, below each part, its sections (' + info.toc + ' links)');
      check(!info.titleButton && info.partButtons.join() === Object.values(parts.walkthroughs).join(), 'a part with a walkthrough has its button next to its heading: ' + info.partButtons.join(', '));
      await shot(page, id + '-parts');
    } else {
      check(info.sections.length >= 4 && info.toc === info.sections.length, 'page ' + id + ' "' + info.title + '": ' + info.sections.length + ' sections, all in the contents');
      check(info.titleButton === (TITLE_WALKTHROUGHS.includes(id) ? 'guide-' + id : null), 'page ' + id + (info.titleButton ? ' has its walkthrough next to its title' : ' is to read, without a walkthrough'));
    }
    check(info.tables > 0 && info.links > 0 && info.pager.length > 0 && info.unresolved.length === 0,
      'page ' + id + ': tables, links to other pages, a pager' + (info.unresolved.length ? ', but unresolved: ' + info.unresolved.join(' ') : ' (' + info.pager.join(' / ') + ')'));
    if (id === '01') {
      check(['Read a decision table', 'Try other inputs', 'First match wins', 'Conditions', 'Add a rule', 'Several decisions in one model'].every(s => info.sections.includes(s)),
        'page 01 explains DMN: the table, other inputs, the hit policy, conditions, a new rule, several decisions');
      await shot(page, '01-what-is-dmn');
    }
    if (id === '02') {
      check(['How the parts fit together', 'The Ivy projects', 'The decision runtime container', 'A decision, block by block'].every(s => info.sections.includes(s)),
        'page 02 explains the architecture: the parts, the projects, the container, a decision block by block');
      check(info.text.includes('Axon Ivy Portal'), 'page 02 shows where the Portal fits in');
    }
    if (id === '03') {
      check(info.sections.includes('Prerequisites') && info.sections.some(s => s.startsWith('Step 1')) && info.sections.includes('If it does not work'), 'page 03 has the prerequisites, the setup steps and what to do if it does not work');
      check(info.code >= 4, 'page 03 shows its commands as highlighted code (' + info.code + ' blocks)');
      await shot(page, '03-setup');
    }
    if (id === '04') {
      check(info.text.includes('Watch out: the same name twice on one level') && info.sections.includes('How do I prepare a value for the decision?'),
        'page 04 has the search for a field and the Script that prepares a value');
    }
    if (id === '07') {
      check(['Before: no rule knows the customer', 'Rule Management in the Portal', 'Add an input and a rule',
        'After: the same invoice costs less', 'Versions and roll back', 'Who has the last word'].every(s => info.sections.includes(s)),
      'page 07 has a section for every chapter of its walkthrough');
      await shot(page, '07-portal');
    }
    if (id === '11') {
      const changes = await questionsOfPart(page, 3);
      check(info.questions.length >= 12 && changes.length === 5 && info.questions.every(q => q.endsWith('?')), 'page 11 answers ' + info.questions.length + ' questions, 5 of them about rules that change');
    }
  }
  await showPage(page, '04');
  await shot(page, '04-00-page-with-button');
  await page.click('#re-article .re-page-link');
  check(await page.evaluate(() => window.reTutorial.current()) !== '04', 'a link to another page opens that page');
  check(problems.length === 0, 'no console errors or warnings' + (problems.length ? ': ' + problems.slice(0, 3).join(' | ') : ''));
  check(external.length === 0, 'no request left the page' + (external.length ? ': ' + external[0] : ''));
  await page.close();
}

// ── Guide 01: What is DMN? — a decision table and "Try it" ──────────────────
{
  const seen = {};
  const lookAtTable = async (page, key) => {
    seen[key] = await page.evaluate(() => {
      const handle = window.reInteractive.instances()[0];
      const editor = handle.app.dmnEditors['invoice-pricing'];
      const table = editor.querySelector('[data-decision="lineDiscountTable"]');
      return {
        tab: handle.app.state.tab,
        result: (editor.querySelector('[data-tour="tryResult"]') || {}).textContent || '',
        hits: [...table.querySelectorAll('tr.hit')].map(row => row.getAttribute('data-tour')).join(),
        rules: table.querySelectorAll('tr[data-tour^="rule"]').length
      };
    });
  };
  const page = await playWalkthrough('01',
    ['laptops', 'gold', 'volume', 'several', 'belowRange', 'nextRange', 'nothing', 'written', 'newResult'],
    ['table', 'rule', 'output', 'dash', 'function', 'tryStrip', 'laptops', 'gold', 'volume', 'several', 'policy', 'order', 'catchAll',
      'text', 'list', 'range', 'compare', 'belowRange', 'nextRange', 'nothing', 'added', 'written', 'newResult', 'whereEdit'],
    lookAtTable);
  check(seen.laptops.tab === 'dmn:invoice-pricing' && /"percent":6,.*decided by rule 6$/.test(seen.laptops.result) && seen.laptops.hits === 'rule6',
    'it plays on the table lineDiscountTable: 10 laptops for a Silver customer light up rule 6: ' + seen.laptops.result);
  check(/"percent":10,.*decided by rule 5$/.test(seen.gold.result) && /"percent":12,.*decided by rule 4$/.test(seen.volume.result),
    'other facts, other rules: ' + seen.gold.result + ' | ' + seen.volume.result);
  check(seen.several.hits === 'rule1' && /Fixed book price.*decided by rule 1$/.test(seen.several.result), 'first match wins: ' + seen.several.result);
  check(/decided by rule 8$/.test(seen.belowRange.result) && /"percent":15,.*decided by rule 7$/.test(seen.nextRange.result), 'the ranges meet without a gap: ' + seen.nextRange.result);
  check(/decided by rule 10$/.test(seen.nothing.result) && seen.written.rules === 11 && /"percent":4,.*decided by rule 10$/.test(seen.newResult.result),
    'a new rule above the one that catches everything decides: ' + seen.newResult.result);
  check(await explore(page) === 'explore', 'after the walkthrough the simulator is editable');

  console.log('Guide 01: "Try it", used like a learner');
  await inApp(page, '[data-tab="dmn:invoice-pricing"]');
  await settle(page);
  const tryIt = (values, cell) => withApp(page, (app, handle, typed, change) => {
    Object.keys(typed).forEach(name => {
      const box = app.dmnControls['invoice-pricing:try.' + name];
      box.setValue(typed[name]);
      box.dispatchEvent(new Event('input'));
    });
    if (change) {
      const input = app.dmnControls['invoice-pricing:lineDiscountTable.' + change[0]];
      input.value = change[1];
      input.dispatchEvent(new Event('input'));
    }
    const table = app.dmnEditors['invoice-pricing'].querySelector('[data-decision="lineDiscountTable"]');
    return {
      result: app.dmnEditors['invoice-pricing'].querySelector('[data-tour="tryResult"]').textContent,
      hits: [...table.querySelectorAll('tr.hit')].map(row => row.getAttribute('data-tour')).join()
    };
  }, values, cell || null);
  let tried = await tryIt({ category: '"Hardware"', quantity: '10', customerTier: 'Silver', country: 'CH' });
  check(/"percent":6,.*decided by rule 6$/.test(tried.result) && tried.hits === 'rule6', 'a learner types the facts, quotes or not: ' + tried.result);
  await shot(page, '01-90-try-it');
  tried = await tryIt({}, ['r6.o1', '7']);
  check(/"percent":7,.*decided by rule 6$/.test(tried.result), 'an edited result is answered at once: ' + tried.result);
  tried = await tryIt({ category: '', quantity: '', customerTier: '', country: '' });
  check(/No values yet/.test(tried.result), 'without values, nothing is evaluated');
  check(await withApp(page, app => app.runtime.modelNames().length) === 0, '"Try it" sends nothing to the runtime');
  await page.close();
}

// ── Guide 02: How the rule engine works — the architecture ───────────────────
{
  // at a stop on the tab "Architecture": what is lit, and whether the note keeps clear of the blocks
  const seen = [];
  const lookAtArchitecture = async (page, key) => {
    seen.push(await page.evaluate(k => {
      const handle = window.reInteractive.instances()[0];
      const root = handle.host.shadowRoot;
      const note = root.querySelector('.tour-note').getBoundingClientRect();
      const blocks = [...root.querySelectorAll('.arch-diagram .arch-block, .arch-diagram .arch-frame')];
      const hits = (a, b) => a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
      return {
        key: k,
        tab: handle.app.state.tab,
        lit: [...root.querySelectorAll('.arch-diagram .lit[data-arch]')].map(node => node.dataset.arch),
        flowing: root.querySelectorAll('.arch-line.flow').length,
        covered: handle.app.state.tab === 'arch' ? blocks.filter(block => hits(note, block.getBoundingClientRect())).map(block => block.dataset.arch) : []
      };
    }, key));
  };
  const page = await playWalkthrough('02',
    ['ivy', 'http', 'demo', 'shared', 'portal', 'image', 'db', 'editor', 'flowRegister', 'flowStored', 'flowDecide', 'flowResult', 'flowWritten', 'designer'],
    ['ivy', 'container', 'http', 'why', 'demo', 'connector', 'core', 'shared', 'requires', 'portal', 'image', 'api', 'engine', 'db', 'editor',
      'flowProcess', 'flowConnector', 'flowRegister', 'flowStored', 'flowEvaluate', 'flowDecide', 'flowResult', 'flowWritten', 'designer'],
    lookAtArchitecture);
  const at = key => seen.find(stop => stop.key === key) || { lit: [], covered: [] };
  const onArch = seen.filter(stop => stop.tab === 'arch');
  check(onArch.length === 13 && at('designer').tab === 'process', 'the walkthrough plays on the tab Architecture and ends on the process canvas');
  check(at('portal').lit.includes('portal') && at('portal').lit.includes('connector') && at('portal').flowing === 1,
    'it shows the Portal, and how it calls the connector');
  check(at('ivy').lit.join() === 'ivy' && at('demo').lit.join() === 'demo' && at('image').lit.join() === 'container', 'it lights the block it talks about: the engine, a project, the container');
  check(at('shared').lit.includes('core') && at('shared').lit.includes('shared') && at('db').lit.includes('volume') && at('editor').lit.includes('browser'),
    'and what belongs together: the shared code on both sides, database and volume, editor page and browser');
  check(at('http').flowing === 2 && at('flowRegister').flowing === 1 && at('flowResult').flowing === 1, 'the link between engine and container flows in the direction of the call');
  const covering = onArch.filter(stop => stop.covered.length);
  check(covering.length === 0, 'no note covers a block of the diagram' + (covering.length ? ': ' + covering.map(stop => stop.key + ' over ' + stop.covered.join('+')).join(', ') : ''));
  await shot(page, '02-90-end');
  check(await explore(page) === 'explore', 'after the walkthrough the simulator is editable');

  console.log('Guide 02: the tab Architecture, used like a learner');
  const arch = () => page.evaluate(() => {
    const root = window.reInteractive.instances()[0].host.shadowRoot;
    const text = id => { const node = root.querySelector('[data-arch="' + id + '"] .arch-live'); return node ? node.textContent : ''; };
    return {
      blocks: [...root.querySelectorAll('.arch-diagram [data-arch]')].map(node => node.dataset.arch).sort().join(),
      links: [...root.querySelectorAll('.arch-link')].filter(link => (link.querySelector('.arch-wire').getAttribute('d') || '').length > 10).map(link => link.dataset.link).sort().join(),
      db: text('db'), api: text('api'), engine: text('engine'), demo: text('demo'),
      state: root.querySelector('.arch-state').textContent,
      stopped: root.querySelector('.arch-container').classList.contains('stopped'),
      broken: root.querySelector('.arch-link[data-link="http"]').classList.contains('broken')
    };
  });
  await inApp(page, '[data-tab="arch"]');
  await settle(page, 400);
  let view = await arch();
  check(view.blocks === 'api,browser,callable,code,connector,container,core,db,demo,editor,engine,http,ivy,portal,shared,store,ui,volume', 'the diagram has the engine with the Portal and three projects, the container with what is in its image, browser and volume');
  check(view.links === 'callable,code,http,store,ui', 'and the five links between them');
  check(view.db === 'no model yet' && view.state === 'running' && /invoice-pricing\.dmn/.test(view.demo), 'it starts with an empty database: ' + view.db);
  const panelShown = () => page.evaluate(() => getComputedStyle(window.reInteractive.instances()[0].host.shadowRoot.querySelector('.panel')).display !== 'none');
  check(!await panelShown(), 'the tab has no bottom panel');
  // the details of a block: a drawer on the right, that changes with the block and closes again
  const drawer = () => page.evaluate(() => {
    const root = window.reInteractive.instances()[0].host.shadowRoot;
    const details = root.querySelector('.arch-details');
    const r = details.getBoundingClientRect();
    const tab = root.querySelector('.editor-arch').getBoundingClientRect();
    const selected = root.querySelector('.arch-diagram .selected');
    const s = selected ? selected.getBoundingClientRect() : null;
    return {
      open: !details.hidden && r.width > 0,
      right: Math.abs(r.right - tab.right) < 2 && r.left > tab.left + tab.width / 2 && Math.abs(r.height - tab.height) < 2,
      covers: Boolean(s && s.right > r.left),
      selected: selected ? selected.dataset.arch : null,
      title: (root.querySelector('.arch-details h4') || {}).textContent || '',
      text: details.textContent
    };
  });
  await inApp(page, '[data-arch="connector"]');
  await settle(page, 200);
  let side = await drawer();
  check(side.open && side.right && !side.covers && /BusinessRuleTask/.test(side.text) && /rule-engine-core/.test(side.text),
    'selecting a block shows what is in it in a drawer on the right, beside the block');
  await shot(page, '02-91-architecture');
  await inApp(page, '[data-arch="db"]');
  side = await drawer();
  check(side.open && side.selected === 'db' && side.title === 'Database' && !/BusinessRuleTask/.test(side.text), 'selecting another block changes the details: ' + side.title);
  await inApp(page, '.arch-details-close');
  side = await drawer();
  check(!side.open && !side.selected, 'the close button closes the details');
  await inApp(page, '[data-arch="api"]');
  await inApp(page, '.arch-scroll');
  side = await drawer();
  check(!side.open && !side.selected, 'a click beside the blocks closes the details');
  await inApp(page, '[data-arch="volume"]');
  await page.evaluate(() => window.reInteractive.instances()[0].host.shadowRoot.querySelector('[data-arch="volume"]').focus());
  await page.keyboard.press('Escape');
  side = await drawer();
  check(!side.open && await page.$('.re-walkthrough') !== null, 'Escape closes the details, the walkthrough dialog stays open');
  await page.setViewport({ width: 1000, height: 760, deviceScaleFactor: 1 });
  await settle(page, 300);
  await inApp(page, '[data-arch="editor"]');
  await settle(page, 200);
  side = await drawer();
  check(side.open && side.right, 'on a narrow screen the details lie over the diagram, on the right');
  await shot(page, '02-91-architecture-narrow');
  await inApp(page, '.arch-details-close');
  await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 1 });
  await settle(page, 300);
  await inApp(page, '[data-tab="process"]');
  check(await panelShown(), 'the other tabs keep their bottom panel');
  await inApp(page, '.run-btn.primary');
  await waitForStatus(page, ['completed', 'failed']);
  await inApp(page, '[data-tab="arch"]');
  await settle(page, 400);
  view = await arch();
  check(/invoice-pricing: version 1 active \(project\), 1 stored/.test(view.db) && /last answer: 7 results, version 1/.test(view.api) && /every decision of invoice-pricing/.test(view.engine),
    'after a run it shows what happened: ' + view.db + ' | ' + view.api + ' | ' + view.engine);
  await shot(page, '02-92-after-run');
  await withApp(page, app => app.setRuntimeState('stopped'));
  view = await arch();
  check(view.state === 'stopped' && view.stopped && view.broken, 'a stopped container is shown as stopped, and the link to it as broken');
  await shot(page, '02-93-stopped');
  await page.close();
}

// ── Guides 03 and 04: the first decision, and an existing DMN decision ───────
/** At a stop: the quote of the note, the open tab, the last call, the log and what the elements say. */
const lookAround = seen => async (page, key) => {
  seen[key] = await page.evaluate(() => {
    const app = window.reInteractive.instances()[0].app;
    const quote = app.tour.current && app.tour.current.quote;
    const call = app.panel.call || {};
    const task = app.model.elements.find(e => e.id === 'f40');
    return {
      tab: app.state.tab,
      quote: Array.isArray(quote) ? quote.map(line => line.text).join('\n') : String(quote || ''),
      sent: Object.fromEntries((call.mappings || []).map(m => [m.name, m.value])),
      files: app.dmnFiles.map(file => file.id).join(),
      createDecision: task ? (task.config.userConfig || {}).decision : null,
      data: JSON.parse(JSON.stringify(app.data || {})),
      logs: app.panel.logs.map(entry => entry.message).slice(-3),
      statuses: Object.fromEntries(['invoice-pricing', 'delivery-days'].map(model => [model, app.runtime.versions(model).map(v => v.version + ':' + v.status + ':' + v.source).join(' ')]))
    };
  });
};

{
  // page 03: the first decision, what the runtime holds, a runtime that does not answer
  let seen = {};
  let page = await playWalkthrough('03',
    ['request', 'response', 'mapped', 'where', 'versionOne', 'lastCall', 'stored', 'unreachable', 'notPriced'],
    ['called', 'prepare', 'task', 'request', 'registered', 'response', 'mapped', 'log', 'where', 'versionOne', 'lastCall', 'stored',
      'stopped', 'unreachable', 'caught', 'notPriced'],
    lookAround(seen));
  check(/customerTier = "Silver"/.test(seen.request.quote) && /lines = \[5 lines\]/.test(seen.request.quote), 'the first run sends the seven inputs, the lines as a list: ' + seen.request.quote.replace(/\n/g, ', '));
  check(/line 1: rule 6 · Hardware: 10 units or more/.test(seen.response.quote) && /line 5: rule 2 · Books: 10 copies or more/.test(seen.response.quote), 'a rule decides each line: ' + seen.response.quote.split('\n')[0]);
  check(/total 18265\.55 · Sales manager\s+\(version 1\)/.test(seen.mapped.quote), 'and the results come back: ' + seen.mapped.quote.split('\n').pop());
  check(/^1:ACTIVE:PROJECT$/.test(seen.versionOne.statuses['invoice-pricing']), 'the runtime holds version 1, active, from the project: ' + seen.versionOne.statuses['invoice-pricing']);
  check(seen.stored.tab === 'arch', 'the version is shown in the database of the container');
  check(/rule:engine:unreachable/.test(seen.unreachable.quote) && seen.notPriced.tab === 'process', 'a stopped runtime: rule:engine:unreachable, caught, path "not priced"');
  await shot(page, '03-90-end');
  check(await explore(page) === 'explore', 'after the walkthrough the simulator is editable');

  console.log('Guide 03: the simulator, used like a learner');
  await inApp(page, '.run-btn.primary');
  await waitForStatus(page, ['completed', 'failed']);
  const first = await withApp(page, app => ({
    status: app.state.status, total: app.data.pricing.totals.total, lines: app.data.pricing.pricedLines.length,
    version: app.runtime.versions('invoice-pricing').find(v => v.status === 'ACTIVE').version, logs: app.panel.logs.map(l => l.message)
  }));
  check(first.status === 'completed' && first.total === 18265.55 && first.lines === 5 && first.version === 1, 'Run: the invoice is priced, 18265.55, by version 1');
  check(first.logs.includes('Invoice INV-2026-0042: total 18265.55, Sales manager'), 'the Runtime Log has the line of the tutorial');
  await inApp(page, '.panel-tab[data-tab="data"]');
  await settle(page, 200);
  check(await page.evaluate(() => window.reInteractive.instances()[0].host.shadowRoot.querySelectorAll('.data-list li').length) >= 10, 'the process data shows the lines and the priced lines, one per row');
  await shot(page, '03-91-process-data');

  // change a rule in the table of the file, the way a learner types into a cell
  await inApp(page, '[data-tab="dmn:invoice-pricing"]');
  await settle(page);
  await withApp(page, app => {
    const cell = app.dmnControls['invoice-pricing:lineDiscountTable.r6.o1'];
    cell.value = '9';
    cell.dispatchEvent(new Event('input'));
  });
  const changedMark = await page.evaluate(() => Boolean(window.reInteractive.instances()[0].host.shadowRoot.querySelector('[data-tab="dmn:invoice-pricing"] .tab-mark.changed')));
  check(changedMark, 'an edited table marks its tab as changed');
  await shot(page, '03-92-table-edited');
  await inApp(page, '[data-tab="process"]');
  await inApp(page, '.run-btn.primary');
  await waitForStatus(page, ['completed', 'failed']);
  const second = await withApp(page, app => ({ laptop: app.data.pricing.pricedLines[0].discountPercent, version: app.runtime.versions('invoice-pricing').find(v => v.status === 'ACTIVE').version, versions: app.runtime.versions('invoice-pricing').map(v => v.status) }));
  check(second.laptop === 9 && second.version === 2 && second.versions.join() === 'RETIRED,ACTIVE', 'the changed file is version 2 and decides the next run: 9 % on the laptops');
  await shot(page, '03-93-after-edit');

  // stop the runtime with the switch of the panel: the boundary event catches the error
  await inApp(page, '.panel-tab[data-tab="runtime"]');
  await inApp(page, '.rt-switch-btn.stopped');
  await inApp(page, '.run-btn.primary');
  await waitForStatus(page, ['completed', 'failed']);
  const stopped = await withApp(page, app => ({ status: app.state.status, code: app.data.pricing.errorCode, executed: Object.keys(app.visual.executed) }));
  check(stopped.status === 'completed' && stopped.code === 'rule:engine:unreachable' && stopped.executed.includes('f10'), 'runtime stopped: rule:engine:unreachable is caught, the process ends at "not priced"');
  await shot(page, '03-94-runtime-stopped');

  // Reset puts everything back
  await inApp(page, '.rt-switch-btn.running');
  await page.evaluate(() => window.reInteractive.instances()[0].app.reset());
  const reset = await withApp(page, app => ({ versions: app.runtime.modelNames().length, cell: app.dmnControls['invoice-pricing:lineDiscountTable.r6.o1'].value, state: app.runtime.state }));
  check(reset.versions === 0 && reset.cell === '6' && reset.state === 'running', 'Reset: the runtime is empty and the file is the one of the tutorial again');

  // the dialog closes with Escape and releases the simulation
  await page.keyboard.press('Escape');
  await settle(page, 300);
  check(await page.evaluate(() => !document.querySelector('.re-walkthrough') && window.reInteractive.instances().length === 0), 'Escape closes the walkthrough and disposes the simulation');
  await page.close();

  // page 04: the one field, mapping by name, null, and a Script that prepares a value
  seen = {};
  page = await playWalkthrough('04',
    ['decisionField', 'enough', 'levelZero', 'levelOne', 'foundIn', 'resultsBack', 'sentNull', 'refused', 'dashMatches', 'newInput', 'mappingError', 'caught', 'typed', 'preparedSent', 'preparedResult'],
    ['decisionField', 'fieldsEmpty', 'enough', 'namesToFind', 'levelZero', 'levelOne', 'foundIn', 'resultsBack', 'closestWins', 'sameLevel', 'caseAndBlanks',
      'noTier', 'sentNull', 'refused', 'allowedValues', 'noPromo', 'dashMatches', 'noMatch', 'newInput', 'mappingError', 'caught', 'waysOut',
      'scriptBefore', 'trimUpper', 'typed', 'preparedSent', 'preparedResult', 'otherName'],
    lookAround(seen));
  check(/total 18265\.55 · Sales manager/.test(seen.enough.quote), 'with only the decision selected, the invoice is priced: ' + seen.enough.quote.split('\n').pop());
  check(seen.levelZero.tab === 'process' && seen.levelZero.data.invoice && seen.levelZero.data.invoice.customerTier === 'Silver',
    'the search is shown on the process data before the call: the invoice of the page');
  check(seen.foundIn.sent.customerTier === 'Silver' && seen.foundIn.sent.country === 'CH' && seen.foundIn.sent.lines.length === 5, 'all seven inputs are found one level down, in in.invoice');
  check(seen.resultsBack.data.pricing && seen.resultsBack.data.pricing.pricedLines.length === 5, 'the results are written to in.pricing by name');
  check(seen.sentNull.sent.customerTier === null && 'customerTier' in seen.sentNull.sent, 'an empty field is sent as null');
  check(/not allowed by the declared type \(tCustomerTier\)/.test(seen.refused.quote), 'null is not one of the allowed values: ' + seen.refused.quote.split('\n')[0]);
  check(/line 1: Hardware 10 × 1200 · 6 %/.test(seen.dashMatches.quote) && /orderDiscounts = none/.test(seen.dashMatches.quote), 'without a promo code the rules with - decide: ' + seen.dashMatches.quote.split('\n')[0]);
  check(/rule:engine:mapping[\s\S]*creditLimit[\s\S]*of the model 'invoice-pricing'[\s\S]*set before the Business Rule Task/.test(seen.mappingError.quote),
    'an input without a field throws rule:engine:mapping, names it and says where to set it');
  check(seen.preparedSent.sent.promoCode === 'WINTER26', 'the Script before the element prepares the value that is sent: ' + seen.preparedSent.sent.promoCode);
  check(/Promotion WINTER26 5 %/.test(seen.preparedResult.quote), 'and the promotion applies: ' + seen.preparedResult.quote);
  check(await explore(page) === 'explore', 'after the walkthrough the simulator is editable');
  await page.close();
}

// ── Guide 06: Change rules without deployment ────────────────────────────────
{
  const seen = {};
  const page = await playWalkthrough('06',
    ['edited', 'pending', 'live', 'active', 'answerNames', 'rolledBack', 'rollbackFile'],
    ['before', 'edited', 'pending', 'registered', 'live', 'list', 'active', 'retired', 'answerNames', 'activeOne', 'onTheCase',
      'rollbackButton', 'rolledBack', 'rollbackResult', 'rollbackFile'],
    lookAround(seen));
  check(/line 1: Hardware 10 × 1200 · 8 %.*\(version 2\)/.test(seen.live.quote), 'a saved file is live with the next run: ' + seen.live.quote);
  check(/^1:ACTIVE/.test(seen.answerNames.statuses['invoice-pricing']), 'the answer names the active version: ' + seen.answerNames.statuses['invoice-pricing']);
  check(/^1:ACTIVE.* 2:RETIRED/.test(seen.rolledBack.statuses['invoice-pricing']), 'rolled back, version 1 is active again: ' + seen.rolledBack.statuses['invoice-pricing']);
  check(await explore(page) === 'explore', 'after the walkthrough the simulator is editable');

  console.log('Guide 06: a rule owner on the editor page of the runtime, used like a learner');
  await inApp(page, '.run-btn.primary');
  await waitForStatus(page, ['completed', 'failed']);
  await inApp(page, '.panel-tab[data-tab="runtime"]');
  await inApp(page, '[data-tour="owner"]');
  await settle(page);
  await withApp(page, app => {
    const cell = app.ownerDialog.controls['lineDiscountTable.r6.o1'];
    cell.value = '10';
    cell.dispatchEvent(new Event('input'));
  });
  await shot(page, '06-90-owner-dialog');
  await inApp(page, '[data-tour="ownerActivate"]');
  await inApp(page, '.run-btn.primary');
  await waitForStatus(page, ['completed', 'failed']);
  const owned = await withApp(page, app => ({
    laptop: app.data.pricing.pricedLines[0].discountPercent, versions: app.runtime.versions('invoice-pricing').map(v => v.source + ':' + v.status),
    file: app.findDmn('invoice-pricing').functions[0].table.rules[5].outputs[0]
  }));
  check(owned.laptop === 10 && owned.versions.join() === 'PROJECT:RETIRED,PORTAL:ACTIVE' && owned.file === '6', 'the rule owner\'s version decides (10 %), the file of the project still says 6');
  await inApp(page, '[data-tour="rollback"]');
  await inApp(page, '.run-btn.primary');
  await waitForStatus(page, ['completed', 'failed']);
  check(await withApp(page, app => app.data.pricing.pricedLines[0].discountPercent) === 6, 'Roll back: version 1 decides again (6 %)');
  await shot(page, '06-91-rolled-back');
  await page.close();
}

// ── Guide 05: Create a new DMN from scratch ──────────────────────────────────
{
  const seen = {};
  const page = await playWalkthrough('05',
    ['create', 'noName', 'taken', 'created', 'inputsNamed', 'table', 'selected', 'above', 'written', 'decided'],
    ['create', 'inputs', 'outputs', 'nameField', 'nameEmpty', 'noName', 'nameTaken', 'taken', 'ownName', 'created', 'registered', 'placeholderResult', 'inputsNamed', 'table', 'selected',
      'placeholder', 'above', 'written', 'saved', 'decided'],
    lookAround(seen));
  check(/rule:engine:mapping[\s\S]*'New DMN name' is empty/.test(seen.noName.quote) && seen.noName.files === 'invoice-pricing'
    && seen.noName.logs.some(m => /cannot create its new DMN\. 'New DMN name' is empty/.test(m)),
    'without a name nothing is created, and the Runtime Log says why: ' + seen.noName.quote.split('\n')[1]);
  check(/A DMN named 'invoice-pricing' exists already \(invoice-pricing\.dmn\)/.test(seen.taken.quote) && seen.taken.createDecision === '#create',
    'the name of another file is an error, that file is not selected: ' + seen.taken.quote.split('\n')[1]);
  check(seen.inputsNamed.tab === 'dmn:delivery-days' && seen.inputsNamed.files === 'invoice-pricing,delivery-days', 'the new task builds dmn/delivery-days.dmn from its fields');
  check(/deliveryDays = 2\s+\(version 2\)/.test(seen.decided.quote), 'the rule of the author decides: ' + seen.decided.quote);
  check(await explore(page) === 'explore', 'after the walkthrough the simulator is editable');
  const fresh = await withApp(page, app => ({ files: app.dmnFiles.map(f => f.id), decision: app.model.elements.find(e => e.id === 'f40').config.userConfig.decision }));
  check(fresh.files.join() === 'invoice-pricing' && fresh.decision === '#create', 'the project is back to how the tutorial ships it: no generated file');

  console.log('Guide 05: the new task, used like a learner');
  await inApp(page, '.run-btn.primary');
  await waitForStatus(page, ['completed', 'failed']);
  const created = await withApp(page, app => ({
    status: app.state.status, files: app.dmnFiles.map(f => f.id), days: app.data.pricing.deliveryDays, total: app.data.pricing.totals.total,
    tabs: [...app.tabsEl.querySelectorAll('.tab')].map(tab => tab.textContent.trim())
  }));
  check(created.status === 'completed' && created.files.join() === 'invoice-pricing,delivery-days' && created.days === 0 && created.total === 18265.55,
    'a run prices the invoice, generates delivery-days.dmn and evaluates its placeholder rule: 0');
  check(created.tabs.includes('delivery-days.dmn'), 'the generated file has its own editor tab');
  await withApp(page, app => { app.select('f40'); app.setInscriptionOpen(true); });
  await settle(page);
  check(await withApp(page, app => app.model.elements.find(e => e.id === 'f40').config.userConfig.decision) === 'delivery-days#deliveryDays', 'opening the mask again selects the generated decision');
  await shot(page, '05-90-generated-selected');
  await page.close();
}

// ── Guide 08: Handle errors ──────────────────────────────────────────────────
{
  const page = await playWalkthrough('08',
    ['stopped', 'cut', 'thrown', 'caught', 'code', 'narrow', 'unhandled', 'off', 'notLoaded', 'timeoutSet', 'timedOut'],
    ['running', 'stopped', 'cut', 'thrown', 'caught', 'otherPath', 'logged', 'code', 'output', 'narrow', 'unhandled', 'unhandledLog', 'autoDeploy', 'off', 'notLoaded', 'refused',
      'timeoutVar', 'timeoutSet', 'slow', 'timedOut', 'sameEvent']);
  check(await explore(page) === 'explore', 'after the walkthrough the simulator is editable');

  console.log('Guide 08: wrong URL in variables.yaml, used like a learner');
  await inApp(page, '[data-tab="variables"]');
  await withApp(page, app => app.setVariables(app.variablesText.replace('localhost:8090', 'localhost:8080')));
  await settle(page);
  const side = await page.evaluate(() => window.reInteractive.instances()[0].host.shadowRoot.querySelector('.editor-variables .var-effect').textContent);
  check(/rule:engine:unreachable/.test(side), 'a wrong URL in variables.yaml is explained next to the file');
  await shot(page, '08-90-wrong-url');
  await inApp(page, '[data-tab="process"]');
  await inApp(page, '.run-btn.primary');
  await waitForStatus(page, ['completed', 'failed']);
  check(await withApp(page, app => app.data.pricing.errorCode) === 'rule:engine:unreachable', 'and the run throws rule:engine:unreachable');
  await page.close();
}

// ── Guide 07: Edit rules in the Portal ───────────────────────────────────────
{
  const seen = {};
  const lookAroundPortal = async (page, key) => {
    seen[key] = await page.evaluate(() => {
      const app = window.reInteractive.instances()[0].app;
      const portal = app.portal;
      const title = portal && portal.root ? (portal.root.querySelector('[data-tour="title"]') || {}).textContent || '' : '';
      const decision = portal && portal.edit ? portal.edit.dmn.decisions.find(d => d.name === 'orderDiscounts') : null;
      const table = decision ? decision.table : null;
      const quote = app.tour.current && app.tour.current.quote;
      return {
        tab: app.state.tab,
        title,
        columns: table ? table.inputs.map(input => input.label).join() : '',
        rules: table ? table.rules.length : 0,
        quote: Array.isArray(quote) ? quote.map(line => line.text).join('\n') : String(quote || '')
      };
    });
  };
  const page = await playWalkthrough('07',
    ['noKeyAccount', 'customer', 'bridge', 'row', 'editor', 'inputs', 'keyAccount', 'collect', 'saved', 'moreInputs', 'applied', 'untouched', 'versionList', 'rolledBack', 'backAgain',
      'fileChanged', 'stillPortal', 'draftBeside'],
    ['noKeyAccount', 'table', 'customer', 'bridge', 'tab', 'row', 'editor', 'goal', 'inputs', 'newRule', 'keyAccount', 'collect', 'checked', 'saved',
      'moreInputs', 'applied', 'untouched', 'versionList', 'rolledBack', 'backAgain', 'portalActive', 'fileChanged', 'asDraft', 'stillPortal', 'draftBeside'],
    lookAroundPortal);
  check(/orderDiscounts = none · applied 0 %.*\(version 1\)/.test(seen.noKeyAccount.quote), 'before: no order discount for the sample invoice: ' + seen.noKeyAccount.quote);
  check(seen.bridge.tab === 'arch' && seen.row.tab === 'portal', 'it shows how the Portal finds the rule engine, then the Portal itself');
  check(seen.inputs.columns === 'customerTier,paymentTerms,promoCode,invoiceDate,customer', 'the table gets the input customer in the Portal: ' + seen.inputs.columns);
  check(seen.keyAccount.rules === 7 && /Version 2 · Active/.test(seen.saved.title), 'one more rule, saved and activated as version 2: ' + seen.saved.title);
  check(/customer = "Bluebird Architects"/.test(seen.moreInputs.quote), 'after: the Business Rule Task sends the new input: ' + seen.moreInputs.quote.split('\n').pop());
  check(/Key account 3 % · applied 3 % · total 17717\.58\s+\(version 2\)/.test(seen.applied.quote), 'and the same invoice costs less: ' + seen.applied.quote);
  check(/none · applied 0 %.*\(version 1\)/.test(seen.backAgain.quote), 'rolled back, no key account discount any more');
  check(/line 1: Hardware 10 × 1200 · 6 %[\s\S]*Key account 3 %/.test(seen.stillPortal.quote), 'who has the last word: a changed file of the project does not replace the rule owner\'s version');
  check(await explore(page) === 'explore', 'after the walkthrough the simulator is editable');

  console.log('Guide 07: the Portal, used like a learner');
  const portal = () => page.evaluate(() => {
    const shadow = window.reInteractive.instances()[0].host.shadowRoot;
    const root = shadow.querySelector('.portal');
    const text = selector => { const node = root.querySelector(selector); return node ? node.textContent.replace(/\s+/g, ' ').trim() : ''; };
    return {
      row: text('[data-tour="row:invoice-pricing"]'),
      message: text('.portal-messages'),
      title: text('[data-tour="title"]'),
      panelShown: getComputedStyle(shadow.querySelector('.panel')).display !== 'none'
    };
  });
  await inApp(page, '[data-tab="process"]');
  await inApp(page, '.run-btn.primary');
  await waitForStatus(page, ['completed', 'failed']);
  check(await withApp(page, app => app.data.pricing.totals.total) === 18265.55, 'a run of the process prices the invoice: 18265.55');
  await inApp(page, '[data-tab="portal"]');
  await settle(page, 300);
  let view = await portal();
  check(/invoice-pricing/.test(view.row) && /Version 1/.test(view.row) && !view.panelShown, 'the tab Portal lists the rules at version 1, without the bottom panel');
  await inApp(page, '[data-tour="edit:invoice-pricing"]');
  await settle(page, 300);
  await page.evaluate(() => {
    const cell = window.reInteractive.instances()[0].host.shadowRoot.querySelector('.portal [data-decision="orderDiscounts"] [data-tour="r1.i1"] input');
    cell.value = '"Gold", "Silver"';
    cell.dispatchEvent(new Event('input'));
  });
  await inApp(page, '[data-tour="activate"]');
  await settle(page, 300);
  view = await portal();
  check(/Version 2 of invoice-pricing is saved and active/.test(view.message) && /Version 2/.test(view.title), 'a learner gives the tier bonus to Silver customers too and saves it: ' + view.message);
  await shot(page, '07-90-portal-learner');
  await inApp(page, '[data-tab="process"]');
  await inApp(page, '.run-btn.primary');
  await waitForStatus(page, ['completed', 'failed']);
  check(await withApp(page, app => app.data.pricing.orderDiscountPercent) === 2, 'and the same process gives the same invoice 2 percent');
  check(await withApp(page, app => app.findDmn('invoice-pricing').decisions[0].table.rules[0].inputs[0]) === '"Gold"', 'the file of the project still says Gold');
  await page.close();
}

// ── Guide 11: Frequently asked questions ─────────────────────────────────────
{
  // when rules change
  const seen = {};
  const page = await playWalkthrough('11-3',
    ['before', 'after', 'sameProcess', 'inputsSent', 'portalDecides', 'stillPortal', 'draftBeside', 'rolledBack', 'backAgain', 'unreachable', 'notPriced'],
    ['before', 'ruleChanged', 'newVersion', 'after', 'sameProcess', 'portalVersion', 'inputsSent', 'portalDecides', 'unchanged', 'withoutField',
      'portalActive', 'fileChanged', 'asDraft', 'stillPortal', 'draftBeside', 'versionList', 'rollbackButton', 'rolledBack', 'backAgain',
      'stopped', 'unreachable', 'boundary', 'notPriced'],
    lookAround(seen));
  check(/· 6 %.*\(version 1\)/.test(seen.before.quote), 'before: 6 % on the laptops: ' + seen.before.quote);
  check(/· 8 %.*\(version 2\)/.test(seen.after.quote), 'a changed rule decides as version 2, the process unchanged: ' + seen.after.quote);
  check(seen.inputsSent.sent.customer === 'Bluebird Architects', 'the input added in the Portal is sent: ' + seen.inputsSent.sent.customer);
  check(/Key account 3 %.*\(version 2\)/.test(seen.portalDecides.quote), 'and the version of the Portal decides: ' + seen.portalDecides.quote);
  check(/Key account 3 %/.test(seen.stillPortal.quote), 'a changed file does not replace the version of the Portal');
  check(/3:DRAFT:PROJECT/.test(seen.draftBeside.statuses['invoice-pricing']), 'the changed file is version 3, a draft: ' + seen.draftBeside.statuses['invoice-pricing']);
  check(/1:ACTIVE/.test(seen.rolledBack.statuses['invoice-pricing']), 'rolled back in the Portal, version 1 is active: ' + seen.rolledBack.statuses['invoice-pricing']);
  check(/none · applied 0 %.*\(version 1\)/.test(seen.backAgain.quote), 'and the next run is decided by it: ' + seen.backAgain.quote);
  check(/rule:engine:unreachable/.test(seen.unreachable.quote) && seen.notPriced.tab === 'process', 'a stopped runtime: rule:engine:unreachable, caught, path "not priced"');
  check(await explore(page) === 'explore', 'after the walkthrough the simulator is editable');
  await page.close();
}

// ── The 2D fallback and a narrow window ──────────────────────────────────────
if (engineUrl) {
  console.log('');
  console.log('Fallback renderer: not checked with --url, the preview switches it on with ?renderer=canvas');
} else {
  console.log('\nFallback renderer, narrow window');
  const { page, problems } = await openTutorial('?renderer=canvas', { width: 820, height: 760 });
  await showPage(page, '03');
  await openWalkthrough(page);
  await atNote(page, 'request');
  await shot(page, '99-fallback-narrow');
  await resume(page);
  await atNote(page, 'mapped');
  await resume(page);
  check(problems.length === 0, 'the walkthrough also plays without WebGL, in a narrow window' + (problems.length ? ': ' + problems.slice(0, 2).join(' | ') : ''));
  await page.close();
}

await browser.close();
await server.close();
console.log('\n' + (checks - failures.length) + ' of ' + checks + ' checks passed. Screenshots: ' + shots);
if (failures.length) {
  console.log('Failed:\n' + failures.map(f => '  - ' + f).join('\n'));
  process.exit(1);
}
