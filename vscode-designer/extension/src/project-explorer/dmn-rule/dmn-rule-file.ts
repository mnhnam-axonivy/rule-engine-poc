import { DmnTypes, generateDmn } from './dmn-generator';

export const DMN_FOLDER = 'dmn';
export const DMN_EXTENSION = '.dmn';

const DMN_RULE_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

/** The name of the model is the name of its file, as the Business Rule Task of the rule engine finds it. */
export const dmnRuleName = (typed: string) => {
  const name = typed.trim();
  return name.toLowerCase().endsWith(DMN_EXTENSION) ? name.slice(0, -DMN_EXTENSION.length) : name;
};

export const isValidDmnRuleName = (name: string) => DMN_RULE_NAME.test(name);

/** A decision table with one input, one output and a placeholder rule, until fields are chosen for it. */
export const dmnRuleTemplate = (model: string) =>
  generateDmn({
    modelName: model,
    inputs: [{ name: 'input', type: DmnTypes.STRING }],
    outputs: [{ name: 'output', type: DmnTypes.STRING }],
    description:
      'The Business Rule Task finds inputs and results by name: name the input and the decision like fields of the process data, then add your rules.'
  });
