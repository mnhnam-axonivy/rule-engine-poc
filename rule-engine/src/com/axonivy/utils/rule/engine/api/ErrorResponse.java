package com.axonivy.utils.rule.engine.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

@JsonInclude(Include.NON_EMPTY)
public record ErrorResponse(
    String code,
    String message,
    List<Message> details) {

  public List<Message> details() {
    return details == null ? List.of() : details;
  }
}
