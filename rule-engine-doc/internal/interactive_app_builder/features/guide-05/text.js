/**
 * Guide 05 — texts of the walkthrough. tour.chapter.<id> are the headings of the tutorial
 * page, word for word.
 */
import { ABOUT_RULES } from '../shared/project.js';

export const strings = {
  en: {
    'lang': 'en',
    'tour.intro.title': 'From process data to a decision table',
    'tour.intro.text': 'The demo process gets a second Business Rule Task after the pricing: how many days the delivery takes. It has no decision yet. It generates one from two fields of the invoice under a name of its own, and a first rule is added.',
    'tour.intro.duration': 'about 4 min',
    'tour.chapter.fields': 'Select the fields',
    'tour.chapter.name': 'Name the new DMN',
    'tour.chapter.generate': 'Generate the file',
    'tour.chapter.rules': 'Fill in the rules',

    'tour.create.title': 'New DMN...',
    'tour.create.text': 'This entry asks for a new file instead of selecting one. The group New DMN below says what to build. Its fields are used for nothing else.',
    'tour.inputs.title': 'Input fields',
    'tour.inputs.text': 'The fields the decision should read, as paths into the process data: the country and the express delivery of the invoice. Each becomes an input named like its field, and a column of the table.',
    'tour.outputs.title': 'Output fields',
    'tour.outputs.text': 'The field the decision should fill: `in.pricing.deliveryDays`, a field the result of the pricing got for it. It becomes the output column. Later the element finds all these fields by their names, as with any decision.',

    'tour.nameField.title': 'New DMN name',
    'tour.nameField.text': 'At the top of the group: the name of the file that "New DMN..." creates, `dmn/delivery-days.dmn`. It is required.',
    'tour.nameEmpty.title': 'Left empty',
    'tour.nameEmpty.text': 'Without a name there is no file to create. The mask warns, and nothing is generated when it is opened again.',
    'tour.noName.title': 'An error when the process passes',
    'tour.noName.text': 'The element throws `rule:engine:mapping`, and the Runtime Log says why: the name is empty.',
    'tour.nameTaken.title': 'A name that is taken',
    'tour.nameTaken.text': '`dmn/invoice-pricing.dmn` exists already. It was not generated from these fields, so it is another file, and it is never overwritten.',
    'tour.taken.title': 'An error too',
    'tour.taken.text': 'The element does not use the other file. It throws `rule:engine:mapping` and names the file.',
    'tour.ownName.title': 'A name of its own',
    'tour.ownName.text': 'That is why the new DMN is named `delivery-days`. The file is made when the mask is opened again or the process runs, and later runs use it.',

    'tour.created.title': 'The file is generated',
    'tour.created.text': 'The element has no such file yet, so it writes `dmn/delivery-days.dmn`. A new tab appears for it.',
    'tour.registered.title': 'And registered',
    'tour.registered.text': 'A generated file is a `.dmn` file like any other: the runtime stores it as version 1 of the model `delivery-days`.',
    'tour.placeholderResult.title': 'It already runs',
    'tour.placeholderResult.text': 'The generated table has one rule that matches everything and returns a neutral value. The process works before a single rule is written.',
    'tour.inputsNamed.title': 'Inputs named like the fields',
    'tour.inputsNamed.text': '`country` and `expressDelivery`, with the types of the data class. Because the names are the field names, nothing has to be mapped: from now on the mapping by name works for this file.',
    'tour.table.title': 'A table, ready for rules',
    'tour.table.text': 'One column per input, one for the output, hit policy FIRST, and the placeholder rule. The decision is named `deliveryDays` like the output field, so its result finds `in.pricing.deliveryDays` by name.',
    'tour.selected.title': 'The new file is selected',
    'tour.selected.text': 'Now that the file exists, the element refers to its decision like to any other. An existing file is never overwritten.',

    'tour.placeholder.title': 'The placeholder rule',
    'tour.placeholder.text': 'It matches everything, so it has to stay last. Rules go above it.',
    'tour.above.title': 'A new rule',
    'tour.above.text': '“Add rule” puts it above the placeholder. With the hit policy FIRST a rule below it would never be reached.',
    'tour.written.title': 'Switzerland: 2 days',
    'tour.written.text': 'Invoices to Switzerland are delivered in 2 days. Express or not does not matter here, so it stays `-`.',
    'tour.saved.title': 'Saved means live',
    'tour.saved.text': 'The table has changed, so the element sends the file again. It becomes version 2.',
    'tour.decided.title': 'Your rule decides',
    'tour.decided.text': 'The sample invoice goes to Switzerland: the new rule matches.',

    'about.rules': ABOUT_RULES
  }
};
