/**
 * The walkthrough: the simulator plays a lesson by itself, like an explainer video. It runs
 * the process, opens the inscription view, types changes and switches tabs, and shows one
 * note at a time next to the thing the note explains. Afterwards the learner is left with
 * the editable simulator.
 *
 * A feature defines the lesson as chapters:
 *   tour: { chapters: [{ id, play: async d => { … } }] }
 * with the texts `tour.intro.*`, `tour.chapter.<id>` and `tour.<note>.title` / `.text` in its
 * strings. Every chapter opens with a title screen showing only its name, which should be the
 * heading of the tutorial section it belongs to, so learners can find it in the text. `d` is the director handed to `play`:
 *
 *   d.note(key, { target, params, quote, wait, extra })
 *                                                  show a note and wait until it has been read;
 *                                                  quote: text, or lines [{ text, wrong }] where
 *                                                  the wrong ones are shown in red and flagged;
 *                                                  extra: more time than the text alone needs,
 *                                                  for a note whose target has to be read too
 *   d.focus(target) / d.clear()                    spotlight something without a note
 *   d.run({ fast, on, start })                     run the process; `on` hooks into the run,
 *                                                  `start` names the start element to begin at
 *   d.stop()                                       end the run from inside one of its hooks
 *   d.reset()                                      back to the project as the tutorial ships it
 *   d.open(elementId, tab) / d.close()             inscription view of an element
 *   d.panel('log' | 'runtime' | 'data' | null)     bottom panel
 *   d.tab('process' | 'dmn:<model>' | 'variables' | 'arch')
 *                                                  editor tab: the process, a .dmn file,
 *                                                  variables.yaml, the architecture
 *   d.arch({ lit, flow, label } | null)            on the tab "Architecture": light blocks
 *                                                  (['connector', 'engine']), let links flow
 *                                                  (['http>'] to the DMN engine, ['http<'] back,
 *                                                  'ui>', 'store>', 'code') and quote on a
 *                                                  link ({ http: 'POST …' }); null ends it
 *   d.edit(target, value | old => value)           type a change: 'f4.inputs', 'f2.code', 'vars',
 *                                                  'dmn:discount:discount.r1.o1' (a cell of a
 *                                                  decision table: file, decision, rule, input
 *                                                  or output column), 'owner:discount.r1.o1'
 *                                                  (the same in the rule owner's dialog).
 *                                                  The spotlight goes to the characters that
 *                                                  change, then they are typed one by one
 *   d.runtime(state)                               'running' | 'slow' | 'stopped': the container
 *   d.activate(model, version) / d.rollback(model) what a rule owner does on the runtime
 *   d.owner(model) / d.ownerActivate()             open the rule owner's dialog on the active
 *                                                  version; activate what was changed in it
 *   d.tab('portal'), d.click('portal@<part>')      the Portal at Admin Settings › Rule Management:
 *                                                  press a button of it, e.g. 'portal@edit:order-approval',
 *                                                  'portal@addRule', 'portal@check', 'portal@activate'
 *   d.portalInput(name, type)                      a new input of the model in the Portal's editor
 *   d.edit('portal:route.r2.i1', value)            type into a cell of the Portal's editor, or into
 *                                                  'portal:description' (What changed?)
 *   d.wait(ms)
 *
 * Targets of notes and spotlights:
 *   'el:f4'                        an element on the canvas
 *   'field:f4.decision'            a field of the open inscription view
 *   'field:f2.code@purchaseAmount' the line of a code field that contains the text
 *   'field:f4.inputs@in.amount'    in any other field: exactly that text
 *   'vars' / 'vars@Url'            variables.yaml, or one of its lines
 *   'dmn:discount'                 the open editor of a .dmn file
 *   'dmn:discount@rule1'           a rule of its table; '@r1.o1' a cell, '@side' the readout
 *                                  next to the table, '@inputs' the inputs of the model
 *   'runtime:call'                 the Decision runtime panel: the last call; ':matched' the
 *                                  rule that decided, ':versions' the versions, ':v2' one of
 *                                  them, ':state' the container switch, ':rollback', ':owner'
 *   'owner' / 'owner@r1.o1'        the rule owner's dialog, or a cell of it
 *   'portal@check'                 a part of the Portal tab (see runtime/ui/portal.js), a rule
 *                                  of its table ('portal@rule2') or a cell ('portal@r2.i1')
 *   'log:last'                     the newest line of the Runtime Log
 *   'data:member'                  a field in the Process data view
 *   'side'                         the readout next to variables.yaml
 *   'arch:connector'               a block of the tab "Architecture": ivy, portal, demo, connector,
 *                                  files, container, api, editor, engine, memory, browser;
 *                                  or the label of a link: http, ui, callable. The camera
 *                                  of the diagram zooms to it and what is lit, on one side
 *                                  of the tab, and the note stands on the other side
 *   (none)                         the note docks in the bottom left corner of the canvas
 *
 * Hooks of d.run: 'enter:<id>' (the token has arrived, the element has not run yet),
 * 'after:<id>' (the element has run, the token is about to move on), 'pause:<id>' (a task
 * waits for its user; the run carries on when the hook returns) and 'rule:<phase>' (created,
 * request, registered, thinking, response, mapped, unmapped, failed, end).
 */
import { h, append, clear } from '../util.js';
import { icon } from './icons.js';
import { elementById, ruleConfig } from '../model/process.js';
import { elementBounds } from '../stage/layout.js';

