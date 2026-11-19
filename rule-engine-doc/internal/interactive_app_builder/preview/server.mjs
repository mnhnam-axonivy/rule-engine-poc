/**
 * Preview of the tutorial without an Axon Ivy engine.
 *
 * It serves the real page of rule-engine-doc — Tutorial.xhtml with its scripts, styles and
 * Markdown — after resolving the few JSF bits the engine would normally render (the Markdown
 * in the dialog data and the CMS labels). Use it to develop a walkthrough and to check how it sits in
 * the tutorial:
 *
 *   npm run preview            →  http://127.0.0.1:4310/
 *   …/?renderer=canvas         force the 2D fallback renderer
 */
import http from 'node:http';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseYaml } from '../runtime/model/yaml.js';

const here = dirname(fileURLToPath(import.meta.url));
const project = resolve(here, '../../..'); // rule-engine-doc
const resources = join(project, 'webContent/resources');
const cms = join(project, 'cms');
const DIALOG = 'dialog/com/axonivy/utils/rule/engine/doc/Tutorial/Tutorial.xhtml';

const TYPES = { '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.png': 'image/png', '.html': 'text/html; charset=utf-8' };

const escapeHtml = text => String(text).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const read = file => readFileSync(file, 'utf8');

/** What the dialog data holds as pages[id]: the Markdown at /Tutorial/guide-<id> of the CMS. */
function markdown(id) {
  const file = join(cms, 'Tutorial', 'guide-' + id + '.md');
  return existsSync(file) ? read(file) : '';
}

let labels = null;
function cmsLabel(path) {
  if (!labels) labels = parseYaml(read(join(cms, 'cms_en.yaml'))).value;
  let node = labels;
  for (const key of path.split('/').filter(Boolean)) node = node && typeof node === 'object' ? node[key] : undefined;
  return typeof node === 'string' ? node : path.split('/').pop();
}

function tutorialPage(query) {
  const xhtml = read(join(project, DIALOG));
  const open = '<ui:define name="content">';
  let body = xhtml.slice(xhtml.indexOf(open) + open.length, xhtml.lastIndexOf('</ui:define>'));
  const title = /<ui:define name="title">([\s\S]*?)<\/ui:define>/.exec(xhtml);

  const resolveCms = text => text.replace(/#\{ivy\.cms\.co\('([^']+)'\)\}/g, (m, key) => escapeHtml(cmsLabel(key)));
  body = resolveCms(body)
    .replace(/<h:outputStylesheet\s+library="css"\s+name="([^"]+)"\s*\/>/g, '<link rel="stylesheet" href="/resources/css/$1" />')
    .replace(/<h:outputScript\s+library="js"\s+name="([^"]+)"\s*\/>/g, '<script src="/resources/js/$1"></script>')
    .replace(/#\{data\.pages\['(\w+)'\]\}/g, (m, id) => escapeHtml(markdown(id)));

  const leftover = body.match(/#\{[^}]*\}|<\/?(?:h|p|ui|f):[\w-]+/);
  if (leftover) console.warn('[preview] not resolved: ' + leftover[0]);

  const options = query.get('renderer') ? '<script>window.reInteractiveOptions = { renderer: ' + JSON.stringify(query.get('renderer')) + ' };</script>' : '';
  return '<!DOCTYPE html>\n<html lang="en"><head><meta charset="utf-8" />' +
    '<meta name="viewport" content="width=device-width, initial-scale=1.0" /><title>' + (title ? resolveCms(title[1]) : 'Tutorial') + ' (preview)</title>' +
    // rough stand-in for the theme the engine adds around the dialog
    '<style>:root{--ivy-primary-color:#007095}body{margin:0;background:#f8f9fa;font:14px "Segoe UI",system-ui,sans-serif}' +
    '#content{padding:24px 32px}button{font:inherit}</style>' + options + '</head>' +
    '<body class="body-hd"><div id="content" class="container frame full-width">' + body + '</div></body></html>';
}

function send(res, status, type, content) {
  res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  res.end(content);
}

function handle(req, res) {
  const url = new URL(req.url, 'http://localhost');
  try {
    if (url.pathname === '/') return send(res, 200, TYPES['.html'], tutorialPage(url.searchParams));
    if (url.pathname === '/favicon.ico') return send(res, 204, 'image/x-icon', '');
    let file = null;
    if (url.pathname.startsWith('/resources/')) file = join(resources, normalize(decodeURIComponent(url.pathname.slice('/resources/'.length))));
    if (file && file.startsWith(resources) && existsSync(file)) {
      return send(res, 200, TYPES[extname(file)] || 'application/octet-stream', readFileSync(file));
    }
    return send(res, 404, 'text/plain', 'Not found: ' + url.pathname);
  } catch (e) {
    console.error(e);
    return send(res, 500, 'text/plain', String(e && e.stack));
  }
}

/** Starts the preview server; resolves with { url, close }. Port 0 picks a free port. */
export function start(port = 4310) {
  return new Promise((resolveStart, reject) => {
    const server = http.createServer(handle);
    server.on('error', reject);
    server.listen(port, '127.0.0.1', () => {
      resolveStart({ url: 'http://127.0.0.1:' + server.address().port + '/', close: () => new Promise(done => server.close(done)) });
    });
  });
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const { url } = await start(Number(process.env.PORT) || 4310);
  console.log('Tutorial preview: ' + url);
}
