package com.axonivy.utils.rule.engine.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * @param contentHash SHA-256 of the DMN file, line endings ignored
 */
@JsonInclude(Include.NON_EMPTY)
public record LoadedVersion(
    String model,
    int version,
    String contentHash,
    String dmnName,
    String dmnNamespace,
    List<Decision> decisions,
    List<Input> inputs) {

  /** A decision and the inputs it needs, directly or through the decisions it requires. */
  public record Decision(String name, String resultType, List<String> requiredInputs) {}

  public record Input(String name, String type, boolean collection) {}

  public List<Decision> decisions() {
    return decisions == null ? List.of() : decisions;
  }

  public List<Input> inputs() {
    return inputs == null ? List.of() : inputs;
  }
}
