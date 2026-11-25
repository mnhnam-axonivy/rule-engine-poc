package com.axonivy.utils.rule.engine.runtime.api.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * A model on the decision runtime: its versions and what its active version needs and decides.
 */
@Schema(name = "Model")
@JsonInclude(Include.NON_EMPTY)
public record ModelInfo(
    String name,
    @Schema(description = "The version that answers evaluations. Not set if no version is active.") Integer activeVersion,
    int latestVersion,
    int versionCount,
    String dmnName,
    String dmnNamespace,
    @Schema(description = "Decisions of the active version.") List<Decision> decisions,
    @Schema(description = "Inputs of the active version.") List<Input> inputs) {

  /** A decision and the inputs it needs, directly or through the decisions it requires. */
  @Schema(name = "DecisionInfo")
  public record Decision(String name, String resultType, List<String> requiredInputs) {}

  /** An input of the model with its DMN type. */
  @Schema(name = "InputInfo")
  public record Input(String name, String type, boolean collection) {}

  public List<Decision> decisions() {
    return decisions == null ? List.of() : decisions;
  }

  public List<Input> inputs() {
    return inputs == null ? List.of() : inputs;
  }
}
