/** tour.chapter.<id> are the headings of the tutorial page, word for word. */
import { ABOUT_RULES } from '../shared/project.js';

export const strings = {
  en: {
    'lang': 'en',
    'tour.intro.title': 'What is DMN?',
    'tour.intro.text': 'A decision table, read and tried without any process: inputs and outputs, rules and their conditions, which rule wins, and a rule of your own.',
    'tour.intro.duration': 'about 4 min',
    'tour.chapter.read': 'Read a decision table',
    'tour.chapter.try': 'Try other inputs',
    'tour.chapter.first': 'First match wins',
    'tour.chapter.conditions': 'Conditions',
    'tour.chapter.add': 'Add a rule',

    'tour.table.title': 'Inputs and outputs',
    'tour.table.text': 'The table `discount` reads two inputs, one column each: the `customerTier` and the `orderAmount` of an invoice. It answers with two outputs: `discountPercent` and `discountReason`.',
    'tour.rule.title': 'A row is a rule',
    'tour.rule.text': 'Rule 6: a Silver customer, whatever the amount, gets 2 percent. A rule matches when all its conditions hold.',
    'tour.output.title': 'The reason comes along',
    'tour.output.text': 'The second output says why. The page of the demo shows it next to the discount, so nobody has to guess where a discount comes from.',
    'tour.dash.title': '- matches everything',
    'tour.dash.text': 'Rule 6 does not care about the order amount.',
    'tour.model.title': 'One model, one table',
    'tour.model.text': 'The file `dmn/invoice-discount.dmn` is a model with these two inputs and this one decision. The process of the demo sends the two values and gets the two outputs back.',

    'tour.tryStrip.title': 'Try it',
    'tour.tryStrip.text': 'Type the facts of an invoice here and the table answers. Nothing is sent anywhere: the table of this file decides.',
    'tour.sample.title': 'The sample invoice',
    'tour.sample.text': 'A Silver customer, an order of 9,200: rule 6 lights up, 2 percent.',
    'tour.gold.title': 'The same order for a Gold customer',
    'tour.gold.text': 'Rule 4 takes it: 5 percent.',
    'tour.large.title': 'Gold, 10,400',
    'tour.large.text': 'A fifth laptop brings the order above 10,000. Rule 3 asks for that and comes first: 8 percent.',

    'tour.several.title': 'Three rules match',
    'tour.several.text': 'A Silver customer with an order of 12,000 matches rule 5, but also rule 6 and rule 7. Only one decides.',
    'tour.policy.title': 'F for FIRST',
    'tour.policy.text': 'The hit policy of the table: the rules are tried from the top, and the first one that matches decides. So rule 5 wins: 4 percent.',
    'tour.order.title': 'The order is a rule too',
    'tour.order.text': 'Rule 5, for large orders, stands above rule 6 for that. The other way round, a Silver customer would get 2 percent whatever the amount.',
    'tour.catchAll.title': 'The last rule catches the rest',
    'tour.catchAll.text': 'Rule 7 has only `-`: it matches every invoice no other rule takes, so the table always has an answer. Keep it last.',

    'tour.text.title': 'A text',
    'tour.text.text': 'Texts are in double quotes: `"Silver"` matches exactly this tier.',
    'tour.compare.title': 'A comparison',
    'tour.compare.text': '`>= 10000`: 10,000 or more. `<`, `<=` and `>` work the same way.',
    'tour.below.title': '9,999',
    'tour.below.text': 'Just below: `>= 10000` of rule 5 does not hold, rule 6 takes it: 2 percent.',
    'tour.from.title': '10,000',
    'tour.from.text': '`>=` includes the number itself: rule 5 decides, 4 percent.',

    'tour.nothing.title': 'Nothing for Standard customers',
    'tour.nothing.text': 'A Standard customer with an order of 12,000 matches only the last rule: no discount.',
    'tour.added.title': 'Add rule',
    'tour.added.text': 'The new rule goes above the rule that catches everything, otherwise it could never decide. It starts as `-` everywhere.',
    'tour.written.title': 'Standard, 10,000 or more',
    'tour.written.text': 'The new rule gives large orders of Standard customers 1 percent, with its reason.',
    'tour.newResult.title': 'The same facts, a new answer',
    'tour.newResult.text': 'The new rule decides: 1 percent. The rules above it still win where they match.',
    'tour.whereEdit.title': 'Where tables change',
    'tour.whereEdit.text': 'A developer edits the .dmn file of the project in a DMN editor, a rule owner changes the rule in the Portal. The next pages show both.',

    'about.rules': ABOUT_RULES
  }
};
