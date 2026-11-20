/**
 * The tab "Architecture": the parts of the Rules Engine as blocks, and what connects them.
 *
 *   Axon Ivy Engine                         Docker container
 *     Axon Ivy Portal    (Rule Management)
 *     ┆ calls Portal/RuleManagement
 *     rule-engine-demo   (your project)       REST API        DMN editor page  ← Browser
 *     rule-engine        (connector)  ⇄ HTTP  DMN engine      Database         → Volume
 *     rule-engine-core   (shared code) ·····  rule-engine-core (compiled in)
 *
 * The diagram is live: it shows the state of the container, what the database holds, the files
 * of the project and the last call. A walkthrough lights blocks and lets a link flow
 * (setFocus); a learner selects a block to read what is in it, in a drawer on the right.
 *
 * Blocks and link labels carry data-arch="<id>", which is what the walkthrough points at
 * ('arch:demo', 'arch:http'):
 *   blocks  ivy, portal, demo, connector, core, container, api, editor, engine, db, shared, browser, volume
 *   links   http (connector ⇄ api), code (core ··· shared), ui (browser → editor), store (db → volume),
 *           callable (portal → connector, along the side of the projects)
 */
import { h, clear, brief } from '../util.js';
import { icon } from './icons.js';
import { readSettings } from '../model/runner.js';
import { parseYaml } from '../model/yaml.js';

const SVG = 'http://www.w3.org/2000/svg';
const IMAGE = 'axonivy/rule-engine-runtime';
/** From this width of the tab on, the details stand beside the diagram (and leave it 840 px). */
const DETAILS_BESIDE = 1180;
/** How far the camera of a walkthrough zooms in and out. */
const ZOOM = { max: 1.2, min: 0.5 };
/** Room around what the camera frames, and between it and the note. */
const FRAME_PAD = 20;

/** A result in a few words: a structure by its values, e.g. 2 · Silver customer. */
const compact = value => (value !== null && typeof value === 'object' && !Array.isArray(value)
  ? Object.keys(value).map(key => (value[key] !== null && typeof value[key] === 'object' ? '…' : String(value[key]))).join(' · ')
  : brief(value));

/**
 * from → to; `both`: a second line carries the answer back; `plain`: no direction; `side`: runs along the left
 * edge of the blocks in between, its label stands in the row below `from`.
 */
const LINKS = [
  { id: 'callable', from: 'portal', to: 'connector', side: true },
  { id: 'http', from: 'connector', to: 'api', both: true },
  { id: 'code', from: 'core', to: 'shared', plain: true },
  { id: 'ui', from: 'browser', to: 'editor' },
  { id: 'store', from: 'db', to: 'volume' }
];

const svg = (name, attrs) => {
  const node = document.createElementNS(SVG, name);
  Object.keys(attrs || {}).forEach(key => node.setAttribute(key, attrs[key]));
  return node;
};

/**
 * The way of a link between two boxes: straight when they face each other, else with two
 * corners. `offset` moves it sideways, for the second line of a link that goes both ways.
 */
function route(a, b, offset = 0) {
  if (a.right <= b.left || b.right <= a.left) {
    const forward = a.right <= b.left;
    const x1 = forward ? a.right : a.left;
    const x2 = forward ? b.left : b.right;
    const y1 = a.top + a.height / 2 + offset;
    const y2 = b.top + b.height / 2 + offset;
    const middle = (x1 + x2) / 2 + (Math.abs(y1 - y2) < 1 ? 0 : offset * (y2 < y1 ? 1 : -1) * (forward ? 1 : -1));
    return { points: [[x1, y1], [middle, y1], [middle, y2], [x2, y2]], label: [(x1 + x2) / 2, (y1 + y2) / 2 - offset] };
  }
  const down = a.bottom <= b.top;
  const y1 = down ? a.bottom : a.top;
  const y2 = down ? b.top : b.bottom;
  const x1 = a.left + a.width / 2 + offset;
  const x2 = b.left + b.width / 2 + offset;
  const middle = (y1 + y2) / 2 + (Math.abs(x1 - x2) < 1 ? 0 : offset * (x2 < x1 ? 1 : -1) * (down ? 1 : -1));
  return { points: [[x1, y1], [x1, middle], [x2, middle], [x2, y2]], label: [(x1 + x2) / 2 - offset, (y1 + y2) / 2] };
}

