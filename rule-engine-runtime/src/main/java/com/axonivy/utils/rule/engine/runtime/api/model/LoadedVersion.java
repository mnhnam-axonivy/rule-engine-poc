package com.axonivy.utils.rule.engine.runtime.api.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

@Schema(name = "LoadedVersion")
@JsonInclude(Include.NON_EMPTY)
public record LoadedVersion(
    String model,
    int version,
    @Schema(description = "SHA-256 of the DMN file, line endings ignored.") String contentHash,
    String dmnName,
    String dmnNamespace,
    List<Decision> decisions,
    List<Input> inputs) {

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
