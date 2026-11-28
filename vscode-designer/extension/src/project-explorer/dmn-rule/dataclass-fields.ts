import fs from 'fs';
import path from 'path';
import { type DmnType, DmnTypes } from './dmn-generator';

export type DataClassField = { name: string; type: string };

export type DataClass = {
  qualifiedName: string;
  simpleName: string;
  namespace: string;
  project: string;
  projectDir: string;
  fields: DataClassField[];
};

/** A field of a data class that can become an input or an output of a DMN rule. */
export type RuleField = {
  id: string;
  name: string;
  dataClass: string;
  namespace: string;
  project: string;
  type: string;
};

const DATACLASS_FOLDER = 'dataclass';
const DATACLASS_EXTENSION = '.d.json';
const DEFAULT_TYPE = 'String';
const MAX_DEPTH = 6;

const typeSet = (...names: string[]) => new Set(names);
const TEXT = typeSet(
  'String',
  'java.lang.String',
  'java.lang.CharSequence',
  'Character',
  'java.lang.Character',
  'char',
  'java.util.UUID',
  'java.net.URI',
  'java.net.URL',
  'java.util.Locale',
  'java.util.Currency'
);
const NUMBER = typeSet(
  'Number',
  'java.lang.Number',
  'Integer',
  'java.lang.Integer',
  'Long',
  'java.lang.Long',
  'Short',
  'java.lang.Short',
  'Byte',
  'java.lang.Byte',
  'Double',
  'java.lang.Double',
  'Float',
  'java.lang.Float',
  'BigDecimal',
  'java.math.BigDecimal',
  'BigInteger',
  'java.math.BigInteger',
  'int',
  'long',
  'short',
  'byte',
  'double',
  'float'
);
const BOOLEAN = typeSet('Boolean', 'java.lang.Boolean', 'boolean');
const DATE = typeSet('Date', 'ch.ivyteam.ivy.scripting.objects.Date', 'java.time.LocalDate', 'java.sql.Date');
const DATE_TIME = typeSet(
  'DateTime',
  'ch.ivyteam.ivy.scripting.objects.DateTime',
  'java.util.Date',
  'java.time.LocalDateTime',
  'java.time.OffsetDateTime',
  'java.time.ZonedDateTime',
  'java.time.Instant',
  'java.sql.Timestamp',
  'java.util.Calendar',
  'java.util.GregorianCalendar'
);
const TIME = typeSet('Time', 'ch.ivyteam.ivy.scripting.objects.Time', 'java.time.LocalTime', 'java.time.OffsetTime', 'java.sql.Time');
const DAYS_TIME_DURATION = typeSet('Duration', 'ch.ivyteam.ivy.scripting.objects.Duration', 'java.time.Duration');
const YEARS_MONTHS_DURATION = typeSet('java.time.Period');
const COLLECTION = typeSet(
  'List',
  'ch.ivyteam.ivy.scripting.objects.List',
  'java.util.List',
  'java.util.ArrayList',
  'java.util.Set',
  'java.util.HashSet',
  'java.util.Collection',
  'java.lang.Iterable'
);

const simpleNameOf = (qualifiedName: string) => qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);

/** @returns the declared type without packages, e.g. List<Address> for List<com.acme.Address> */
export const displayType = (type: string) => type.replace(/[\w$]+(\.[\w$]+)+/g, simpleNameOf);

const findFiles = async (dir: string, extension: string) => {
  try {
    const files = await fs.promises.readdir(dir, { recursive: true });
    return files.filter(file => file.endsWith(extension)).map(file => path.join(dir, file));
  } catch {
    return [];
  }
};

export const parseDataClass = (json: string, projectDir: string): DataClass | undefined => {
  const content = JSON.parse(json) as { simpleName?: string; namespace?: string; fields?: Array<{ name?: string; type?: string }> };
  if (!content.simpleName) {
    return undefined;
  }
  const namespace = content.namespace ?? '';
  return {
    qualifiedName: namespace ? `${namespace}.${content.simpleName}` : content.simpleName,
    simpleName: content.simpleName,
    namespace,
    project: path.basename(projectDir),
    projectDir,
    fields: (content.fields ?? []).flatMap(field => (field.name ? [{ name: field.name, type: field.type?.trim() || DEFAULT_TYPE }] : []))
  };
};

/** @returns the data classes of the projects, read from their dataclass folders */
const readDataClass = async (file: string, projectDir: string) => {
  try {
    const dataClass = parseDataClass(await fs.promises.readFile(file, 'utf-8'), projectDir);
    return dataClass ? [dataClass] : [];
  } catch {
    return [];
  }
};

export const readDataClasses = async (projectDirs: string[]) => {
  const perProject = await Promise.all(
    projectDirs.map(async projectDir => {
      const files = await findFiles(path.join(projectDir, DATACLASS_FOLDER), DATACLASS_EXTENSION);
      return (await Promise.all(files.map(file => readDataClass(file, projectDir)))).flat();
    })
  );
  return perProject.flat();
};

export const ruleFields = (dataClasses: DataClass[]): RuleField[] =>
  dataClasses.flatMap(dataClass =>
    dataClass.fields.map(field => ({
      id: `${dataClass.project}:${dataClass.qualifiedName}.${field.name}`,
      name: field.name,
      dataClass: dataClass.simpleName,
      namespace: dataClass.namespace,
      project: dataClass.project,
      type: displayType(field.type)
    }))
  );

