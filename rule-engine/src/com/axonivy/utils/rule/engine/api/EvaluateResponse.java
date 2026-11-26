package com.axonivy.utils.rule.engine.api;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

@JsonInclude(Include.NON_EMPTY)
public record EvaluateResponse(
    String model,
    int version,
    String decision,
    Object result,
    Map<String, Object> results,
    List<Message> messages) {

  public Map<String, Object> results() {
    return results == null ? Map.of() : results;
  }

  public List<Message> messages() {
    return messages == null ? List.of() : messages;
  }
}
