/**
 * Turns an Axon Ivy process file (*.p.json) plus its data class (*.d.json) into the model
 * the simulation works on. The JSON is imported from the tutorial project at build time, so
 * the simulated process is always the real one.
 *
 * A process file can hold several starts; a lesson runs the one it is about.
 */

export const RULE_CLASS = 'com.axonivy.utils.rule.engine.BusinessRuleTask';

const ACTIVITY = { w: 112, h: 60 };
const EVENT = { w: 30, h: 30 };
const GATEWAY = { w: 32, h: 32 };
const BOUNDARY = { w: 22, h: 22 };

/** type → how the element is drawn (shape), simulated (kind) and titled in the inscription view. */
const TYPES = {
  RequestStart: { kind: 'start', shape: 'event', label: 'Start' },
  SignalStartEvent: { kind: 'start', shape: 'event', label: 'Signal Start' },
  ProgramStart: { kind: 'start', shape: 'event', label: 'Program Start' },
  CallSubStart: { kind: 'start', shape: 'event', label: 'Sub Start' },
  TaskEnd: { kind: 'end', shape: 'event', label: 'End' },
  CallSubEnd: { kind: 'end', shape: 'event', label: 'Sub End' },
  ErrorEnd: { kind: 'end', shape: 'event', label: 'Error End' },
  TaskSwitchEvent: { kind: 'task', shape: 'event', label: 'Task' },
  ErrorBoundaryEvent: { kind: 'boundary', shape: 'event', label: 'Error Boundary' },
  Script: { kind: 'script', shape: 'activity', label: 'Script' },
  ProgramInterface: { kind: 'program', shape: 'activity', label: 'Program' },
  UserTask: { kind: 'usertask', shape: 'activity', label: 'User Task' },
  DialogCall: { kind: 'activity', shape: 'activity', label: 'User Dialog' },
  SubProcessCall: { kind: 'activity', shape: 'activity', label: 'Call' },
  Alternative: { kind: 'gateway', shape: 'gateway', label: 'Alternative' },
  ProcessAnnotation: { kind: 'note', shape: 'note', label: 'Note' }
};

const clone = value => JSON.parse(JSON.stringify(value));
const joinLines = value => (Array.isArray(value) ? value.join('\n') : value == null ? '' : String(value));

function toElement(raw, colors, doc = 'process', prefix = '', attachedTo = null) {
  const def = TYPES[raw.type] || { kind: 'activity', shape: 'activity', label: raw.type };
  const visual = raw.visual || {};
  const at = visual.at || { x: 0, y: 0 };
  const base = def.kind === 'boundary' ? BOUNDARY : def.shape === 'event' ? EVENT : def.shape === 'gateway' ? GATEWAY : ACTIVITY;
  const size = visual.size || {};
  const config = clone(raw.config || {});
  if (config.output && config.output.code !== undefined) config.output.code = joinLines(config.output.code);
  if (config.userConfig) {
    // Line endings in stored prompts are CRLF; the editor works with LF.
    Object.keys(config.userConfig).forEach(k => {
      if (typeof config.userConfig[k] === 'string') config.userConfig[k] = config.userConfig[k].replace(/\r\n/g, '\n');
    });
  }
  const isRule = raw.type === 'ProgramInterface' && config.javaClass === RULE_CLASS;
  if (isRule && !config.userConfig) config.userConfig = {};
  return {
    id: prefix + raw.id,
    rawId: raw.id, // the id inside its own file
    doc,
    attachedTo, // a boundary event sits on the element that throws what it catches
    labelOffset: visual.labelOffset || null,
    type: raw.type,
    kind: isRule ? 'rule' : def.kind,
    shape: def.shape,
    typeLabel: def.label,
    isRule,
    name: joinLines(raw.name),
    description: joinLines(raw.description !== undefined ? raw.description : visual.description),
    tags: (raw.tags || []).slice(),
    x: at.x,
    y: at.y,
    w: size.width || base.w,
    h: size.height || base.h,
    colorName: visual.color || null,
    color: visual.color ? (colors[visual.color] || null) : null,
    config,
    outgoing: (raw.connect || []).map(c => ({
      id: prefix + c.id, rawId: c.id, to: prefix + c.to, via: c.via ? clone(c.via) : null,
      // the label of a connector, e.g. the answer an Alternative takes it for; segment: on which part of the line
      label: c.label && c.label.name ? String(c.label.name) : '',
      labelSegment: c.label && c.label.segment != null ? Number(c.label.segment) : null
    })),
    removed: false
  };
}

/**
 * @param {Object} processJson   contents of Feature01.p.json
 * @param {Object} dataClassJson contents of Feature01Data.d.json
 * @param {{ file?: string, project?: string, classes?: Object[], cms?: Object }} [meta]
 *   `classes`: further *.d.json files; `cms`: CMS objects by path (see macro.js)
 */
/** A data class file (*.d.json) as { name, simpleName, fields: [{ name, type, comment }] }. */
function toClass(json) {
  return {
    name: (json.namespace ? json.namespace + '.' : '') + json.simpleName,
    simpleName: json.simpleName,
    fields: (json.fields || []).map(f => ({ name: f.name, type: f.type || 'String', comment: f.comment || '' }))
  };
}