const pathOf = points => points.map((p, i) => (i ? 'L' : 'M') + Math.round(p[0]) + ' ' + Math.round(p[1])).join(' ');

/** A small arrow head at the end of the points, in the direction of their last segment. */
function headOf(points) {
  const [x, y] = points[points.length - 1];
  let i = points.length - 2;
  while (i > 0 && Math.abs(points[i][0] - x) < 1 && Math.abs(points[i][1] - y) < 1) i--;
  const dx = Math.sign(Math.round(x - points[i][0]));
  const dy = Math.sign(Math.round(y - points[i][1]));
  const s = 7;
  const w = 4;
  return dx
    ? 'M' + x + ' ' + y + ' l' + -dx * s + ' ' + -w + ' v' + 2 * w + ' z'
    : 'M' + x + ' ' + y + ' l' + -w + ' ' + -dy * s + ' h' + 2 * w + ' z';
}

export class ArchitectureView {
  /** @param {Object} app  the DesignerApp  @param {HTMLElement} host  the editor of the tab */
  constructor(app, host) {
    this.app = app;
    this.host = host;
    this.nodes = {}; // blocks and link labels by id
    this.live = {}; // the line of a block that shows the current state
    this.paths = {}; // link id → { out, back, label }
    this.focus = null; // what a walkthrough lights: { lit, flow, label }
    this.camera = null; // what a note of a walkthrough is about: { ids, note }, see frame()
    this.frameKey = '';
    this.selection = null;
    this.call = null; // the last call of a Business Rule Task
    this.observer = typeof ResizeObserver === 'function' ? new ResizeObserver(() => this.layout()) : null;
    // the details close with their button, with Escape, or with a click anywhere but on a block
    this.onClick = event => {
      if (!this.selection) return;
      const target = event.target;
      if (target && target.closest && target.closest('.arch-block, .arch-frame-head, .arch-details')) return;
      this.show(null);
    };
    this.onKey = event => {
      if (event.key !== 'Escape' || !this.selection) return;
      event.preventDefault(); // the walkthrough dialog around the simulator stays open
      event.stopPropagation();
      this.close();
    };
    host.addEventListener('click', this.onClick);
    host.addEventListener('keydown', this.onKey);
  }

  // ── building ────────────────────────────────────────────────────────────────

