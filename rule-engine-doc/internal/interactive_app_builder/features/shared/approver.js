const RULE_CLASS = 'com.axonivy.utils.rule.engine.BusinessRuleTask';

export const APPROVER = { APPLY: 'f6', END: 'f11', CREATE_TASK: 'f40' };
export const NEW_MODEL = 'invoice-approver';
export const INPUT_FIELDS = 'in.invoice.total, in.invoice.discountPercent';
export const OUTPUT_FIELDS = 'in.invoice.approver';

export function extendedProcess(process) {
  const copy = JSON.parse(JSON.stringify(process));
  const byId = id => copy.elements.find(element => element.id === id);
  // the new task goes between the discount and the end, which moves to the right
  byId(APPROVER.APPLY).connect = [{ id: 'f41', to: APPROVER.CREATE_TASK }];
  byId(APPROVER.END).visual = { ...byId(APPROVER.END).visual, at: { x: 880, y: 64 } };
  const index = copy.elements.findIndex(element => element.id === APPROVER.END);
  copy.elements.splice(index, 0, {
    id: APPROVER.CREATE_TASK,
    type: 'ProgramInterface',
    name: ['Business Rule Task', 'approver'],
    config: {
      javaClass: RULE_CLASS,
      userConfig: { decision: '#create', newModel: NEW_MODEL, inputs: INPUT_FIELDS, outputs: OUTPUT_FIELDS }
    },
    visual: { at: { x: 720, y: 64 }, size: { width: 144, height: 60 } },
    connect: [{ id: 'f42', to: APPROVER.END }]
  });
  return copy;
}

export const withApprover = classes => classes.map(json => (json.simpleName !== 'Invoice' ? json : {
  ...json,
  fields: [...json.fields, { name: 'approver', comment: 'Decided by dmn/invoice-approver.dmn' }]
}));
