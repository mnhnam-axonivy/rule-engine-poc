/**
 * A small XML reader without a DOM dependency, so the model code that uses it runs in the browser and in the
 * unit tests alike.
 */

export class XmlError extends Error {
  constructor(message, pos) {
    super(message);
    this.name = 'XmlError';
    this.pos = pos;
  }
}

const ENTITIES = { lt: '<', gt: '>', amp: '&', quot: '"', apos: "'" };

export function decodeEntities(text) {
  return String(text).replace(/&(#x[0-9a-fA-F]+|#\d+|\w+);/g, (all, name) => {
    if (name[0] === '#') {
      const code = name[1] === 'x' ? parseInt(name.slice(2), 16) : parseInt(name.slice(1), 10);
      return Number.isNaN(code) ? all : String.fromCodePoint(code);
    }
    return ENTITIES[name] === undefined ? all : ENTITIES[name];
  });
}

export function encodeEntities(text) {
  return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

const localName = name => (name.includes(':') ? name.slice(name.indexOf(':') + 1) : name);

export function parseXml(source) {
  const src = String(source == null ? '' : source);
  let i = 0;
  const stack = [];
  let root = null;

  const fail = message => { throw new XmlError(message, i); };
  const skip = (open, close, what) => {
    const end = src.indexOf(close, i + open.length);
    if (end < 0) fail(what + ' is not closed');
    i = end + close.length;
  };

  while (i < src.length) {
    const lt = src.indexOf('<', i);
    const text = src.slice(i, lt < 0 ? src.length : lt);
    if (stack.length && text) stack[stack.length - 1].text += decodeEntities(text);
    else if (text.trim()) fail('Text outside of the root element');
    if (lt < 0) break;
    i = lt;

    if (src.startsWith('<?', i)) { skip('<?', '?>', 'The processing instruction'); continue; }
    if (src.startsWith('<!--', i)) { skip('<!--', '-->', 'The comment'); continue; }
    if (src.startsWith('<![CDATA[', i)) {
      const end = src.indexOf(']]>', i);
      if (end < 0) fail('The CDATA section is not closed');
      if (stack.length) stack[stack.length - 1].text += src.slice(i + 9, end);
      i = end + 3;
      continue;
    }
    if (src.startsWith('<!', i)) { skip('<!', '>', 'The declaration'); continue; }

    if (src[i + 1] === '/') {
      const end = src.indexOf('>', i);
      if (end < 0) fail('The closing tag is not closed');
      const name = src.slice(i + 2, end).trim();
      const open = stack.pop();
      if (!open || open.name !== name) fail('</' + name + '> does not close <' + (open ? open.name : '?') + '>');
      i = end + 1;
      continue;
    }

    const tag = /^<([^\s/>]+)/.exec(src.slice(i, i + 200));
    if (!tag) fail('Unexpected "<"');
    const node = { name: tag[1], local: localName(tag[1]), attrs: {}, children: [], text: '' };
    i += tag[0].length;
    for (;;) {
      while (i < src.length && /\s/.test(src[i])) i++;
      if (i >= src.length) fail('<' + node.name + '> is not closed');
      if (src[i] === '>') { i++; break; }
      if (src[i] === '/' && src[i + 1] === '>') { i += 2; node.selfClosed = true; break; }
      const attr = /^([^\s=/>]+)\s*=\s*("([^"]*)"|'([^']*)')/.exec(src.slice(i));
      if (!attr) fail('Attribute of <' + node.name + '> is not readable');
      node.attrs[attr[1]] = decodeEntities(attr[3] !== undefined ? attr[3] : attr[4]);
      i += attr[0].length;
    }
    if (stack.length) stack[stack.length - 1].children.push(node);
    else if (root) fail('More than one root element');
    else root = node;
    if (node.selfClosed) delete node.selfClosed;
    else stack.push(node);
  }
  if (stack.length) fail('<' + stack[stack.length - 1].name + '> is not closed');
  if (!root) fail('The file contains no element');
  return root;
}

export const childrenOf = (node, local) => node.children.filter(child => child.local === local);

export const childOf = (node, local) => node.children.find(child => child.local === local) || null;

export const textOf = (node, local) => {
  const child = childOf(node, local);
  return child ? child.text.trim() : '';
};
