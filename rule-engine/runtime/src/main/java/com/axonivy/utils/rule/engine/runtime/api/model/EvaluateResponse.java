package com.axonivy.utils.rule.engine.runtime.api.model;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

@Schema(name = "EvaluateResponse")
@JsonInclude(Include.NON_EMPTY)
public record EvaluateResponse(
    @Schema(description = "Name of the evaluated model.") String model,
    @Schema(description = "The version that produced the result.") int version,
    @Schema(description = "The requested decision, if one was named.") String decision,
    @Schema(description = "Result of the requested decision, if one was named.") Object result,
    @Schema(description = "Results of all evaluated decisions by name.") Map<String, Object> results,
    @Schema(description = "Warnings of the evaluation.") List<Message> messages) {

  public Map<String, Object> results() {
    return results == null ? Map.of() : results;
  }

  public List<Message> messages() {
    return messages == null ? List.of() : messages;
  }
}
