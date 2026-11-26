import process from '../../../../../rule-engine-demo/process/InvoicePricing.p.json';
import dataClass from '../../../../../rule-engine-demo/dataclass/com/axonivy/utils/rule/engine/demo/InvoicePricingData.d.json';
import invoice from '../../../../../rule-engine-demo/dataclass/com/axonivy/utils/rule/engine/demo/Invoice.d.json';
import invoiceLine from '../../../../../rule-engine-demo/dataclass/com/axonivy/utils/rule/engine/demo/InvoiceLine.d.json';
import discount from '../../../../../rule-engine-demo/dmn/invoice-discount.dmn';
import { MODEL, DECISION, SAMPLE_INVOICE } from './invoice.js';

export * from './invoice.js';

/** The settings of the connector, as the tutorial sets them up (rule-engine/config/variables.yaml). */
export const VARIABLES = `Variables:
  RuleEngine:
    # URL of the decision runtime, the container
    Url: http://localhost:8090
    # Shared secret of the runtime, empty for local development
    ApiKey: ""
    # Seconds to wait for a decision
    TimeoutSeconds: "10"
    # Register the .dmn files of the project on the runtime
    AutoDeploy: "true"
`;

export const project = {
  file: 'InvoicePricing.p.json',
  project: 'rule-engine-demo',
  process,
  dataClass,
  classes: [invoice, invoiceLine],
  dmn: [{ model: MODEL, file: 'dmn/invoice-discount.dmn', text: discount }],
  variables: VARIABLES,
  variablesPath: 'config/variables.yaml',
  params: { invoice: SAMPLE_INVOICE },
  tryTable: DECISION,
  portalTable: DECISION
};

export const portalProject = { ...project, portal: true };

export const ABOUT_RULES = [
  'It starts empty. A .dmn file of the project becomes a version when a Business Rule Task evaluates it for the first time, and again whenever the file has changed.',
  'A version from the project goes live as long as the active version also came from a project. A version activated by a rule owner is never replaced by the project.',
  'It evaluates decision tables with the hit policies FIRST, UNIQUE and COLLECT, FEEL literal expressions and contexts, and the functions (business knowledge models) of a model, as the DMN engine does.',
  'It checks the inputs against their types: a value outside the allowed values, or null where they do not allow it, is refused.',
  'It listens on http://localhost:8090. Stopped, slow, or with another URL in variables.yaml, the Business Rule Task throws the same BPMN errors as with the real container.',
  'The API key and effective dates are not simulated.'
].join('|');