  render() {
    const t = this.app.t;
    this.nodes = {};
    this.live = {};
    const block = (id, iconName, extra = '') => {
      const live = h('span.arch-live');
      this.live[id] = live;
      const node = h('button.arch-block' + extra, { type: 'button', 'data-arch': id, 'aria-pressed': 'false', onclick: () => this.select(id) },
        h('span.arch-block-head', null, icon(iconName, 14), h('span.arch-name', null, t('arch.' + id + '.title')), h('span.arch-role', null, t('arch.' + id + '.kind'))),
        h('span.arch-text', null, t('arch.' + id + '.text')),
        live);
      this.nodes[id] = node;
      return node;
    };
    const requires = () => h('div.arch-requires', { 'aria-hidden': 'true' }, h('span', null, t('arch.requires')));
    const calls = () => h('div.arch-calls', null, inline(label('callable', t('arch.link.callable'))));
    const frame = (id, title, sub, kind, children) => {
      const node = h('section.arch-frame.arch-' + id, { 'data-arch': id },
        h('button.arch-frame-head', { type: 'button', onclick: () => this.select(id) }, title, h('span.arch-frame-sub', null, sub)),
        h('div.arch-kind', null, kind),
        children);
      this.nodes[id] = node;
      return node;
    };
    this.stateEl = h('span.arch-state');
    this.linksEl = svg('svg', { class: 'arch-links', 'aria-hidden': 'true' });
    this.labels = {};
    const inline = node => {
      node.classList.add('inline');
      return node;
    };
    const label = (id, text) => {
      const sub = h('span.arch-link-sub');
      const node = h('div.arch-link-label', { 'data-arch': id }, h('span.arch-link-text', null, text), sub);
      this.labels[id] = { node, text: node.firstChild, sub, fixed: text };
      this.nodes[id] = node;
      return node;
    };

    this.diagram = h('div.arch-diagram', null,
      this.linksEl,
      frame('ivy',
        h('span.arch-frame-title', null, icon('process', 15), t('arch.ivy.title')),
        t('arch.ivy.sub'), t('arch.ivy.kind'),
        h('div.arch-stack', null,
          block('portal', 'pointer', '.arch-optional'), calls(),
          block('demo', 'process'), requires(), block('connector', 'extension'), requires(), block('core', 'braces'))),
      h('div.arch-outside.arch-top', null, block('browser', 'pointer', '.arch-small')),
      frame('container',
        h('span.arch-frame-title', null, icon('database', 15), t('arch.container.title'), h('code', null, t('arch.container.name')), this.stateEl),
        t('arch.container.sub', { image: IMAGE }), t('arch.container.kind'),
        h('div.arch-cells', null,
          block('api', 'arrowRight'), block('editor', 'list'),
          block('engine', 'settings'), block('db', 'database'),
          block('shared', 'braces', '.arch-dashed'))),
      h('div.arch-outside.arch-bottom', null, block('volume', 'database', '.arch-small')),
      label('http', t('arch.link.http')), label('code', t('arch.link.code')), label('ui', t('arch.link.ui')), label('store', t('arch.link.store')));
    // the label of 'callable' was created above, in its row of the stack

    // the details of the selected block: a drawer on the right, beside the diagram
    this.detailsEl = h('aside.arch-details', { hidden: true, 'aria-label': t('arch.details.label') });
    // the stage clips the diagram while the camera moves it, so that it never slides over the title
    this.scrollEl = h('div.arch-scroll', null,
      h('div.arch-title', null, h('strong', null, t('arch.title')), h('span', null, t('arch.hint'))),
      h('div.arch-stage', null, this.diagram));
    this.mainEl = h('div.arch-main', null, this.scrollEl, this.detailsEl);
    clear(this.host).append(this.mainEl);
    if (this.observer) {
      this.observer.disconnect();
      this.observer.observe(this.diagram);
    }
    this.update();
    this.applyFocus();
    this.renderDetails();
    this.frameKey = '';
    this.layout();
  }

  /** The element of a block or of a link label, for the walkthrough. */
  node(id) {
    return this.nodes[id] || null;
  }

  /** Where the diagram ends on screen: notes of the walkthrough go below it, unless the camera frames a part of it. */
  bottom() {
    return this.diagram && !this.camera ? this.diagram.getBoundingClientRect().bottom : 0;
  }

  /**
   * Where a block or a link label lies in the diagram, as laid out: not moved by the camera.
   * @returns {{left:number, top:number, right:number, bottom:number, width:number, height:number}|null}
   */
  boxOf(id) {
    const node = this.nodes[id];
    if (!node || node.hidden || !node.offsetWidth) return null;
    let left = 0;
    let top = 0;
    let el = node;
    while (el && el !== this.diagram) {
      left += el.offsetLeft;
      top += el.offsetTop;
      el = el.offsetParent;
    }
    if (el !== this.diagram) return null;
    const width = node.offsetWidth;
    const height = node.offsetHeight;
    // a link label stands centred on its point
    if (node.classList.contains('arch-link-label') && !node.classList.contains('inline')) {
      left -= width / 2;
      top -= height / 2;
    }
    return { left, top, right: left + width, bottom: top + height, width, height };
  }

  // ── the camera of a walkthrough ─────────────────────────────────────────────

