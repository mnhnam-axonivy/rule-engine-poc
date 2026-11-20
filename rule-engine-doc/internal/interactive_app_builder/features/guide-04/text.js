/**
 * Guide 04 — texts of the walkthrough. tour.chapter.<id> are the headings of the tutorial
 * page, word for word.
 */
import { ABOUT_RULES } from '../shared/project.js';

export const strings = {
  en: {
    'lang': 'en',
    'tour.intro.title': 'Use an existing DMN decision',
    'tour.intro.text': 'The one field the element needs, how it finds the fields of the process data by their names, what an empty field sends, what happens without a field, and how a Script before the element prepares a value.',
    'tour.intro.duration': 'about 5 min',
    'tour.chapter.decision': 'Which fields do I have to fill in?',
    'tour.chapter.byName': 'How does the element find a field by name?',
    'tour.chapter.nullValue': 'What is sent when a field is empty?',
    'tour.chapter.noField': 'What happens when an input has no field?',
    'tour.chapter.script': 'How do I prepare a value for the decision?',

    'tour.decisionField.title': 'DMN decision',
    'tour.decisionField.text': 'The one field every Business Rule Task needs: what to evaluate, here `invoice-discount › discount`, the decision of the file `dmn/invoice-discount.dmn`. Each entry of the dropdown lists what it needs and gives, with the types.',
    'tour.fieldsEmpty.title': 'New DMN: not used',
    'tour.fieldsEmpty.text': 'The group New DMN is closed, and empty. Its fields only describe a new file. With an existing decision they are not used.',
    'tour.enough.title': 'That is enough',
    'tour.enough.text': 'The process runs and the discount is decided. Nothing else was configured.',

    'tour.namesToFind.title': 'The names to look for',
    'tour.namesToFind.text': 'The model needs two inputs: `customerTier` and `orderAmount`. These names are what the element looks for in the process data. The model itself never sees the process data.',
    'tour.levelZero.title': 'Level 0: the fields of in',
    'tour.levelZero.text': 'The search starts with the fields of the process data itself: `invoice`, `errorCode` and `errorMessage`. None has one of the two names.',
    'tour.levelOne.title': 'Level 1: inside invoice',
    'tour.levelOne.text': 'So it goes one level down, into each data class of `in`. `invoice` has both. The search would go on down to 4 levels below `in`; it does not look inside a list such as the lines.',
    'tour.foundIn.title': 'What is sent',
    'tour.foundIn.text': 'For each input the call shows the field it was read from, both in `in.invoice`. Only these values go to the model: the customer name, the lines and the invoice number stay in the process.',
    'tour.resultsBack.title': 'The results go back the same way',
    'tour.resultsBack.text': 'The result of `discount` is a structure with two entries. The process data has no field `discount`, so each entry goes to the field with its own name: `discountPercent` and `discountReason`, found in `in.invoice`.',
    'tour.closestWins.title': 'The first match wins',
    'tour.closestWins.text': 'The search stops at the first field with the name. A field closer to `in` beats a deeper one: `in.customerTier` would win over `in.invoice.customerTier`.',
    'tour.sameLevel.title': 'Watch out: the same name twice on one level',
    'tour.sameLevel.text': 'With `in.invoice.customerTier` and `in.customer.customerTier`, the element takes the one whose parent comes first in the data class, without a warning. To send the other one, give it a field of its own closer to `in`, set in a Script before the element: `in.customerTier = in.customer.customerTier;`.',
    'tour.caseAndBlanks.title': 'Exact names first',
    'tour.caseAndBlanks.text': 'Only if no field has exactly the name does the element search again, ignoring case and blanks: an input `Customer Tier` of a hand-written model finds the field `customerTier`.',

    'tour.noTier.title': 'No customer tier',
    'tour.noTier.text': 'One more line in the Script clears the customer tier, as if the page had not set it.',
    'tour.sentNull.title': 'Sent as null',
    'tour.sentNull.text': '`customerTier` is still sent, as `null`. The model gets every input it needs, with or without a value.',
    'tour.dashMatches.title': 'Only - matches null',
    'tour.dashMatches.text': 'No rule for a tier matches `null`. The last rule has `-` for the tier and decides: no discount.',
    'tour.noMatch.title': 'A condition does not match null',
    'tour.noMatch.text': 'A condition such as `"Silver"` or `>= 10000` does not match `null`. If no rule of a table matches at all, its result is `null`, and the field of the result is cleared.',
    'tour.allowedValues.title': 'Unless the type refuses it',
    'tour.allowedValues.text': 'An input whose type lists allowed values takes `null` only if it lists it; otherwise the runtime refuses the call. The models that New DMN... generates list `null` for every enum. The tier of the demo is a plain text.',

    'tour.newInput.title': 'A new input',
    'tour.newInput.text': 'A rule author added the input `creditLimit` to the table `discount`. The process data has no field with that name.',
    'tour.mappingError.title': 'Not evaluated',
    'tour.mappingError.text': 'The element throws `rule:engine:mapping`, and the model is not evaluated. The message names the input.',
    'tour.caught.title': 'Caught like any error',
    'tour.caught.text': 'The Error Boundary Event with `rule:engine` catches it: the process takes the path "not priced".',
    'tour.waysOut.title': 'Two ways out',
    'tour.waysOut.text': 'Add a field named `creditLimit` to the invoice and set it before the Business Rule Task, for example in this Script. Or the rule author names the input like a field the process already has.',

    'tour.scriptBefore.title': 'The Script before the element',
    'tour.scriptBefore.text': 'Whatever the model should get is prepared here, before the token reaches the Business Rule Task.',
    'tour.loop.title': 'A value the page does not have',
    'tour.loop.text': 'The rules ask for the order amount. The page only has the lines, so the Script works out the amount of each line, quantity times unit price.',
    'tour.sumSet.title': 'In a field named like the input',
    'tour.sumSet.text': 'The sum goes to `in.invoice.orderAmount`: the name of the input, so the element finds it.',
    'tour.preparedSent.title': 'The prepared value is sent',
    'tour.preparedSent.text': 'The element reads the field when the token arrives: it sends `orderAmount = 9200`.',
    'tour.preparedResult.title': 'And decides',
    'tour.preparedResult.text': 'An order below 10,000: rule 6, 2 percent. With a fifth laptop the sum is 10,400, and rule 5 would decide.',
    'tour.otherName.title': 'Another name, the same way',
    'tour.otherName.text': 'When your field has another name than the input, copy it in the Script: `in.invoice.customerTier = in.customer.tier;`. Input fields and Output fields of the element are only for a new DMN.',

    'about.rules': ABOUT_RULES
  }
};