const CANCELLED = Symbol('walkthrough cancelled');
const FAST = 2.5;
const SIDES = { portal: ['bottom', 'top', 'right', 'left'], arch: ['right', 'left', 'bottom', 'top'], el: ['bottom', 'top', 'left', 'right'], field: ['left', 'bottom', 'top'], vars: ['bottom', 'top'], src: ['bottom', 'top'], dmn: ['bottom', 'top', 'left'], owner: ['bottom', 'top', 'left'], runtime: ['top', 'bottom'], log: ['top', 'bottom'], data: ['top', 'bottom'], side: ['left', 'top', 'bottom'] };
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

/** Text with `code` spans. */
function rich(text) {
  return String(text).split('`').map((part, i) => (i % 2 ? h('code', null, part) : part));
}

/**
 * How long a note stays: a base plus time per character, with CJK characters counted double.
 * Kept brisk on purpose; anyone who wants to read longer clicks the simulation to pause.
 */
export function readingTime(text) {
  let units = 0;
  for (const ch of String(text)) units += /[　-鿿＀-￯]/.test(ch) ? 2.2 : 1;
  return clamp(600 + units * 26, 1800, 6000);
}

/** A quoted value cut to what fits a note, with an ellipsis when something is left out. */
function excerpt(text) {
  const full = String(text);
  let out = full.split('\n').slice(0, 8).join('\n');
  if (out.length > 380) out = out.slice(0, 380).trimEnd();
  return out.length < full.length ? out + ' …' : out;
}

const quoteText = quote => (Array.isArray(quote) ? quote.map(l => l.text).join('\n') : String(quote || ''));

/** The chapter title screen only needs a glance. */
const titleTime = text => clamp(1100 + String(text).length * 25, 1500, 2600);

/** Time per typed step: a few characters go slowly enough to follow, a long text takes about as long in total. */
const typingTime = steps => clamp(Math.round(1400 / steps), 40, 200);

/**
 * A part of a scope by its key: 'rule2', 'r2.o1', 'addRule'. In the editor of a .dmn file with several tables,
 * '<table>/<key>' looks inside one of them: 'discount/rule6', 'discount/r6.o1'.
 */
function findPart(scope, part, suffix = '') {
  if (!scope || !part) return null;
  const slash = part.indexOf('/');
  const root = slash > 0 ? scope.querySelector('[data-decision="' + part.slice(0, slash) + '"]') : scope;
  return root ? root.querySelector('[data-tour="' + part.slice(slash + 1) + '"]' + suffix) : null;
}

/** The key of a part without its table: 'r6.o1' for 'discount/r6.o1'. */
const partKey = part => String(part || '').slice(String(part || '').indexOf('/') + 1);

function parseTarget(target) {
  const m = /^(el|field|vars|src|dmn|owner|runtime|log|data|side|arch|portal)(?::([^@]*))?(?:@(.*))?$/.exec(target || '');
  return m ? { kind: m[1], name: m[2] || '', line: m[3] || '' } : null;
}

/** Index of the first line of a code editor that contains `text`, or -1. */
const lineIndex = (editor, text) => editor.textarea.value.split('\n').findIndex(l => l.includes(text));

export class Tour {
  /** @param {Object} app  the DesignerApp  @param {{chapters: {id:string, play:Function}[]}} [definition] */
  constructor(app, definition) {
    this.app = app;
    this.chapters = (definition && definition.chapters) || [];
    this.pace = Number(app.options.tourPace) > 0 ? Number(app.options.tourPace) : 1;
    this.state = 'idle'; // 'idle' | 'intro' | 'playing' | 'done'
    this.index = 0;
    this.paused = false;
    this.gen = 0;
    this.playing = Promise.resolve();
    this.abort = null; // ends the wait in progress
    this.advance = false; // "Next" was pressed
    this.notes = 0; // notes shown in the current chapter
    this.current = null; // the note on screen
    this.title = null; // id of the chapter whose title screen is showing
    this.target = null;
    this.cardKind = null;
    this.tracking = 0;

    this.block = h('div.tour-block', { onclick: () => this.togglePause() });
    this.spotEl = h('div.tour-spot', { hidden: true });
    this.noteEl = h('div.tour-note', { hidden: true, role: 'status', 'aria-live': 'polite' });
    this.titleEl = h('div.tour-title', { hidden: true, role: 'status', 'aria-live': 'polite' });
    this.cover = h('div.tour-cover', { hidden: true });
    app.layer.append(this.block, this.spotEl, this.noteEl, this.titleEl, this.cover);
    this.onKey = e => {
      if (e.key === 'ArrowRight') { e.preventDefault(); this.next(); }
      else if (e.key === 'ArrowLeft') { e.preventDefault(); this.back(); }
    };
    app.playerEl.addEventListener('keydown', this.onKey);
  }

  get available() { return this.chapters.length > 0; }

  // ── screens ────────────────────────────────────────────────────────────────

