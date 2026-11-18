package com.axonivy.utils.rule.engine.api;

import java.time.OffsetDateTime;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Makes a version the active one, now or at an effective date.
 */
@Schema(name = "ActivateRequest")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivateRequest(
    @Schema(description = "Who activates the version.") String user,
    @Schema(description = "Go live at this time instead of now.") OffsetDateTime effectiveDate) {

  public static ActivateRequest now(String user) {
    return new ActivateRequest(user, null);
  }

  public static ActivateRequest at(String user, OffsetDateTime effectiveDate) {
    return new ActivateRequest(user, effectiveDate);
  }
}
