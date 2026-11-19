/**
 * Entry point of the shared runtime (built to js/interactive-runtime.min.js).
 *
 * It exposes `window.reInteractive`:
 *   register(id, definition)          called by each js/feature-XX.js
 *   has(id)                           is an app available for this page?
 *   about(id)                         its title, intro text, duration and chapter titles
 *   mount(container, id, { lang, autoplay, chapter })   renders the app, playing from a chapter with
 *                                     autoplay; returns { host, app, setLanguage, dispose }
 *
 * Each app renders into a shadow root, so the hosting page and the simulation cannot
 * disturb each other's styles.
 */
import css from './ui/styles.css';
import { DesignerApp } from './app.js';

/* global __RE_VERSION__ */
const VERSION = typeof __RE_VERSION__ === 'string' ? __RE_VERSION__ : 'dev';

if (!window.reInteractive) {
  const registry = new Map();
  let sheet = null;

  const applyStyles = shadow => {
    if (typeof CSSStyleSheet === 'function' && 'adoptedStyleSheets' in shadow) {
      try {
        if (!sheet) { sheet = new CSSStyleSheet(); sheet.replaceSync(css); }
        shadow.adoptedStyleSheets = [sheet];
        return;
      } catch (e) { /* older browsers: fall back to a <style> element */ }
    }
    const style = document.createElement('style');
    style.textContent = css;
    shadow.appendChild(style);
  };

  const mount = (container, id, options = {}) => {
    const definition = registry.get(id);
    if (!definition) throw new Error('reInteractive: no app is registered as "' + id + '"');
    const host = document.createElement('div');
    host.className = 're-interactive-host';
    host.setAttribute('data-app', id);
    const shadow = host.attachShadow({ mode: 'open' });
    applyStyles(shadow);
    const root = document.createElement('div');
    shadow.appendChild(root);
    container.appendChild(host);

    const app = new DesignerApp(root, definition, { ...(window.reInteractiveOptions || {}), ...options });
    const handle = {
      id,
      host,
      app,
      setLanguage: lang => app.setLanguage(lang),
      dispose() {
        app.dispose();
        host.remove();
        mounted.delete(handle);
      }
    };
    mounted.add(handle);
    return handle;
  };

  const mounted = new Set();
  const api = {
    version: VERSION,
    register(id, definition) { registry.set(id, definition); },
    has: id => registry.has(id),
    ids: () => [...registry.keys()],
    /**
     * What a walkthrough is about, from its own texts, for a list of walkthroughs:
     * { id, title, text, duration, chapters: [titles] }, or null if there is no such walkthrough.
     */
    about(id, lang = 'en') {
      const definition = registry.get(id);
      if (!definition) return null;
      const all = definition.strings || {};
      const strings = all[lang] || all.en || {};
      const chapters = ((definition.tour && definition.tour.chapters) || []).map(chapter => strings['tour.chapter.' + chapter.id] || chapter.id);
      return { id, title: strings['tour.intro.title'] || id, text: strings['tour.intro.text'] || '', duration: strings['tour.intro.duration'] || '', chapters };
    },
    /** The simulations currently on the page (for debugging and tests). */
    instances: () => [...mounted],
    mount
  };

  // Feature scripts may load before or after this file; both orders work.
  const queued = window.__reInteractiveQueue;
  if (Array.isArray(queued)) queued.forEach(([id, definition]) => api.register(id, definition));
  window.__reInteractiveQueue = { push: ([id, definition]) => api.register(id, definition) };
  window.reInteractive = api;
}
