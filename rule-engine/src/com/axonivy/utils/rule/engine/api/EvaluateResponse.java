package com.axonivy.utils.rule.engine.api;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * The result of an evaluation and the version that produced it.
 *
 * @param model name of the evaluated model
 * @param version the version that produced the result
 * @param decision the requested decision, if one was named
 * @param result result of the requested decision, if one was named
 * @param results results of all evaluated decisions by name
 * @param messages warnings of the evaluation
 */
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
