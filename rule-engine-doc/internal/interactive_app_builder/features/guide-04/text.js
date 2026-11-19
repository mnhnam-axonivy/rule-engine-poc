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
    'tour.decisionField.text': 'The one field every Business Rule Task needs: what to evaluate, here `invoice-pricing › all decisions`, every decision of the file `dmn/invoice-pricing.dmn`. Each entry of the dropdown lists what it needs and gives, with the types.',
    'tour.fieldsEmpty.title': 'New DMN: not used',
    'tour.fieldsEmpty.text': 'The group New DMN is closed, and empty. Its fields only describe a new file. With an existing decision they are not used.',
    'tour.enough.title': 'That is enough',
    'tour.enough.text': 'The process runs and the invoice is priced. Nothing else was configured.',

    'tour.namesToFind.title': 'The names to look for',
    'tour.namesToFind.text': 'The model needs seven inputs: `customerTier`, `country`, `paymentTerms`, `promoCode`, `expressDelivery`, `invoiceDate` and `lines`. These names are what the element looks for in the process data. The model itself never sees the process data.',
    'tour.levelZero.title': 'Level 0: the fields of in',
    'tour.levelZero.text': 'The search starts with the fields of the process data itself: `invoice` and `pricing`. Neither has one of the seven names.',
    'tour.levelOne.title': 'Level 1: inside invoice',
    'tour.levelOne.text': 'So it goes one level down, into each data class of `in`. `invoice` has all seven, the list `lines` of the invoice lines among them. The search would go on down to 4 levels below `in`; it does not look inside a list.',
    'tour.foundIn.title': 'What is sent',
    'tour.foundIn.text': 'For each input the call shows the field it was read from, all in `in.invoice`. The list of lines goes as a list of structures, one per line. Only these values go to the model: the customer name and the invoice number stay in the process.',
    'tour.resultsBack.title': 'The results go back the same way',
    'tour.resultsBack.text': 'Each result is written to the field named like its decision: `pricedLines`, `orderDiscounts`, `shipping`, `totals` and `approval` are found in `in.pricing`. A list of structures fills a list of data classes.',
    'tour.closestWins.title': 'The first match wins',
    'tour.closestWins.text': 'The search stops at the first field with the name. A field closer to `in` beats a deeper one: `in.country` would win over `in.invoice.country`.',
    'tour.sameLevel.title': 'Watch out: the same name twice on one level',
    'tour.sameLevel.text': 'With `in.invoice.country` and `in.customer.country`, the element takes the one whose parent comes first in the data class, without a warning. To send the other one, give it a field of its own closer to `in`, set in a Script before the element: `in.country = in.customer.country;`.',
    'tour.caseAndBlanks.title': 'Exact names first',
    'tour.caseAndBlanks.text': 'Only if no field has exactly the name does the element search again, ignoring case and blanks: an input `Customer Tier` of a hand-written model finds the field `customerTier`.',

    'tour.noTier.title': 'No customer tier',
    'tour.noTier.text': 'One more line in the Script clears the customer tier, as if the page had not set it.',
    'tour.sentNull.title': 'Sent as null',
    'tour.sentNull.text': '`customerTier` is still sent, as `null`. The model gets every input it needs, with or without a value.',
    'tour.refused.title': 'Not allowed here',
    'tour.refused.text': 'The type of `customerTier` lists its allowed values: Standard, Silver, Gold and Platinum. `null` is not one of them, so the runtime refuses the call.',
    'tour.allowedValues.title': 'Allowed values decide',
    'tour.allowedValues.text': 'A type with allowed values takes `null` only if it lists it. The models that New DMN... generates list it for every enum, the demo model does not: a tier must be set.',
    'tour.noPromo.title': 'No promo code',
    'tour.noPromo.text': 'Now the Script clears the promo code instead. `promoCode` is a plain text, without allowed values.',
    'tour.dashMatches.title': 'The rules with - still decide',
    'tour.dashMatches.text': '`null` is sent and accepted. Every rule whose condition for the promo code is `-` still matches: the invoice is priced as before.',
    'tour.noMatch.title': 'A condition does not match null',
    'tour.noMatch.text': 'A condition such as `"WINTER26"` or `>= 50` does not match `null`. If no rule of a table matches at all, its result is `null`, or an empty list for COLLECT.',

    'tour.newInput.title': 'A new input',
    'tour.newInput.text': 'A rule author added the input `creditLimit` to the table `approvalFlags`. The process data has no field with that name.',
    'tour.mappingError.title': 'Not evaluated',
    'tour.mappingError.text': 'The element throws `rule:engine:mapping`, and the model is not evaluated. The message names the input.',
    'tour.caught.title': 'Caught like any error',
    'tour.caught.text': 'The Error Boundary Event with `rule:engine` catches it: the process takes the path "not priced".',
    'tour.waysOut.title': 'Two ways out',
    'tour.waysOut.text': 'Add a field named `creditLimit` to the invoice and set it before the Business Rule Task, for example in the Script. Or the rule author names the input like a field the process already has.',

    'tour.scriptBefore.title': 'The Script before the element',
    'tour.scriptBefore.text': 'Whatever the model should get is prepared here, before the token reaches the Business Rule Task.',
    'tour.trimUpper.title': 'The promo code, prepared',
    'tour.trimUpper.text': 'People type promo codes in any case and with blanks. The Script trims the code and writes it in capitals, so that it matches the rule.',
    'tour.typed.title': 'As typed on the page',
    'tour.typed.text': 'Say the customer typed ` winter26 `. This line stands for the page; the Script below prepares it.',
    'tour.preparedSent.title': 'The prepared value is sent',
    'tour.preparedSent.text': 'The element reads the field when the token arrives: it sends `"WINTER26"`.',
    'tour.preparedResult.title': 'The promotion applies',
    'tour.preparedResult.text': 'The rule for WINTER26 matches, between the 1st of November 2026 and the 31st of January 2027: 5 percent off the whole invoice.',
    'tour.otherName.title': 'Another name, the same way',
    'tour.otherName.text': 'When your field has another name than the input, copy it in the Script: `in.invoice.customerTier = in.customer.tier;`. Input fields and Output fields of the element are only for a new DMN.',

    'about.rules': ABOUT_RULES
  }
};