export function loadProcess(processJson, dataClassJson, meta = {}) {
  const colors = (processJson.layout && processJson.layout.colors) || {};
  // boundary events are stored inside the element they are attached to; here each is an element of its own
  const flatten = (list, elementColors, doc, prefix) => {
    const out = [];
    (list || []).forEach(raw => {
      out.push(toElement(raw, elementColors, doc, prefix));
      (raw.boundaries || []).forEach(boundary => {
        const element = toElement(boundary, elementColors, doc, prefix, prefix + raw.id);
        // an event on the upper edge of its element carries its label above, clear of the element
        element.labelAbove = element.y < ((raw.visual || {}).at || { y: 0 }).y;
        out.push(element);
      });
    });
    return out;
  };
  const elements = flatten(processJson.elements, colors, 'process', '');
  const dataClass = toClass(dataClassJson);
  const docs = [{ id: 'process', file: meta.file || '', processId: processJson.id, dataClass }];
  // other data classes of the project that the process refers to, by full name
  const classes = {};
  (meta.classes || []).forEach(json => {
    const cls = toClass(json);
    classes[cls.name] = cls;
  });
  const model = {
    id: processJson.id,
    file: meta.file || '',
    project: meta.project || '',
    dataClass,
    docs,
    classes,
    cms: meta.cms || {}, // CMS objects the process refers to, by path
    elements,
    colors
  };
  model.original = snapshot(model);
  return model;
}

/** Editable state of every element — used for "reset" and for change detection. */
export function snapshot(model) {
  return clone(model.elements.map(e => ({ id: e.id, name: e.name, description: e.description, tags: e.tags, x: e.x, y: e.y, config: e.config, removed: e.removed })));
}

export function restore(model, snap) {
  snap.forEach(s => {
    const el = elementById(model, s.id);
    if (!el) return;
    el.name = s.name;
    el.description = s.description;
    el.tags = (s.tags || []).slice();
    el.x = s.x;
    el.y = s.y;
    el.config = clone(s.config);
    el.removed = s.removed;
  });
}

export function elementById(model, id) {
  return model.elements.find(e => e.id === id) || null;
}

export function startElement(model) {
  return model.elements.find(e => e.kind === 'start' && e.doc === 'process') || null;
}

/** The starts of the process a run can begin at. */
export function startElements(model) {
  return model.elements.filter(e => e.kind === 'start' && e.doc === 'process' && e.type !== 'CallSubStart');
}

/**
 * The Error Boundary Event on an element that catches an error code: its own code is the
 * same, is empty, or is a pattern with * that fits.
 */
export function boundaryFor(model, element, errorCode) {
  const fits = pattern => {
    const wanted = String(pattern || '').trim();
    if (!wanted || wanted === errorCode) return true;
    // error codes are hierarchical: rule:engine catches rule:engine:timeout
    if (errorCode.startsWith(wanted + ':')) return true;
    if (!wanted.includes('*')) return false;
    // a pattern such as smartworkflow:guardrail:* : the parts between the stars have to appear in that order
    const parts = wanted.split('*');
    let from = 0;
    for (let i = 0; i < parts.length; i++) {
      const found = i === 0 ? (errorCode.startsWith(parts[0]) ? 0 : -1) : errorCode.indexOf(parts[i], from);
      if (found < 0) return false;
      from = found + parts[i].length;
    }
    return parts[parts.length - 1] === '' || errorCode.endsWith(parts[parts.length - 1]);
  };
  return model.elements.find(e => e.kind === 'boundary' && e.attachedTo === element.id && !e.removed && fits(e.config.errorCode)) || null;
}

/** The file an element belongs to: { id, file, processId, dataClass }. */
export function docOf(model, element) {
  return model.docs.find(d => d.id === element.doc) || model.docs[0];
}

/** A view of one file of the model, for a stage: only its elements. */
export function docView(model, docId) {
  return { get elements() { return model.elements.filter(e => e.doc === docId); } };
}

export function ruleElements(model) {
  return model.elements.filter(e => e.isRule);
}

/** All connectors as { id, from, to, via } with resolved elements. */
export function connectors(model) {
  const list = [];
  model.elements.forEach(from => {
    from.outgoing.forEach(c => {
      const to = elementById(model, c.to);
      if (to) list.push({ id: c.id, from, to, via: c.via, label: c.label || '', labelSegment: c.labelSegment == null ? null : c.labelSegment });
    });
  });
  return list;
}

/** The element reached through the first outgoing connector (the tutorial flows are linear). */
export function nextOf(model, element) {
  const out = element.outgoing[0];
  return out ? { connectorId: out.id, element: elementById(model, out.to) } : null;
}

export function originalOf(model, id) {
  return model.original.find(s => s.id === id) || null;
}

/** The configuration map of a Business Rule Task (decision, inputs, outputs, newModel). */
export function ruleConfig(element) {
  if (!element.config.userConfig) element.config.userConfig = {};
  return element.config.userConfig;
}

/**
 * Lists in the stored config are JSON-ish strings such as `["a", "b"]` or `"OpenAI"`.
 * Returns their items as plain strings.
 */
export function configList(value) {
  if (value == null) return [];
  const raw = String(value).trim();
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) return parsed.map(String).filter(Boolean);
    return parsed === '' || parsed == null ? [] : [String(parsed)];
  } catch (e) {
    return raw.split(',').map(s => s.trim().replace(/^["']|["']$/g, '')).filter(Boolean);
  }
}
