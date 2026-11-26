package com.axonivy.utils.rule.engine.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

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
