package com.axonivy.utils.rule.engine.runtime.api.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * The answer of the runtime to a request it cannot fulfil.
 */
@Schema(name = "Error")
@JsonInclude(Include.NON_EMPTY)
public record ErrorResponse(
    @Schema(description = "Stable error code, e.g. MODEL_NOT_FOUND, INVALID_MODEL, EVALUATION_FAILED.") String code,
    @Schema(description = "Readable explanation.") String message,
    @Schema(description = "Findings of the DMN engine.") List<Message> details) {

  public List<Message> details() {
    return details == null ? List.of() : details;
  }
}