  /**
   * While a note of a walkthrough is about a part of the diagram, the camera scales and moves the diagram so
   * that the part, with what is lit beside it, stands in the middle of one side of the tab, and the note has
   * the other side. A small tab, such as the tutorial inside the Portal, zooms out; a large one zooms in a bit.
   * Called for every frame the note is shown: it only moves when something changed.
   * @param {string|null} id  the block or link the note is about; null lets go
   * @param {{width:number, height:number}} [note]  the size of the note
   */
  frame(id, note = null) {
    if (!this.diagram) return;
    const ids = id && this.nodes[id] ? [...new Set([id, ...this.focusIds()])] : [];
    const view = this.scrollEl;
    const key = ids.length ? [ids.join(), note ? note.width + 'x' + note.height : '', view.clientWidth + 'x' + view.clientHeight].join('|') : '';
    if (key === this.frameKey) return;
    this.frameKey = key;
    this.camera = ids.length ? { ids, note } : null;
    this.applyCamera();
  }

  /**
   * What the camera frames, where it is on screen now (it follows the camera while it moves), and the side it
   * keeps free for the note.
   * @returns {{side: 'left'|'right', rect: {left:number, top:number, right:number, bottom:number}}|null}
   */
  framed() {
    const camera = this.camera;
    if (!camera || !camera.side) return null;
    const rects = camera.ids.map(id => this.nodes[id]).filter(node => node && !node.hidden).map(node => node.getBoundingClientRect());
    if (!rects.length) return null;
    return {
      side: camera.side,
      rect: {
        left: Math.min(...rects.map(r => r.left)), top: Math.min(...rects.map(r => r.top)),
        right: Math.max(...rects.map(r => r.right)), bottom: Math.max(...rects.map(r => r.bottom))
      }
    };
  }

  /** What the walkthrough lights: blocks, and both ends of a link that flows. */
  focusIds() {
    const focus = this.focus || {};
    const ids = [...(focus.lit || [])];
    (focus.flow || []).forEach(entry => {
      const link = LINKS.find(candidate => candidate.id === entry.replace(/[<>]$/, ''));
      if (link) ids.push(link.from, link.to);
    });
    return ids.filter(id => this.nodes[id]);
  }

  applyCamera() {
    if (!this.diagram) return;
    const camera = this.camera;
    const view = this.scrollEl;
    view.classList.toggle('framed', Boolean(camera));
    const boxes = camera ? camera.ids.map(id => this.boxOf(id)).filter(Boolean) : [];
    if (!boxes.length || !view.clientWidth) {
      this.diagram.style.transform = '';
      return;
    }
    view.scrollTop = 0;
    view.scrollLeft = 0;
    const box = {
      left: Math.min(...boxes.map(b => b.left)), top: Math.min(...boxes.map(b => b.top)),
      right: Math.max(...boxes.map(b => b.right)), bottom: Math.max(...boxes.map(b => b.bottom))
    };
    const width = box.right - box.left;
    const height = box.bottom - box.top;
    // the diagram as laid out, in the coordinates of the scroll area; the title above it stays
    const originX = this.diagram.offsetLeft - view.offsetLeft;
    const originY = this.diagram.offsetTop - view.offsetTop;
    const noteWidth = camera.note ? camera.note.width + FRAME_PAD : 0;
    const areaWidth = Math.max(120, view.clientWidth - noteWidth - FRAME_PAD * 2);
    const areaHeight = Math.max(120, view.clientHeight - originY - FRAME_PAD * 2);
    const scale = Math.max(ZOOM.min, Math.min(ZOOM.max, areaWidth / width, areaHeight / height));
    // a part on the right of the diagram stays on the right, and the note goes to its left
    const onRight = box.left + width / 2 > this.diagram.offsetWidth / 2;
    camera.side = onRight ? 'left' : 'right';
    const areaLeft = onRight ? view.clientWidth - FRAME_PAD - areaWidth : FRAME_PAD;
    // the middle of the part goes to the middle of its area; the area starts below the title
    const x = areaLeft + areaWidth / 2 - originX - scale * (box.left + width / 2);
    const y = FRAME_PAD + areaHeight / 2 - scale * (box.top + height / 2);
    this.diagram.style.transform = 'translate(' + Math.round(x) + 'px, ' + Math.round(y) + 'px) scale(' + scale.toFixed(3) + ')';
  }

