package com.axonivy.utils.rule.engine.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Evaluates one decision, or all decisions, of a model with the given inputs.
 */
@Schema(name = "EvaluateRequest")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluateRequest(
    @Schema(description = "Name of the decision to evaluate. Not set = all decisions of the model.") String decision,
    @Schema(description = "Input values by the name of the DMN input.") Map<String, Object> input) {

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private String decision;
    private final Map<String, Object> input = new LinkedHashMap<>();

    public Builder decision(String decision) {
      this.decision = decision;
      return this;
    }

    public Builder input(Map<String, Object> input) {
      if (input != null) {
        this.input.putAll(input);
      }
      return this;
    }

    public Builder input(String name, Object value) {
      this.input.put(name, value);
      return this;
    }

    public EvaluateRequest build() {
      return new EvaluateRequest(decision, input);
    }
  }
}