/** @returns the constants of a Java enum declared in the source, or undefined if the source is no enum */
export const enumConstants = (javaSource: string, simpleName: string) => {
  const source = javaSource.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/[^\n]*/g, ' ');
  const start = new RegExp(`\\benum\\s+${simpleName.replace(/\$/g, '\\$')}\\b[^{]*\\{`).exec(source);
  if (!start) {
    return undefined;
  }
  // the constants end at the first ';' or '}' outside of the arguments of a constant
  const constants: string[] = [];
  let depth = 0;
  let current = '';
  for (const c of source.substring(start.index + start[0].length)) {
    if (depth === 0 && (c === ';' || c === '}' || c === ',')) {
      const name = /^(?:@[\w.]+(?:\([^)]*\))?\s*)*([A-Za-z_$][\w$]*)/.exec(current.trim())?.[1];
      if (name) {
        constants.push(name);
      }
      current = '';
      if (c !== ',') {
        break;
      }
      continue;
    }
    if (c === '(' || c === '{') {
      depth++;
    } else if (c === ')' || c === '}') {
      depth--;
    }
    current += c;
  }
  return constants;
};

const readEnum = (projectDirs: string[], qualifiedName: string) => {
  for (const projectDir of projectDirs) {
    const source = path.join(projectDir, 'src', ...qualifiedName.split('.')) + '.java';
    if (fs.existsSync(source)) {
      return enumConstants(fs.readFileSync(source, 'utf-8'), simpleNameOf(qualifiedName));
    }
  }
  return undefined;
};

/**
 * Maps the declared types of data class fields to DMN types, like the generator of the rule engine maps Java types:
 * a data class becomes a structure of its fields, an enum the list of its constants, a list a collection.
 */
export class DmnTypeMapper {
  private readonly dataClasses = new Map<string, DataClass>();
  private readonly structureNames = new Map<string, string>();
  private readonly enumOf: (qualifiedName: string) => string[] | undefined;

  constructor(dataClasses: DataClass[], enumOf: (qualifiedName: string) => string[] | undefined) {
    this.enumOf = enumOf;
    dataClasses.forEach(dataClass => {
      if (!this.dataClasses.has(dataClass.qualifiedName)) {
        this.dataClasses.set(dataClass.qualifiedName, dataClass);
      }
    });
  }

  static forProjects(dataClasses: DataClass[], projectDirs: string[]) {
    return new DmnTypeMapper(dataClasses, qualifiedName => readEnum(projectDirs, qualifiedName));
  }

  toDmnType(declared: string, path: string[] = []): DmnType {
    const type = declared.trim();
    const [, raw, argument] = /^([\w$.]+)\s*<(.+)>$/.exec(type) ?? [];
    if (raw && argument) {
      return COLLECTION.has(raw) ? { kind: 'collection', element: this.toDmnType(argument, path) } : this.toDmnType(raw, path);
    }
    if (type.endsWith('[]')) {
      const element = type.slice(0, -2);
      return element === 'byte' ? DmnTypes.ANY : { kind: 'collection', element: this.toDmnType(element, path) };
    }
    return this.simpleType(type) ?? this.namedType(type, path);
  }

  private simpleType(type: string) {
    if (TEXT.has(type)) return DmnTypes.STRING;
    if (BOOLEAN.has(type)) return DmnTypes.BOOLEAN;
    if (NUMBER.has(type)) return DmnTypes.NUMBER;
    if (DATE.has(type)) return DmnTypes.DATE;
    if (DATE_TIME.has(type)) return DmnTypes.DATE_TIME;
    if (TIME.has(type)) return DmnTypes.TIME;
    if (DAYS_TIME_DURATION.has(type)) return DmnTypes.DAYS_TIME_DURATION;
    if (YEARS_MONTHS_DURATION.has(type)) return DmnTypes.YEARS_MONTHS_DURATION;
    if (COLLECTION.has(type)) return { kind: 'collection', element: DmnTypes.ANY } satisfies DmnType;
    return undefined;
  }

  private namedType(type: string, path: string[]): DmnType {
    const dataClass = this.dataClasses.get(type);
    if (dataClass) {
      return this.structure(dataClass, path);
    }
    const constants = this.enumOf(type);
    if (constants) {
      return { kind: 'enumeration', name: this.typeName(type), values: constants };
    }
    return DmnTypes.ANY;
  }

  private structure(dataClass: DataClass, path: string[]): DmnType {
    if (path.includes(dataClass.qualifiedName) || path.length >= MAX_DEPTH || dataClass.fields.length === 0) {
      return DmnTypes.ANY; // recursive data structures end here
    }
    const nested = [...path, dataClass.qualifiedName];
    return {
      kind: 'structure',
      name: this.typeName(dataClass.qualifiedName),
      fields: dataClass.fields.map(field => ({ name: field.name, type: this.toDmnType(field.type, nested) }))
    };
  }

  /** Names of the structures and enums, so two classes with the same simple name stay apart. */
  private typeName(qualifiedName: string) {
    const base = `t${simpleNameOf(qualifiedName)}`;
    let name = base;
    for (let index = 2; this.structureNames.has(name) && this.structureNames.get(name) !== qualifiedName; index++) {
      name = `${base}${index}`;
    }
    this.structureNames.set(name, qualifiedName);
    return name;
  }
}