  // ── links ───────────────────────────────────────────────────────────────────

  /** Draws the links between the blocks as they are laid out now. */
  layout() {
    if (!this.diagram || this.host.hidden) return;
    if (!this.host.clientWidth) return;
    // the details take room beside the diagram, or lie over it where there is too little room
    this.mainEl.classList.toggle('details-over', this.host.clientWidth < DETAILS_BESIDE);
    const width = this.scrollEl.clientWidth;
    // wide: browser and volume stand beside the page and the database they belong to;
    // otherwise above and below the container; narrow: one column
    const mode = width >= 1330 ? 'wide' : width >= 840 ? 'medium' : 'narrow';
    this.diagram.classList.toggle('wide', mode === 'wide');
    this.diagram.classList.toggle('narrow', mode === 'narrow');
    [['browser', 'editor'], ['volume', 'db']].forEach(([outside, partner]) => {
      const holder = this.nodes[outside].parentNode;
      holder.style.marginTop = '';
      if (mode !== 'wide') return;
      const p = this.boxOf(partner);
      if (p) holder.style.marginTop = Math.round(p.top + (p.height - this.nodes[outside].offsetHeight) / 2) + 'px';
    });
    // as laid out, whatever the camera does with the diagram
    const base = { width: this.diagram.offsetWidth, height: this.diagram.offsetHeight };
    const rectOf = id => this.boxOf(id) || { left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0 };
    this.linksEl.setAttribute('viewBox', '0 0 ' + Math.round(base.width) + ' ' + Math.round(base.height));
    while (this.linksEl.firstChild) this.linksEl.removeChild(this.linksEl.firstChild);
    this.paths = {};
    LINKS.forEach(link => {
      this.labels[link.id].node.hidden = mode === 'narrow' && link.id === 'code';
      if (this.labels[link.id].node.hidden) return;
      // Browser and volume above and below the container: their links end at its frame, so that
      // they cross none of its texts. In one column the frames are connected, at their left half,
      // where the browser is not in the way.
      const edge = (frame, like, side) => {
        const y = side === 'top' ? frame.top : frame.bottom;
        return { left: like.left, right: like.right, width: like.width, top: y, bottom: y, height: 0 };
      };
      const half = r => ({ ...r, right: r.left + r.width / 2, width: r.width / 2 });
      let a = rectOf(link.from);
      let b = rectOf(link.to);
      if (mode !== 'wide' && link.id === 'ui') b = edge(rectOf('container'), a, 'top');
      if (mode !== 'wide' && link.id === 'store') a = edge(rectOf('container'), b, 'bottom');
      if (mode === 'narrow' && link.id === 'http') {
        a = half(rectOf('ivy'));
        b = half(rectOf('container'));
      }
      const group = svg('g', { class: 'arch-link', 'data-link': link.id });
      const line = (points, cls, withHead) => {
        const g = svg('g', { class: 'arch-line ' + cls });
        g.append(svg('path', { d: pathOf(points), class: 'arch-wire' }));
        if (withHead) g.append(svg('path', { d: headOf(points), class: 'arch-head' }));
        group.append(g);
        return g;
      };
      if (link.side) {
        // down the left edge of the blocks in between, inside the frame
        const x = Math.min(a.left, b.left) - 7;
        const y1 = a.top + a.height / 2;
        const y2 = b.top + b.height / 2;
        this.linksEl.append(group);
        this.paths[link.id] = { group, out: line([[a.left, y1], [x, y1], [x, y2], [b.left, y2]], 'out', true) };
        return;
      }
      const there = route(a, b, link.both ? -6 : 0);
      const entry = { group, out: line(there.points, 'out', !link.plain) };
      if (link.both) entry.back = line(route(b, a, -6).points, 'back', true);
      this.linksEl.append(group);
      this.paths[link.id] = entry;
      const center = link.both ? route(a, b, 0).label : there.label;
      const node = this.labels[link.id].node;
      node.style.left = Math.round(center[0]) + 'px';
      node.style.top = Math.round(center[1]) + 'px';
    });
    this.applyFocus();
    this.applyState();
    this.applyCamera();
  }

