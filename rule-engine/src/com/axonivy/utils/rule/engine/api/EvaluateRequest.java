package com.axonivy.utils.rule.engine.api;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Evaluates one decision, or all decisions, of a model with the given inputs.
 *
 * @param decision name of the decision to evaluate. Not set = all decisions of the model
 * @param input input values by the name of the DMN input
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluateRequest(
    String decision,
    Map<String, Object> input) {

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
