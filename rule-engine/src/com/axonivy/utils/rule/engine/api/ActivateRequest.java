package com.axonivy.utils.rule.engine.api;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivateRequest(
    String user,
    OffsetDateTime effectiveDate) {

  public static ActivateRequest now(String user) {
    return new ActivateRequest(user, null);
  }

  public static ActivateRequest at(String user, OffsetDateTime effectiveDate) {
    return new ActivateRequest(user, effectiveDate);
  }
}