  // ── what a walkthrough shows ────────────────────────────────────────────────

  /**
   * Lights blocks and lets links flow; everything else steps back.
   * @param {{ lit?: string[], flow?: string[], label?: Object }|null} focus
   *   flow: 'http>' (to the runtime), 'http<' (the answer), 'ui>', 'store>', 'code'
   *   label: text for the label of a link while the focus lasts, e.g. { http: 'POST …/evaluate' }
   */
  setFocus(focus) {
    this.focus = focus && (focus.lit || focus.flow) ? focus : null;
    this.applyFocus();
    // the end of a chapter, a reset: the whole diagram again
    if (!this.focus) this.frame(null);
  }

  applyFocus() {
    if (!this.diagram) return;
    const focus = this.focus;
    const lit = new Set((focus && focus.lit) || []);
    const flows = {};
    ((focus && focus.flow) || []).forEach(entry => {
      const id = entry.replace(/[<>]$/, '');
      flows[id] = flows[id] || {};
      flows[id][entry.endsWith('<') ? 'back' : 'out'] = true;
      lit.add(id);
    });
    this.diagram.classList.toggle('focus', Boolean(focus));
    Object.keys(this.nodes).forEach(id => {
      const node = this.nodes[id];
      const inside = node.classList.contains('arch-frame') && [...lit].some(other => other !== id && this.nodes[other] && node.contains(this.nodes[other]));
      node.classList.toggle('lit', lit.has(id));
      node.classList.toggle('lit-inside', inside);
    });
    Object.keys(this.paths).forEach(id => {
      const entry = this.paths[id];
      const flow = flows[id] || {};
      entry.group.classList.toggle('lit', lit.has(id));
      entry.out.classList.toggle('flow', Boolean(flow.out));
      if (entry.back) entry.back.classList.toggle('flow', Boolean(flow.back));
    });
    Object.keys(this.labels).forEach(id => {
      const text = focus && focus.label && focus.label[id];
      this.labels[id].text.textContent = text || this.labels[id].fixed;
      this.labels[id].node.classList.toggle('quoted', Boolean(text));
    });
  }

  // ── the current state ───────────────────────────────────────────────────────

  /** The last call of a Business Rule Task, or null after a reset. */
  setCall(call) {
    this.call = call;
    this.update();
  }

  update() {
    if (!this.diagram) return;
    const { app } = this;
    const t = app.t;
    const runtime = app.runtime;
    const yaml = parseYaml(app.variablesText);
    const settings = readSettings(yaml.errors.length ? {} : yaml.value || {});
    this.reachable = Boolean(settings.url) && settings.url.replace(/\/+$/, '') === runtime.url && runtime.state !== 'stopped';

    this.live.demo.textContent = app.dmnFiles.map(file => file.current.file + (file.generated ? ' ' + t('arch.live.generated') : app.dmnChanged(file) ? ' ' + t('arch.live.changed') : '')).join(', ');
    this.live.connector.textContent = 'RuleEngine.Url = ' + (settings.url || '?');
    const fromPortal = runtime.modelNames()
      .flatMap(name => runtime.versions(name).filter(version => version.source === 'PORTAL').map(version => ({ name, version })))
      .pop();
    this.live.portal.textContent = fromPortal ? t('arch.live.portal', { model: fromPortal.name.slice(settings.prefix.length), version: fromPortal.version.version }) : '';
    this.live.connector.classList.toggle('bad', !settings.url || settings.url.replace(/\/+$/, '') !== runtime.url);
    const names = runtime.modelNames();
    this.live.db.textContent = names.length
      ? names.map(name => {
        const active = runtime.active(name);
        const count = runtime.versions(name).length;
        return active
          ? t('arch.live.db.model', { model: name, version: active.version, source: active.source.toLowerCase(), count })
          : t('arch.live.db.inactive', { model: name, count });
      }).join(' · ')
      : t('arch.live.db.empty');
    const call = this.call;
    let last = '';
    let failed = false;
    if (call && call.status === 'failed') {
      last = t('arch.live.call.failed', { code: call.errorCode });
      failed = true;
    } else if (call && call.results !== undefined) {
      const names = Object.keys(call.results);
      // one or two results by their value; a whole model by the number of its results, so that the block stays small
      const result = names.length <= 2 ? names.map(name => name + ' = ' + compact(call.results[name])).join(', ') : t('arch.live.results', { count: names.length });
      last = t('arch.live.call.ok', { result, version: call.version });
    }
    this.live.api.textContent = last;
    this.live.api.classList.toggle('bad', failed);
    this.live.engine.textContent = call && call.rule ? t('arch.live.rule', { n: call.rule.index + 1, decision: call.decision || call.model })
      : call && (call.rules || []).length ? t('arch.live.model', { model: call.model, count: call.rules.length }) : '';
    this.labels.http.sub.textContent = this.reachable ? runtime.url : t('arch.live.unreachable');
    this.stateEl.textContent = t('runtime.state.' + runtime.state);
    this.stateEl.className = 'arch-state ' + runtime.state;
    this.applyState();
  }

