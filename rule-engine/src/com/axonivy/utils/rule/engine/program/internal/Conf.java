package com.axonivy.utils.rule.engine.program.internal;

/**
 * Keys of the element configuration, as stored in the process.
 */
public interface Conf {
  /** Selected decision: <code>model#decision</code>, or {@link #CREATE_NEW}. */
  String DECISION = "decision";
  /** Only for {@link #CREATE_NEW}: the fields that become the inputs, e.g. <code>in.member.purchaseAmount, in.member.type</code>. */
  String INPUTS = "inputs";
  /** Only for {@link #CREATE_NEW}: the fields that become the outputs, e.g. <code>in.member.discount</code>. */
  String OUTPUTS = "outputs";
  /** Only for {@link #CREATE_NEW}: the name of the model to create from the selected fields. */
  String NEW_MODEL = "newModel";

  /** Entry of the decision dropdown that generates a new .dmn file. */
  String CREATE_NEW = "#create";
  /** Separates model and decision in the value of {@link #DECISION}. */
  String SEPARATOR = "#";
}
