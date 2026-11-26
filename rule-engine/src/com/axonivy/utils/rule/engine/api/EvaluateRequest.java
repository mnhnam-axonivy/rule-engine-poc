package com.axonivy.utils.rule.engine.api;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

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
