package com.axonivy.utils.rule.engine.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

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

  public record Decision(String name, String resultType, List<String> requiredInputs) {}

  public record Input(String name, String type, boolean collection) {}

  public List<Decision> decisions() {
    return decisions == null ? List.of() : decisions;
  }

  public List<Input> inputs() {
    return inputs == null ? List.of() : inputs;
  }
}