  #card(kind, children) {
    this.cardKind = kind;
    clear(this.cover).append(h('div.tour-card', { role: 'dialog', 'aria-label': this.app.t('tour.kicker') }, children));
    this.cover.className = 'tour-cover ' + kind;
    this.cover.hidden = false;
  }

  #closeCard() {
    this.cardKind = null;
    this.cover.hidden = true;
    clear(this.cover);
  }

  /** The first thing a learner sees: what the walkthrough covers, and one button. */
  showIntro() {
    const t = this.app.t;
    this.state = 'intro';
    this.app.setMode('intro');
    this.#card('intro', [
      h('div.tour-kicker', null, t('tour.kicker')),
      h('h3', null, t('tour.intro.title')),
      h('p', null, t('tour.intro.text')),
      h('ol.tour-card-list', null, this.chapters.map(c => h('li', null, t('tour.chapter.' + c.id)))),
      h('div.tour-card-actions', null,
        h('button.tour-play', { type: 'button', onclick: () => this.start(0) }, icon('play', 14), h('span', null, t('tour.play')), h('span.tour-play-time', null, t('tour.intro.duration'))),
        h('button.link-btn', { type: 'button', onclick: () => this.skip() }, t('tour.skip')))]);
  }

  #showEnd() {
    const t = this.app.t;
    this.#card('end', [
      h('div.tour-kicker', null, t('tour.kicker')),
      h('h3', null, t('tour.end.title')),
      h('p', null, t('tour.end.text')),
      h('div.tour-card-actions', null,
        h('button.tour-play', { type: 'button', onclick: () => this.#closeCard() }, h('span', null, t('tour.end.start'))),
        h('button.link-btn', { type: 'button', onclick: () => this.start(0) }, icon('reset', 13), t('tour.replay.all')))]);
  }

  // ── transport ──────────────────────────────────────────────────────────────

  /** Plays from the given chapter to the end. Whatever was playing is stopped first. */
  async start(index = 0) {
    await this.#halt();
    const gen = this.gen;
    this.state = 'playing';
    this.#closeCard();
    this.setPaused(false);
    this.app.setMode('tour');
    this.playing = this.#play(gen, clamp(index, 0, this.chapters.length - 1));
    const toggle = this.app.playerEl.querySelector('.tour-toggle');
    if (toggle) toggle.focus({ preventScroll: true });
  }

  async #play(gen, from) {
    const d = this.#director(gen);
    try {
      for (let i = from; i < this.chapters.length; i++) {
        this.index = i;
        this.notes = 0;
        this.#prepare();
        this.renderPlayer();
        await this.#titleScreen(gen, this.chapters[i]);
        await d.wait(120);
        await this.chapters[i].play(d);
        await d.wait(400);
      }
    } catch (e) {
      if (e === CANCELLED) return;
      console.error('[swInteractive] The walkthrough stopped unexpectedly.', e);
    }
    if (gen === this.gen) this.#finish(true);
  }

  /** The screen between chapters: the name of the tutorial section that comes next. */
  async #titleScreen(gen, chapter) {
    const t = this.app.t;
    this.title = chapter.id;
    this.#fillTitle();
    this.titleEl.hidden = false;
    this.titleEl.classList.remove('leave');
    try {
      await this.#sleep(gen, titleTime(t('tour.chapter.' + chapter.id)), { skippable: true });
      this.titleEl.classList.add('leave');
      await this.#sleep(gen, 220);
    } finally {
      this.title = null;
      this.titleEl.hidden = true;
    }
  }

  #fillTitle() {
    const t = this.app.t;
    const index = this.chapters.findIndex(c => c.id === this.title);
    if (index < 0) return;
    clear(this.titleEl).append(h('div.tour-title-box', null,
      h('h3', null, t('tour.chapter.' + this.title))));
  }

  /** Every chapter starts from the process as the tutorial ships it. */
  #prepare() {
    const { app } = this;
    this.#hide();
    app.reset();
    app.setTab('process');
    app.select(null);
    app.setInscriptionOpen(false);
    app.setPanelOpen(false);
  }

  /** The animation engines of all canvases. */
  #effects() {
    return Object.keys(this.app.stages).map(doc => this.app.stages[doc].effects);
  }

  async #halt() {
    this.gen++;
    if (this.abort) this.abort();
    this.#effects().forEach(fx => { fx.paused = false; }); // a paused animation would never finish, and neither would what waits for it
    this.app.stop();
    this.app.cancelEffects();
    this.#hide();
    this.titleEl.hidden = true;
    await this.playing;
  }

  #finish(withCard) {
    const { app } = this;
    this.state = 'done';
    this.setPaused(false);
    this.#hide();
    this.#closeCard();
    app.reset();
    app.setTab('process');
    app.setMode('explore');
    this.renderPlayer();
    if (withCard) this.#showEnd();
  }

  /** Leaves the walkthrough for the editable simulator. */
  async skip() {
    await this.#halt();
    this.#finish(false);
  }

  setPaused(paused) {
    this.paused = paused;
    this.app.root.classList.toggle('tour-paused', paused); // stops what is animated with CSS
    this.#effects().forEach(fx => { fx.paused = paused; });
    if (!paused) this.app.viewStage().invalidate();
    this.noteEl.classList.toggle('paused', paused);
    this.renderPlayer();
  }

  togglePause() {
    if (this.state === 'playing') this.setPaused(!this.paused);
  }

  /** Ends the current note right away. */
  next() {
    if (this.state !== 'playing') return;
    this.advance = true;
    if (this.paused) this.setPaused(false);
  }

  /** Replays the chapter; from its very beginning, goes to the previous one. */
  back() {
    if (this.state !== 'playing') return;
    this.start(this.notes <= 1 && this.index > 0 ? this.index - 1 : this.index);
  }

  renderPlayer() {
    const { app } = this;
    const t = app.t;
    const el = clear(app.playerEl);
    if (this.state !== 'playing') return;
    const total = this.chapters.length;
    const button = (name, label, handler, extra = '') => h('button.icon-btn' + extra, { type: 'button', title: label, 'aria-label': label, onclick: handler }, icon(name, 15));
    el.append(
      button(this.paused ? 'play' : 'pause', t(this.paused ? 'tour.resume' : 'tour.pause'), () => this.togglePause(), '.tour-toggle'),
      button('chevronLeft', t('tour.back'), () => this.back()),
      button('chevronRight', t('tour.next'), () => this.next()),
      h('div.tour-chapters', { role: 'list' }, this.chapters.map((c, i) => {
        const title = t('tour.chapter.' + c.id);
        return h('button.tour-chapter' + (i === this.index ? '.current' : i < this.index ? '.done' : ''), {
          type: 'button', role: 'listitem', title, 'aria-label': t('tour.chapter', { n: i + 1, total }) + ': ' + title,
          'aria-current': i === this.index ? 'step' : null, onclick: () => this.start(i)
        }, h('span.tour-chapter-n', null, String(i + 1)), h('span.tour-chapter-title', null, title));
      })),
      h('span.spacer'),
      h('button.tour-exit', { type: 'button', onclick: () => this.skip() }, h('span', null, t('tour.exit')), icon('close', 12)));
  }

  /** Rebuilds everything that carries text (language switch). */
  render() {
    this.renderPlayer();
    if (this.cardKind === 'intro') this.showIntro();
    else if (this.cardKind === 'end') this.#showEnd();
    if (this.title) this.#fillTitle();
    if (this.current) this.#fill(this.current);
  }

  // ── the director handed to a chapter ───────────────────────────────────────

  #director(gen) {
    const { app } = this;
    const alive = () => { if (gen !== this.gen) throw CANCELLED; };
    // the layout changed: wait for it, then bring the whole process back into view
    const settle = async () => {
      await this.#sleep(gen, 260);
      await this.#refit();
      alive();
      await this.#sleep(gen, 120);
    };
    return {
      app,
      wait: ms => this.#sleep(gen, ms),
      note: (key, options) => this.#note(gen, key, options),
      focus: target => { alive(); this.#show(null, parseTarget(target)); },
      clear: () => { alive(); this.#hide(); },
      run: options => this.#run(gen, options),
      stop: () => { alive(); app.stop(); },
      runtime: async state => {
        alive();
        app.setRuntimeState(state);
        await this.#sleep(gen, 400);
      },
      activate: async (model, version) => {
        alive();
        app.activateVersion(model, version);
        await this.#sleep(gen, 400);
      },
      rollback: async model => {
        alive();
        app.rollbackModel(model);
        await this.#sleep(gen, 400);
      },
      arch: focus => {
        alive();
        app.arch.setFocus(focus);
      },
      owner: async model => {
        alive();
        this.#hide();
        app.editAsRuleOwner(model);
        await this.#sleep(gen, 500);
      },
      ownerActivate: async () => {
        alive();
        this.#hide();
        if (app.ownerDialog) app.ownerDialog.activate();
        await this.#sleep(gen, 400);
      },
      portalInput: async (name, type = 'string') => {
        // "+ Input" of the Portal's editor: an input data node and a column, as the KIE editor makes them
        alive();
        const spot = { kind: 'portal', name: '', line: 'addInput' };
        this.#show(null, spot);
        await this.#sleep(gen, 700);
        this.#hide();
        if (app.portal) app.portal.addInput(name, type);
        await this.#sleep(gen, 400);
      },
      click: async target => {
        // presses a button of the DMN editor or of the Decision runtime panel, e.g. 'dmn:discount@addRule'
        alive();
        const parsed = parseTarget(target);
        const scope = parsed ? this.#scope(parsed) : null;
        const part = parsed ? (parsed.kind === 'runtime' ? parsed.name : parsed.line) : '';
        const node = findPart(scope, part);
        if (!node) return;
        this.#show(null, parsed);
        await this.#sleep(gen, 700);
        this.#hide();
        node.click();
        await this.#sleep(gen, 400);
      },
      reset: () => { alive(); this.#prepare(); },
      edit: (target, change) => this.#edit(gen, target, change),
      open: async (id, tab) => {
        alive();
        const el = elementById(app.model, id);
        app.setTab(el.doc);
        if (tab) app.state.inscriptionTabs[id] = tab;
        if (app.state.selection === id) app.state.selection = null; // selected again: render it anew
        app.select(id);
        app.setInscriptionOpen(true);
        if (tab) app.setInscriptionTab(el, tab);
        await settle();
      },
      close: async () => {
        alive();
        this.#hide();
        app.setInscriptionOpen(false);
        app.select(null);
        await settle();
      },
      panel: async tab => {
        alive();
        if (tab) app.setPanelTab(tab);
        app.setPanelOpen(Boolean(tab));
        await settle();
      },
      tab: async id => {
        alive();
        this.#hide();
        app.setTab(id);
        await this.#sleep(gen, 500);
      }
    };
  }

  /** Brings the whole process back into view after the canvas changed size. */
  #refit() {
    const stage = this.app.stages[this.app.state.tab];
    if (!stage) return Promise.resolve();
    stage.resize();
    return !stage.view.tilt && !stage.effects.remote ? stage.fit({ animate: true }) : Promise.resolve();
  }

  async #run(gen, { fast = false, on = {}, start = null } = {}) {
    const { app } = this;
    if (gen !== this.gen) throw CANCELLED;
    this.#hide();
    const hooks = {};
    Object.keys(on).forEach(name => {
      hooks[name] = async payload => {
        try { await on[name](payload); } catch (e) { if (e !== CANCELLED) throw e; }
      };
    });
    const stages = Object.keys(app.stages).map(doc => app.stages[doc]);
    app.runHooks = hooks;
    stages.forEach(stage => stage.setSpeed((fast ? FAST : 1) * this.pace));
    let result;
    try {
      result = await app.run({ start });
    } finally {
      app.runHooks = null;
      stages.forEach(stage => stage.setSpeed(1));
    }
    if (gen !== this.gen) throw CANCELLED;
    return result || { status: 'cancelled', ruleCalls: [] };
  }

  /**
   * Types a change into a field. The spotlight first rests on the characters that are about
   * to go (or on the place where text will be added), then they are removed and the new ones
   * written, one step at a time, with the spotlight following.
   */
  async #edit(gen, target, change) {
    const { app } = this;
    if (gen !== this.gen) throw CANCELLED;
    let get;
    let set;
    let control;
    let spot;
    if (target === 'vars') {
      get = () => app.variablesText;
      set = v => app.setVariables(v);
      control = () => app.variablesCode;
      spot = { kind: 'vars', name: '', line: '' };
    } else if (target.startsWith('src:')) {
      const id = target.slice(4);
      get = () => app.sourceText[id];
      set = v => app.setSource(id, v);
      control = () => app.sourceCode[id];
      spot = { kind: 'src', name: id, line: '' };
    } else if (target.startsWith('portal:')) {
      // in the Portal's editor: a cell of the decision table, 'portal:route.r1.i1', or 'portal:description'
      const key = target.slice(7);
      const field = key === 'description';
      control = () => (app.portal ? (field ? app.portal.description : app.portal.controls[key]) : null);
      get = () => (control() ? control().value : '');
      set = v => {
        const input = control();
        if (!input) return;
        input.setValue(v);
        input.dispatchEvent(new Event('input')); // typed the way a learner types
      };
      spot = { kind: 'portal', name: '', line: field ? 'description' : key.replace('.', '/') };
    } else if (target.startsWith('dmn:') || target.startsWith('owner:')) {
      // a cell of a decision table: 'dmn:<model>:<decision>.r1.o1', or the same in the rule owner's dialog
      const owner = target.startsWith('owner:');
      const key = target.slice(owner ? 6 : 4);
      const cell = key.slice(key.indexOf('.') + 1);
      control = () => (owner ? (app.ownerDialog ? app.ownerDialog.controls[key] : null) : app.dmnControls[key]);
      get = () => (control() ? control().value : '');
      set = v => {
        const input = control();
        if (!input) return;
        input.setValue(v);
        input.dispatchEvent(new Event('input')); // the table is edited the way a learner edits it
      };
      const table = key.slice(key.indexOf(':') + 1, key.indexOf('.'));
      spot = owner ? { kind: 'owner', name: '', line: cell } : { kind: 'dmn', name: key.slice(0, key.indexOf(':')), line: table + '/' + cell };
    } else {
      const [id, key] = target.split('.');
      const el = elementById(app.model, id);
      control = () => app.inscription.controls[key];
      spot = { kind: 'field', name: target, line: '' };
      if (key === 'code') {
        get = () => (el.config.output && el.config.output.code) || '';
        set = v => {
          app.changeElement(el, e => { e.config.output = { ...(e.config.output || {}), code: v }; }, 'code');
          if (control()) control().setValue(v);
        };
      } else if (key === 'tags') {
        // the tags of an element, as a comma-separated list
        get = () => (el.tags || []).join(',');
        set = v => {
          app.changeElement(el, e => { e.tags = v ? String(v).split(',') : []; }, 'tags');
          app.inscription.render();
        };
      } else if (el.isRule) {
        get = () => ruleConfig(el)[key] || '';
        set = v => {
          app.setRuleConfig(el, key, v);
          if (control()) control().setValue(v);
        };
      } else {
        // a setting of any other element, e.g. the signature of a sub-process start
        get = () => el.config[key] || '';
        set = v => {
          app.changeElement(el, e => { e.config[key] = v; }, key);
          if (control()) control().setValue(v);
        };
      }
    }
    const from = get();
    const to = typeof change === 'function' ? change(from) : change;
    if (to === from) return;
    if (!control() || typeof control().textRect !== 'function') {
      // a choice, not text: there is nothing to type
      set(to);
      await this.#sleep(gen, 500);
      return;
    }
    let p = 0;
    while (p < from.length && p < to.length && from[p] === to[p]) p++;
    let s = 0;
    while (s < from.length - p && s < to.length - p && from[from.length - 1 - s] === to[to.length - 1 - s]) s++;
    const head = from.slice(0, p);
    const tail = from.slice(from.length - s);
    const removed = from.length - p - s;
    const added = to.length - p - s;
    // the changing characters are [p, p + n) of the field's value
    const mark = n => { if (this.target) this.target.range = [p, p + n]; };
    this.#show(null, { ...spot, range: [p, p + removed] });
    await this.#sleep(gen, removed ? 900 : 500);
    const out = Math.min(removed, 16);
    for (let k = 1; k <= out; k++) {
      const left = removed - Math.round(removed * k / out);
      set(head + from.slice(p, p + left) + tail);
      mark(left);
      await this.#sleep(gen, typingTime(out));
    }
    if (out && added) await this.#sleep(gen, 250);
    const steps = Math.min(added, 28);
    for (let k = 1; k <= steps; k++) {
      const n = Math.round(added * k / steps);
      set(head + to.slice(p, p + n) + tail);
      mark(n);
      await this.#sleep(gen, typingTime(steps));
    }
    set(to);
    mark(added);
    await this.#sleep(gen, 600);
  }

  // ── notes ──────────────────────────────────────────────────────────────────

  async #note(gen, key, { target = null, params = null, quote = '', wait = null, extra = 0 } = {}) {
    if (gen !== this.gen) throw CANCELLED;
    this.notes++;
    const note = { key, params, quote };
    this.#show(note, parseTarget(target));
    const t = this.app.t;
    const manual = wait == null && this.app.reducedMotion;
    this.noteEl.classList.toggle('manual', manual);
    const ms = wait != null ? wait : readingTime(t('tour.' + key + '.title') + ' ' + t('tour.' + key + '.text', params || {})) + Math.min(1200, quoteText(quote).length * 5) + extra;
    await this.#sleep(gen, ms, { skippable: true, progress: true, manual });
    this.#hide();
  }

  #fill(note) {
    const t = this.app.t;
    this.bar = h('i');
    let quote = null;
    if (Array.isArray(note.quote)) {
      quote = h('div.tour-note-quote', null, note.quote.map(line => h('div' + (line.wrong ? '.wrong' : ''), null,
        line.text || '\u00a0', line.wrong ? h('span.tour-note-flag', null, ' ' + t('tour.quote.wrong')) : null)));
    } else if (note.quote) quote = h('div.tour-note-quote', null, excerpt(note.quote));
    append(clear(this.noteEl), [
      h('div.tour-note-title', null, t('tour.' + note.key + '.title', note.params || {})),
      h('div.tour-note-text', null, rich(t('tour.' + note.key + '.text', note.params || {}))),
      quote,
      h('button.tour-note-next', { type: 'button', onclick: () => this.next() }, t('tour.next'), icon('chevronRight', 12)),
      h('div.tour-note-bar', null, this.bar)]);
  }

  /** @param {Object|null} target  a parsed target; with `range`, characters [start, end) of the field's value */
  #show(note, target) {
    this.current = note;
    this.target = target;
    this.#reveal();
    if (note) {
      this.#fill(note);
      this.noteEl.hidden = false;
      this.noteEl.classList.remove('enter');
      void this.noteEl.offsetWidth;
      this.noteEl.classList.add('enter');
    } else this.noteEl.hidden = true;
    this.#place();
    if (!this.tracking) {
      const loop = () => { this.#place(); this.tracking = requestAnimationFrame(loop); };
      this.tracking = requestAnimationFrame(loop);
    }
  }

  #hide() {
    cancelAnimationFrame(this.tracking);
    this.tracking = 0;
    this.current = null;
    this.target = null;
    this.noteEl.hidden = true;
    this.spotEl.hidden = true;
  }

  #editor(target) {
    const { app } = this;
    if (target.kind === 'vars') return app.variablesCode;
    if (target.kind === 'src') return app.sourceCode[target.name] || null;
    const node = app.inscriptionEl.querySelector('[data-tour="' + target.name.split('.').pop() + '"]');
    return node ? node.querySelector('.code') : null;
  }

  /**
   * The characters of a field's value that the target means, as [start, end), or null when
   * it means the whole field or a line: a range given by #edit, or the text after "@" in a
   * field that is not a code editor.
   */
  #range(target) {
    if (target.range) return target.range;
    if (target.kind !== 'field' || !target.line) return null;
    const [id, key] = target.name.split('.');
    const el = elementById(this.app.model, id);
    if (key === 'code' || !el || !el.isRule) return null;
    const at = String(ruleConfig(el)[key] || '').indexOf(target.line);
    return at < 0 ? null : [at, at + target.line.length];
  }

  /** Where those characters are on screen, or null. */
  #textRect(target) {
    const range = this.#range(target);
    if (!range) return null;
    const control = target.kind === 'vars' ? this.app.variablesCode
      : target.kind === 'src' ? this.app.sourceCode[target.name]
        : target.kind === 'portal' ? (target.line === 'description' ? this.app.portal.description : this.#cell(target))
          : target.kind === 'dmn' || target.kind === 'owner' ? this.#cell(target)
          : this.app.inscription.controls[target.name.split('.').pop()];
    const r = control && typeof control.textRect === 'function' ? control.textRect(range[0], range[1]) : null;
    return r && r.height > 0 ? { left: r.left, top: r.top, width: r.width, height: r.height, exact: true } : null;
  }

  /** The part of the screen a 'dmn', 'owner' or 'runtime' target lives in. */
  #scope(target) {
    const { app } = this;
    if (target.kind === 'dmn') return app.state.tab === 'dmn:' + target.name ? app.dmnEditors[target.name] || null : null;
    if (target.kind === 'owner') return app.ownerDialog ? app.ownerDialog.box : null;
    if (target.kind === 'portal') return app.portal && app.state.tab === 'portal' ? app.portal.root : null;
    return app.state.panelOpen && app.state.panelTab === 'runtime' ? app.panel.runtimeView : null;
  }

  /** The input of the table cell a 'dmn' or 'owner' target names with r<rule>.<i|o><column>, or null. */
  #cell(target) {
    const scope = this.#scope(target);
    const node = scope && /^r\d+\.(?:[io]\d+|n)$/.test(partKey(target.line)) ? findPart(scope, target.line, ' input') : null;
    return node || null;
  }

  /** Scrolls the target into view inside its own scroll container (never the page). */
  #reveal() {
    const { app } = this;
    const target = this.target;
    if (!target) return;
    if (target.kind === 'log') {
      app.panel.logView.scrollTop = app.panel.logView.scrollHeight;
      return;
    }
    if (target.kind === 'data') {
      const row = app.panel.dataView.querySelector('[data-field="' + target.name + '"]');
      if (row) app.panel.dataView.scrollTop = Math.max(0, row.offsetTop - 2);
      return;
    }
    if (target.kind === 'arch') return; // the camera of the diagram brings it into view, see #place
    if (target.kind === 'dmn' || target.kind === 'owner' || target.kind === 'runtime' || target.kind === 'portal') {
      const scope = this.#scope(target);
      const part = target.kind === 'runtime' ? target.name : target.line;
      const node = findPart(scope, part);
      if (!node) return;
      if (target.kind === 'runtime') {
        // the panel is low: bring the part to its middle, so that the lines around it are there too
        const view = app.panel.runtimeView;
        const n = node.getBoundingClientRect();
        const v = view.getBoundingClientRect();
        view.scrollTop += n.top - v.top - Math.max(4, (v.height - n.height) / 2);
      } else if (typeof node.scrollIntoView === 'function') node.scrollIntoView({ block: 'nearest', inline: 'nearest' });
      return;
    }
    if (target.kind === 'field') {
      const node = app.inscriptionEl.querySelector('[data-tour="' + target.name.split('.').pop() + '"]');
      const body = app.inscriptionEl.querySelector('.inscription-body');
      if (node && body) {
        const n = node.getBoundingClientRect();
        const b = body.getBoundingClientRect();
        if (n.top < b.top + 8 || n.bottom > b.bottom - 8) body.scrollTop += n.top - b.top - 12;
      }
    }
    if ((target.line || target.range) && (target.kind === 'field' || target.kind === 'vars' || target.kind === 'src')) {
      const editor = this.#editor(target);
      const lineOf = at => editor.textarea.value.slice(0, at).split('\n').length - 1;
      const i = !editor ? -1 : target.range ? lineOf(target.range[0]) : lineIndex(editor, target.line);
      if (i >= 0) {
        const lh = parseFloat(getComputedStyle(editor).lineHeight) || 18;
        editor.scrollTop = Math.max(0, 6 + i * lh - editor.clientHeight / 2 + lh / 2);
      }
    }
  }

  /** The target's box on screen, or null when there is nothing to point at. */
  #rect(target) {
    const { app } = this;
    const box = r => (r && r.width > 0 && r.height > 0 ? { left: r.left, top: r.top, width: r.width, height: r.height } : null);
    const line = editor => {
      const i = lineIndex(editor, target.line);
      const outer = editor.getBoundingClientRect();
      if (i < 0) return box(outer);
      const lh = parseFloat(getComputedStyle(editor).lineHeight) || 18;
      const body = editor.querySelector('.code-body').getBoundingClientRect();
      const left = editor.querySelector('.code-gutter').getBoundingClientRect().right;
      const top = clamp(body.top + 6 + i * lh, outer.top, outer.bottom - lh);
      return box({ left, top, width: outer.right - left - 2, height: lh });
    };
    switch (target.kind) {
      case 'el': {
        const el = elementById(app.model, target.name);
        const stage = el ? app.stages[el.doc] : null; // the canvas of the element's file, when it is the open tab
        if (!el || app.state.tab !== el.doc || !stage.width) return null;
        const b = elementBounds(el);
        const a = stage.worldToClient(b.x, b.y);
        const c = stage.worldToClient(b.x + b.w, b.y + b.h);
        return box({ left: Math.min(a.x, c.x), top: Math.min(a.y, c.y), width: Math.abs(c.x - a.x), height: Math.abs(c.y - a.y) });
      }
      case 'field': {
        const node = app.inscriptionEl.querySelector('[data-tour="' + target.name.split('.').pop() + '"]');
        if (!node) return null;
        const editor = target.line ? node.querySelector('.code') : null;
        return this.#textRect(target) || (editor ? line(editor) : box(node.getBoundingClientRect()));
      }
      case 'src': {
        const code = app.sourceCode[target.name];
        if (!code || app.state.tab !== 'src:' + target.name) return null;
        return this.#textRect(target) || (target.line ? line(code) : box(code.getBoundingClientRect()));
      }
      case 'vars':
        if (!app.variablesCode) return null;
        return this.#textRect(target) || (target.line ? line(app.variablesCode) : box(app.variablesCode.getBoundingClientRect()));
      case 'log': {
        const lines = app.panel.logView.querySelectorAll('.log-line');
        return lines.length ? box(lines[lines.length - 1].getBoundingClientRect()) : box(app.panel.logView.getBoundingClientRect());
      }
      case 'data': {
        const row = app.panel.dataView.querySelector('[data-field="' + target.name + '"]');
        const view = app.panel.dataView.getBoundingClientRect();
        if (!row) return box(view);
        // only the part of the row that the panel shows
        const r = row.getBoundingClientRect();
        const top = Math.max(r.top, view.top);
        return box({ left: r.left, top, width: r.width, height: Math.min(r.bottom, view.bottom) - top });
      }
      case 'dmn':
      case 'owner':
      case 'portal':
      case 'runtime': {
        // a named part of the DMN editor, the rule owner's dialog or the Decision runtime panel
        const scope = this.#scope(target);
        if (!scope) return null;
        const part = target.kind === 'runtime' ? target.name : target.line;
        const node = part ? findPart(scope, part) : scope;
        if (!node) return box(scope.getBoundingClientRect());
        const rect = this.#textRect(target) || box(node.getBoundingClientRect());
        // a rule or a cell of a decision table: where the table ends, and whether it is a whole rule
        const table = rect && typeof node.closest === 'function' ? node.closest('.dmn-scroll') : null;
        if (table) {
          rect.tableBottom = table.getBoundingClientRect().bottom;
          rect.row = node.tagName === 'TR';
        }
        return rect;
      }
      case 'side':
        return app.variablesInfo ? box(app.variablesInfo.getBoundingClientRect()) : null;
      case 'arch': {
        const node = app.state.tab === 'arch' ? app.arch.node(target.name) : null;
        const rect = node ? box(node.getBoundingClientRect()) : null;
        if (rect) rect.under = app.arch.bottom(); // where the diagram ends
        return rect;
      }
      default:
        return null;
    }
  }

  /** Puts the spotlight on the target and the note beside it. */
  #place() {
    const { app } = this;
    const layer = app.layer.getBoundingClientRect();
    if (!layer.width) return;
    if (this.target && this.target.kind === 'arch' && app.state.tab === 'arch' && !this.noteEl.hidden) {
      // the diagram moves the block into view and leaves room for the note; the note follows it frame by frame
      app.arch.frame(this.target.name, { width: this.noteEl.offsetWidth, height: this.noteEl.offsetHeight });
    }
    const r = this.target ? this.#rect(this.target) : null;
    const spot = this.spotEl;
    if (r) {
      const exact = Boolean(r.exact); // around characters, not around a field
      const pad = exact ? 3 : this.target.line ? 2 : 5;
      const side = exact ? 1 : pad; // the frame must not cover the neighbouring characters
      spot.classList.toggle('text', exact);
      spot.classList.toggle('plain', this.target.kind === 'arch');
      spot.hidden = false;
      spot.style.transform = 'translate(' + Math.round(r.left - layer.left - side) + 'px,' + Math.round(r.top - layer.top - pad) + 'px)';
      spot.style.width = Math.round(r.width + side * 2) + 'px';
      spot.style.height = Math.round(r.height + pad * 2) + 'px';
    } else spot.hidden = true;

    const note = this.noteEl;
    if (note.hidden) return;
    const w = note.offsetWidth;
    const ht = note.offsetHeight;
    const floor = layer.height - app.playerEl.offsetHeight - 8;
    let x;
    let y;
    let side = 'dock';
    let arrow = 0;
    if (r) {
      const gap = 16;
      const left = r.left - layer.left;
      const top = r.top - layer.top;
      const fits = {
        bottom: top + r.height + gap + ht <= floor,
        top: top - gap - ht >= 8,
        left: left - gap - w >= 8,
        right: left + r.width + gap + w <= layer.width - 8
      };
      // beside characters the note goes below or above: to their left is the rest of the field
      const order = r.exact ? ['bottom', 'top', 'left', 'right'] : SIDES[this.target.kind];
      side = order.find(s => fits[s]) || order[0];
      const cx = left + r.width / 2;
      const cy = top + r.height / 2;
      if (side === 'bottom' || side === 'top') {
        x = clamp(cx - w / 2, 8, Math.max(8, layer.width - w - 8));
        y = side === 'bottom' ? top + r.height + gap : top - gap - ht;
        arrow = clamp(cx - x, 18, w - 18);
        if (side === 'bottom' && r.tableBottom) {
          // keep the rules below readable: under the table when it ends close by, else under the
          // end of the rule, where only its description is
          const under = r.tableBottom - layer.top + gap;
          if (under - y <= 110 && under + ht <= floor) y = under;
          else if (r.row && r.width > w * 1.5) {
            x = clamp(left + r.width - w - 8, 8, Math.max(8, layer.width - w - 8));
            arrow = w / 2;
          }
        }
      } else {
        x = side === 'left' ? left - gap - w : left + r.width + gap;
        y = clamp(cy - ht / 2, 8, Math.max(8, floor - ht));
        arrow = clamp(cy - y, 18, ht - 18);
      }
      const framed = this.target.kind === 'arch' && app.state.tab === 'arch' ? app.arch.framed() : null;
      if (framed) {
        // beside all the camera frames, on the side it keeps free, level with the block the note is about
        side = framed.side;
        x = side === 'right' ? framed.rect.right - layer.left + gap : framed.rect.left - layer.left - gap - w;
        x = clamp(x, 8, Math.max(8, layer.width - w - 8));
        y = clamp(cy - ht / 2, 8, Math.max(8, floor - ht));
        arrow = clamp(cy - y, 18, ht - 18);
      } else if (r.under && r.under - layer.top + 12 + ht <= floor) {
        // a block of the architecture: below the diagram, in line with the block, so that no block is covered
        x = clamp(cx - w / 2, 8, Math.max(8, layer.width - w - 8));
        y = r.under - layer.top + 12;
        side = 'dock';
      }
      y = clamp(y, 8, Math.max(8, floor - ht));
    } else {
      const host = app.canvasHost.getBoundingClientRect();
      const area = host.width > 0 ? host : layer;
      // bottom left: the middle of the canvas is where the rule task and the runtime are
      x = clamp(area.left - layer.left + 16, 8, Math.max(8, layer.width - w - 8));
      y = clamp(area.bottom - layer.top - ht - 16, 8, Math.max(8, floor - ht));
    }
    note.dataset.side = side;
    note.style.setProperty('--arrow', Math.round(arrow) + 'px');
    note.style.transform = 'translate(' + Math.round(x) + 'px,' + Math.round(y) + 'px)';
  }

  // ── time ───────────────────────────────────────────────────────────────────

  /**
   * Waits `ms` of playing time: paused time does not count. `skippable` waits end when
   * "Next" is pressed; `manual` ones only then (reduced motion: nothing moves by itself).
   */
  #sleep(gen, ms, { skippable = false, progress = false, manual = false } = {}) {
    if (gen !== this.gen) return Promise.reject(CANCELLED);
    if (skippable) this.advance = false;
    return new Promise((resolve, reject) => {
      let elapsed = 0;
      let last = performance.now();
      let raf = 0;
      this.abort = () => {
        cancelAnimationFrame(raf);
        this.abort = null;
        reject(CANCELLED);
      };
      const tick = now => {
        const dt = Math.min(80, now - last);
        last = now;
        if (!this.paused) elapsed += dt * this.pace;
        if (progress && this.bar) this.bar.style.width = (manual ? 0 : Math.min(1, elapsed / ms) * 100) + '%';
        if ((skippable && this.advance) || (!manual && elapsed >= ms)) {
          if (skippable) this.advance = false;
          this.abort = null;
          resolve();
          return;
        }
        raf = requestAnimationFrame(tick);
      };
      raf = requestAnimationFrame(tick);
    });
  }

  dispose() {
    this.gen++;
    if (this.abort) this.abort();
    this.playing.catch(() => {});
    cancelAnimationFrame(this.tracking);
    this.app.playerEl.removeEventListener('keydown', this.onKey);
  }
}
