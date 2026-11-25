package com.axonivy.utils.rule.engine.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * A finding of the DMN engine: its compiler, validator or evaluation.
 *
 * @param severity ERROR, WARN or INFO
 * @param message readable text
 * @param type kind of the finding, e.g. ERROR_EVAL_NODE
 * @param sourceId id of the DMN element the finding belongs to
 */
@JsonInclude(Include.NON_NULL)
public record Message(String severity, String message, String type, String sourceId) {

  public static final String ERROR = "ERROR";
  public static final String WARN = "WARN";
  public static final String INFO = "INFO";

  public static Message error(String message) {
    return new Message(ERROR, message, null, null);
  }

  @JsonIgnore
  public boolean isError() {
    return ERROR.equals(severity);
  }
}
