import { pathLength, pointAt } from './layout.js';

const easeInOut = t => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);
const easeOut = t => 1 - Math.pow(1 - t, 3);
const linear = t => t;
const lerp = (a, b, t) => a + (b - a) * t;
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

const NODE_HEIGHT = 230;
const NODE_OFFSET = 112;
const VIEW_KEYS = ['x', 'y', 'zoom', 'tilt', 'lift'];

function bezier(a, c, b, t) {
  const u = 1 - t;
  return {
    x: u * u * a.x + 2 * u * t * c.x + t * t * b.x,
    y: u * u * a.y + 2 * u * t * c.y + t * t * b.y,
    z: u * u * a.z + 2 * u * t * c.z + t * t * b.z
  };
}

/** Rough text width for sizing chips without a canvas (CJK glyphs count double). */
function textUnits(text) {
  let n = 0;
  for (const ch of String(text)) n += ch.charCodeAt(0) > 0x2e80 ? 1.9 : 1;
  return n;
}

export class Effects {
  constructor(host) {
    this.host = host;
    this.items = [];
    this.tweens = new Set();
    this.speed = 1;
    this.reducedMotion = false;
    this.paused = false;
    this.remote = null;
  }

  tween(duration, update, ease = easeInOut, group = 'fx') {
    const ms = this.reducedMotion ? 0 : duration / this.speed;
    if (ms <= 0) {
      update(1);
      this.host.invalidate();
      return Promise.resolve();
    }
    return new Promise(resolve => {
      this.tweens.add({ ms, elapsed: 0, update, ease, resolve, group });
      this.host.invalidate();
    });
  }

  wait(duration) { return this.tween(duration, () => {}, linear); }

  step(dt) {
    if (this.paused) return false;
    this.tweens.forEach(tw => {
      tw.elapsed += dt;
      const t = clamp(tw.elapsed / tw.ms, 0, 1);
      tw.update(tw.ease(t));
      if (t >= 1) {
        this.tweens.delete(tw);
        tw.resolve();
      }
    });
    return this.tweens.size > 0;
  }

  cancelAll() {
    const pending = [...this.tweens];
    this.tweens.clear();
    this.items.length = 0;
    this.remote = null;
    pending.forEach(tw => tw.resolve());
    this.host.invalidate();
  }

