/**
 * Guide 01 — texts of the walkthrough. tour.chapter.<id> are the headings of the tutorial
 * page, word for word.
 */
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
    'tour.table.text': 'The table `lineDiscountTable` reads four inputs, one column each: `category`, `quantity`, `customerTier` and `country`. It answers with two outputs: `percent` and `reason`.',
    'tour.rule.title': 'A row is a rule',
    'tour.rule.text': 'Rule 6: Hardware, from 10 to 49 units, for any customer tier and any country, gives 6 percent. A rule matches when all its conditions hold.',
    'tour.output.title': 'The reason comes along',
    'tour.output.text': 'The second output says why. The page of the demo shows it next to each line, so nobody has to guess where a discount comes from.',
    'tour.dash.title': '- matches everything',
    'tour.dash.text': 'Rule 6 does not care about the customer tier or the country.',
    'tour.function.title': 'One table for every line',
    'tour.function.text': 'This table is a function of the model, a business knowledge model. The model calls it once for every line of an invoice, with the category and the quantity of that line.',

    'tour.tryStrip.title': 'Try it',
    'tour.tryStrip.text': 'Type the facts of one line here and the table answers. Nothing is sent anywhere: the table of this file decides.',
    'tour.laptops.title': '10 laptops for a Silver customer',
    'tour.laptops.text': 'Rule 6 lights up: 6 percent, the first line of the sample invoice.',
    'tour.gold.title': 'The same laptops for a Gold customer',
    'tour.gold.text': 'Rule 5 asks for Gold or Platinum and comes first: 10 percent.',
    'tour.volume.title': '60 laptops',
    'tour.volume.text': 'Rule 4 takes 50 units or more: 12 percent, whatever the tier.',

    'tour.several.title': 'Three rules match',
    'tour.several.text': '12 books for a customer in Germany match rule 1, but also rule 2 and rule 10. Only one decides.',
    'tour.policy.title': 'F for FIRST',
    'tour.policy.text': 'The hit policy of the table: the rules are tried from the top, and the first one that matches decides. So rule 1 wins: 0 percent.',
    'tour.order.title': 'The order is a rule too',
    'tour.order.text': 'Books have a fixed price in Germany and Austria. Rule 1 stands above rule 2 for that: the other way round, German books would get 5 percent.',
    'tour.catchAll.title': 'The last rule catches the rest',
    'tour.catchAll.text': 'Rule 10 has only `-`: it matches every line no other rule takes, so the table always has an answer. Keep it last.',

    'tour.text.title': 'A text',
    'tour.text.text': 'Texts are in double quotes: `"Books"` matches exactly this category.',
    'tour.list.title': 'One of several',
    'tour.list.text': '`"DE", "AT"` matches either of the two countries.',
    'tour.range.title': 'A range',
    'tour.range.text': '`[10..50)` is from 10 up to, but not including, 50. The square bracket includes, the round one excludes.',
    'tour.compare.title': 'A comparison',
    'tour.compare.text': '`>= 50`: 50 units or more. `<`, `<=` and `>` work the same way.',
    'tour.belowRange.title': '19 seats',
    'tour.belowRange.text': '`[5..20)` of rule 8 takes them: 5 percent.',
    'tour.nextRange.title': 'One seat more',
    'tour.nextRange.text': '20 seats are no longer in `[5..20)`, but in `>= 20` of rule 7: 15 percent. The ranges meet without a gap.',

    'tour.nothing.title': 'Nothing for 30 boxes yet',
    'tour.nothing.text': 'Consumables get a discount from 50 on. 30 boxes match only the last rule: 0 percent.',
    'tour.added.title': 'Add rule',
    'tour.added.text': 'The new rule goes above the rule that catches everything, otherwise it could never decide. It starts as `-` everywhere.',
    'tour.written.title': 'Consumables from 20 on',
    'tour.written.text': 'The new rule gives them 4 percent, with its reason.',
    'tour.newResult.title': 'The same facts, a new answer',
    'tour.newResult.text': 'The new rule decides: 4 percent. The rules above it still win where they match.',
    'tour.whereEdit.title': 'Where tables change',
    'tour.whereEdit.text': 'A developer edits the .dmn file of the project in a DMN editor, a rule owner changes the rule in the Portal. The next pages show both.',

    'about.rules': ABOUT_RULES
  }
};
