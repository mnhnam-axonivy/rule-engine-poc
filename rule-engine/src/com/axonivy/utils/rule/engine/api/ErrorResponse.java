package com.axonivy.utils.rule.engine.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * The answer of the runtime to a request it cannot fulfil.
 *
 * @param code stable error code, e.g. MODEL_NOT_FOUND, INVALID_MODEL, EVALUATION_FAILED
 * @param message readable explanation
 * @param details findings of the DMN engine
 */
@JsonInclude(Include.NON_EMPTY)
public record ErrorResponse(
    String code,
    String message,
    List<Message> details) {

  public List<Message> details() {
    return details == null ? List.of() : details;
  }
}
