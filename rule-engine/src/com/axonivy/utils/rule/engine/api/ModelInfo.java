package com.axonivy.utils.rule.engine.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * A model on the decision runtime: its versions and what its active version needs and decides.
 *
 * @param activeVersion the version that answers evaluations. Not set if no version is active
 * @param decisions decisions of the active version
 * @param inputs inputs of the active version
 */
@JsonInclude(Include.NON_EMPTY)
public record ModelInfo(
    String name,
    Integer activeVersion,
    int latestVersion,
    int versionCount,
    String dmnName,
    String dmnNamespace,
    List<Decision> decisions,
    List<Input> inputs) {

  /** A decision and the inputs it needs, directly or through the decisions it requires. */
  public record Decision(String name, String resultType, List<String> requiredInputs) {}

  /** An input of the model with its DMN type. */
  public record Input(String name, String type, boolean collection) {}

  public List<Decision> decisions() {
    return decisions == null ? List.of() : decisions;
  }

  public List<Input> inputs() {
    return inputs == null ? List.of() : inputs;
  }
}
