/**
 * What every lesson simulates: the demo project as it is. The callable sub process InvoicePricing, its data
 * classes and dmn/invoice-pricing.dmn are imported from rule-engine-demo at build time, so the simulation
 * shows the real files. The page of the demo calls the process with an invoice; the simulation passes the
 * sample invoice of the demo (InvoiceSamples.standard), dated the 19th of November 2026.
 */
import process from '../../../../../rule-engine-demo/process/InvoicePricing.p.json';
import dataClass from '../../../../../rule-engine-demo/dataclass/com/axonivy/utils/rule/engine/demo/InvoicePricingData.d.json';
import salesInvoice from '../../../../../rule-engine-demo/dataclass/com/axonivy/utils/rule/engine/demo/SalesInvoice.d.json';
import invoiceLine from '../../../../../rule-engine-demo/dataclass/com/axonivy/utils/rule/engine/demo/InvoiceLine.d.json';
import pricingResult from '../../../../../rule-engine-demo/dataclass/com/axonivy/utils/rule/engine/demo/PricingResult.d.json';
import pricedLine from '../../../../../rule-engine-demo/dataclass/com/axonivy/utils/rule/engine/demo/PricedLine.d.json';
import discount from '../../../../../rule-engine-demo/dataclass/com/axonivy/utils/rule/engine/demo/Discount.d.json';
import shipping from '../../../../../rule-engine-demo/dataclass/com/axonivy/utils/rule/engine/demo/Shipping.d.json';
import totals from '../../../../../rule-engine-demo/dataclass/com/axonivy/utils/rule/engine/demo/InvoiceTotals.d.json';
import approval from '../../../../../rule-engine-demo/dataclass/com/axonivy/utils/rule/engine/demo/Approval.d.json';
import pricing from '../../../../../rule-engine-demo/dmn/invoice-pricing.dmn';
import { MODEL, SAMPLE_INVOICE } from './invoice.js';

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

/** The parts of a lesson definition that describe the project. */
export const project = {
  file: 'InvoicePricing.p.json',
  project: 'rule-engine-demo',
  process,
  dataClass,
  classes: [salesInvoice, invoiceLine, pricingResult, pricedLine, discount, shipping, totals, approval],
  dmn: [{ model: MODEL, file: 'dmn/invoice-pricing.dmn', text: pricing }],
  variables: VARIABLES,
  variablesPath: 'config/variables.yaml',
  // the caller of the callable sub process: the page of the demo, with the sample invoice
  params: { invoice: SAMPLE_INVOICE },
  // "Try it" in the editor of the .dmn file works on this table
  tryTable: 'lineDiscountTable',
  // a new input in the Portal goes into this table
  portalTable: 'orderDiscounts'
};

/** The project with the tab Portal: Admin Settings › Rule Management, see runtime/ui/portal.js. */
export const portalProject = { ...project, portal: true };

/** What the simulation does and does not do, shown under "About this simulation". */
export const ABOUT_RULES = [
  'It starts empty. A .dmn file of the project becomes a version when a Business Rule Task evaluates it for the first time, and again whenever the file has changed.',
  'A version from the project goes live as long as the active version also came from a project. A version activated by a rule owner is never replaced by the project.',
  'It evaluates decision tables with the hit policies FIRST, UNIQUE and COLLECT, FEEL literal expressions and contexts, and the functions (business knowledge models) of a model, as the DMN engine does.',
  'It checks the inputs against their types: a value outside the allowed values, or null where they do not allow it, is refused.',
  'It listens on http://localhost:8090. Stopped, slow, or with another URL in variables.yaml, the Business Rule Task throws the same BPMN errors as with the real container.',
  'The API key and effective dates are not simulated.'
].join('|');
