package com.axonivy.utils.rule.engine.program.internal;

/**
 * Keys of the element configuration, as stored in the process.
 * <p>A key of the mapping names the choice of {@link #DECISION} it belongs to:
 * <code>decision[&lt;choice&gt;].&lt;name&gt;</code>. The inscription view of the Designer shows such a field only
 * while the first selected decision is that choice.</p>
 */
public interface Conf {
  /** Selected decision: <code>model#decision</code>, or <code>model</code> for all decisions of the model. */
  String DECISION = "decision";
  /** Separates model and decision in the value of {@link #DECISION}. */
  String SEPARATOR = "#";

  /** @return the value of {@link #DECISION} that selects a decision of the model, or all of them if it is null */
  static String choice(String model, String decision) {
    return decision == null ? model : model + SEPARATOR + decision;
  }

  /**
   * @return the key of the process data an input reads, e.g.
   *         <code>decision[invoice-discount#discount].input.orderAmount</code> for <code>in.invoice.amount</code>
   */
  static String inputKey(String choice, String input) {
    return DECISION + "[" + choice + "].input." + input;
  }

  /**
   * @return the key of the field a result fills, e.g. <code>decision[invoice-discount#discount].output.discountPercent</code>
   *         for <code>in.invoice.discountPercent</code>. With all decisions of a model selected, the key names the
   *         decision of the result too: <code>decision[invoice-approval].output.approval.nextStep</code>
   */
  static String outputKey(String choice, String decision, String output) {
    String result = choice.contains(SEPARATOR) ? output : decision + "." + output;
    return DECISION + "[" + choice + "].output." + result;
  }
}
