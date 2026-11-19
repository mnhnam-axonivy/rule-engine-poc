/**
 * The demo process extended the way the page "Create a new DMN from scratch" describes it: the result gets a field
 * deliveryDays, and a second Business Rule Task after the pricing asks for a new DMN, delivery-days, from
 * in.invoice.country and in.invoice.expressDelivery. Plain functions over the files of the demo, so that the unit
 * tests use them as the lesson does.
 */
const RULE_CLASS = 'com.axonivy.utils.rule.engine.BusinessRuleTask';

/** Element ids: those of the demo, and the new Business Rule Task. */
export const DELIVERY = { RULE_TASK: 'f4', LOG: 'f6', END: 'f11', CREATE_TASK: 'f40' };
export const NEW_MODEL = 'delivery-days';
export const INPUT_FIELDS = 'in.invoice.country, in.invoice.expressDelivery';
export const OUTPUT_FIELDS = 'in.pricing.deliveryDays';

export function extendedProcess(process) {
  const copy = JSON.parse(JSON.stringify(process));
  const byId = id => copy.elements.find(element => element.id === id);
  // the new task goes between the pricing and the log, which move to the right
  byId(DELIVERY.RULE_TASK).connect = [{ id: 'f41', to: DELIVERY.CREATE_TASK }];
  byId(DELIVERY.LOG).visual = { ...byId(DELIVERY.LOG).visual, at: { x: 800, y: 64 } };
  byId(DELIVERY.END).visual = { ...byId(DELIVERY.END).visual, at: { x: 936, y: 64 } };
  const index = copy.elements.findIndex(element => element.id === DELIVERY.LOG);
  copy.elements.splice(index, 0, {
    id: DELIVERY.CREATE_TASK,
    type: 'ProgramInterface',
    name: ['Business Rule Task', 'delivery days'],
    config: {
      javaClass: RULE_CLASS,
      userConfig: { decision: '#create', newModel: NEW_MODEL, inputs: INPUT_FIELDS, outputs: OUTPUT_FIELDS }
    },
    visual: { at: { x: 616, y: 64 }, size: { width: 144, height: 60 } },
    connect: [{ id: 'f42', to: DELIVERY.LOG }]
  });
  return copy;
}

export const withDeliveryDays = classes => classes.map(json => (json.simpleName !== 'PricingResult' ? json : {
  ...json,
  fields: [...json.fields, { name: 'deliveryDays', type: 'java.lang.Integer', comment: 'Decided by dmn/delivery-days.dmn' }]
}));


export const PRICING_RESULT = 'com.axonivy.utils.rule.engine.demo.PricingResult';