  #add(item) { this.items.push(item); return item; }
  #remove(item) {
    const i = this.items.indexOf(item);
    if (i >= 0) this.items.splice(i, 1);
  }

  moveView(target, duration = 500) {
    this.#stopViewTweens();
    const from = this.host.getView();
    const keys = VIEW_KEYS.filter(k => target[k] !== undefined && target[k] !== from[k]);
    if (!keys.length) return Promise.resolve();
    return this.tween(duration, t => {
      const next = {};
      keys.forEach(k => { next[k] = lerp(from[k], target[k], t); });
      this.host.setView(next);
    }, easeInOut, 'view');
  }

  #stopViewTweens() {
    [...this.tweens].filter(tw => tw.group === 'view').forEach(tw => {
      this.tweens.delete(tw);
      tw.resolve();
    });
  }

  /** The user panned or zoomed: stop steering the camera, and do not snap back afterwards. */
  userMovedView() {
    this.#stopViewTweens();
    if (this.remote) this.remote.userMoved = true;
  }

  async travel(points, { duration = 460 } = {}) {
    const length = pathLength(points);
    const at = t => pointAt(points, t);
    const token = this.#add({ kind: 'token', x: points[0].x, y: points[0].y, z: 0, r: 13, o: 1 });
    const trail = [1, 2, 3, 4].map(i => this.#add({ kind: 'token', x: points[0].x, y: points[0].y, z: 0, r: 13 - i * 2, o: 0.5 - i * 0.1, lag: i * 0.045 }));
    await this.tween(Math.max(260, duration * clamp(length / 90, 0.6, 1.8)), t => {
      Object.assign(token, at(t));
      trail.forEach(tr => Object.assign(tr, at(t - tr.lag)));
      const fade = t > 0.9 ? (1 - t) / 0.1 : 1;
      token.o = fade;
      trail.forEach((tr, i) => { tr.o = (0.5 - (i + 1) * 0.1) * fade; });
    });
    this.#remove(token);
    trail.forEach(tr => this.#remove(tr));
  }

  pulse(el, tone = 'assistant') {
    const round = el.shape === 'event';
    const ring = this.#add({ kind: 'ring', x: el.x, y: el.y, z: 0, w: el.w, h: el.h, radius: round ? el.w / 2 : 5, o: 0, tone });
    return this.tween(520, t => {
      const grow = 4 + t * 16;
      ring.w = el.w + grow;
      ring.h = el.h + grow;
      ring.radius = round ? ring.w / 2 : 5 + grow / 2;
      ring.o = (1 - t) * 0.9;
    }, easeOut).then(() => this.#remove(ring));
  }

  async remoteOpen(el, { title, subtitle }) {
    const tilt = this.host.supportsTilt && !this.reducedMotion;
    const node = tilt ? { x: el.x, y: el.y, z: NODE_HEIGHT } : { x: el.x, y: el.y - NODE_OFFSET, z: 0 };
    const anchor = { x: el.x, y: el.y - el.h / 2 - 2, z: 0 };
    const card = this.#add({ kind: 'card', ...node, s: 0.6, o: 0, rev: 0, data: { w: 156, h: 46, tone: 'model', icon: true, title, subtitle } });
    const link = this.#add({ kind: 'link', a: anchor, b: { x: node.x, y: node.y + (tilt ? 0 : 23), z: Math.max(0, node.z - 16) }, o: 0, tone: 'model' });
    this.remote = { el, tilt, node, anchor, card, link, previous: this.host.getView(), userMoved: false };

    const size = this.host.size();
    const view = this.host.getView();
    let camera;
    if (tilt) {
      const zoom = clamp(Math.min(view.zoom * 1.12, (size.h - 60) / 290, size.w / 400), 0.5, 1.6);
      camera = { x: el.x, y: el.y - 6, zoom, tilt: 0.62, lift: 100 };
    } else {
      const zoom = clamp(Math.min(view.zoom, (size.h - 30) / 200, size.w / 400), 0.5, 1.6);
      camera = { x: el.x, y: el.y - 46, zoom };
    }
    await Promise.all([
      this.moveView(camera, 640),
      this.tween(460, t => { card.o = t; card.s = lerp(0.6, 1, t); link.o = t * 0.55; }, easeOut)
    ]);
  }

  #flight(from, to, side, packet, duration) {
    const control = {
      x: (from.x + to.x) / 2 + side,
      y: (from.y + to.y) / 2 - (this.remote && this.remote.tilt ? 0 : 6),
      z: (from.z + to.z) / 2 + (this.remote && this.remote.tilt ? 26 : 0)
    };
    const width = clamp(textUnits(packet.text) * 5.6 + (packet.tag ? textUnits(packet.tag) * 5.4 + 34 : 24), 96, 214);
    const item = this.#add({ kind: 'card', ...from, s: 0.7, o: 0, rev: 0, data: { w: width, h: 26, tone: packet.tone, tag: packet.tag, title: packet.text } });
    return this.tween(duration, t => {
      Object.assign(item, bezier(from, control, to, t));
      item.s = lerp(0.72, 1, Math.sin(Math.PI * t));
      item.o = clamp(Math.min(t / 0.14, (1 - t) / 0.16), 0, 1);
    }).then(() => this.#remove(item));
  }

  async remoteSend(packets) {
    const a = this.remote;
    if (!a) return;
    const n = packets.length;
    await Promise.all(packets.map((p, i) => this.wait(i * 320).then(() => {
      if (this.remote !== a) return undefined;
      return this.#flight(a.anchor, { x: a.node.x, y: a.node.y + (a.tilt ? 0 : 16), z: a.node.z }, (i - (n - 1) / 2) * 170, p, 1100);
    })));
  }

  async remoteThink(ms, { failing = false } = {}) {
    const a = this.remote;
    if (!a) return;
    const ring = this.#add({ kind: 'ring', x: a.node.x, y: a.node.y, z: a.node.z, w: 156, h: 46, radius: 7, o: 0, tone: 'model' });
    const beats = Math.max(1, Math.round(ms / 620));
    await this.tween(ms, t => {
      const phase = (t * beats) % 1;
      a.card.s = 1 + 0.045 * Math.sin(phase * Math.PI);
      ring.w = 156 + phase * 30;
      ring.h = 46 + phase * 30;
      ring.radius = 7 + phase * 15;
      ring.o = (1 - phase) * 0.7;
    }, linear);
    this.#remove(ring);
    a.card.s = 1;
    if (failing) {
      a.card.data = { ...a.card.data, tone: 'error' };
      a.card.rev++;
      a.link.tone = 'error';
      const x0 = a.card.x;
      await this.tween(340, t => { a.card.x = x0 + Math.sin(t * Math.PI * 5) * 5 * (1 - t); }, linear);
      a.card.x = x0;
    }
  }

  async remoteReceive(packet) {
    const a = this.remote;
    if (!a) return;
    await this.#flight({ x: a.node.x, y: a.node.y + (a.tilt ? 0 : 16), z: a.node.z }, a.anchor, 0, packet, 1000);
    if (this.remote === a) this.pulse(a.el, packet.tone);
  }

  chip(el, text, tone = 'assistant') {
    const width = clamp(textUnits(text) * 6 + 22, 64, 250);
    const y0 = el.y - el.h / 2 - 20;
    const item = this.#add({ kind: 'card', x: el.x, y: y0, z: 0, s: 1, o: 0, rev: 0, data: { w: width, h: 22, tone, title: text } });
    let shown;
    const visible = new Promise(resolve => { shown = resolve; });
    this.tween(1900, t => {
      item.y = y0 - easeOut(Math.min(1, t * 2)) * 10;
      item.o = clamp(Math.min(t / 0.12, (1 - t) / 0.25), 0, 1);
      if (t >= 0.42) shown();
    }, linear).then(() => { shown(); this.#remove(item); });
    return visible;
  }

  async remoteClose() {
    const a = this.remote;
    if (!a) return;
    const back = a.userMoved ? { tilt: 0, lift: 0 } : { ...a.previous, tilt: 0, lift: 0 };
    await Promise.all([
      this.tween(360, t => { a.card.o = 1 - t; a.card.s = lerp(1, 0.85, t); a.link.o = 0.55 * (1 - t); }),
      this.moveView(back, 620)
    ]);
    this.#remove(a.card);
    this.#remove(a.link);
    if (this.remote === a) this.remote = null;
  }
}