  applyState() {
    if (!this.nodes.container) return;
    this.nodes.container.classList.toggle('stopped', this.app.runtime.state === 'stopped');
    const http = this.paths.http;
    if (http) http.group.classList.toggle('broken', !this.reachable);
    this.labels.http.node.classList.toggle('broken', !this.reachable);
  }

  // ── details of a block ──────────────────────────────────────────────────────

  /** A click on a block: shows its details, or closes them when they are shown already. */
  select(id) {
    if (this.app.mode === 'tour') return; // the walkthrough has the room of the tab to itself
    this.show(this.selection === id ? null : id);
  }

  /** Closes the details; the focus goes back to the block they were about. */
  close() {
    const block = this.selection && this.nodes[this.selection];
    const focused = this.detailsEl && this.detailsEl.contains(this.host.getRootNode().activeElement);
    this.show(null);
    if (focused && block) (block.querySelector('.arch-frame-head') || block).focus();
  }

  /** Shows the details of a block or a frame in the drawer on the right; null closes it. */
  show(id) {
    const opened = Boolean(this.selection) !== Boolean(id);
    this.selection = id && this.nodes[id] ? id : null;
    this.renderDetails();
    if (!opened) return;
    this.layout(); // the drawer took room from the diagram, or gave it back
    if (this.selection) this.nodes[this.selection].scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }

  renderDetails() {
    if (!this.detailsEl) return;
    const t = this.app.t;
    const id = this.selection;
    Object.keys(this.nodes).forEach(other => {
      const node = this.nodes[other];
      node.classList.toggle('selected', other === id);
      if (node.hasAttribute('aria-pressed')) node.setAttribute('aria-pressed', String(other === id));
    });
    this.mainEl.classList.toggle('with-details', Boolean(id));
    this.detailsEl.hidden = !id;
    clear(this.detailsEl);
    if (!id) return;
    const frame = id === 'ivy' || id === 'container';
    const code = text => String(text).split('`').map((part, i) => (i % 2 ? h('code', null, part) : part));
    const closeButton = h('button.icon-btn.arch-details-close', {
      type: 'button', title: t('arch.details.close'), 'aria-label': t('arch.details.close'), onclick: () => this.close()
    }, icon('close', 14));
    this.detailsEl.append(
      h('div.arch-details-head', null,
        h('h4', null, t('arch.' + id + '.title')),
        frame ? null : h('span.arch-role', null, t('arch.' + id + '.kind')),
        closeButton),
      h('p.arch-details-text', null, frame ? t('arch.' + id + '.sub', { image: IMAGE }) : t('arch.' + id + '.text')),
      h('div.arch-kind', null, t('arch.details.label')),
      h('ul', null, t('arch.' + id + '.details').split('|').filter(Boolean).map(item => h('li', null, code(item)))));
  }

  dispose() {
    if (this.observer) this.observer.disconnect();
    this.host.removeEventListener('click', this.onClick);
    this.host.removeEventListener('keydown', this.onKey);
  }
}
