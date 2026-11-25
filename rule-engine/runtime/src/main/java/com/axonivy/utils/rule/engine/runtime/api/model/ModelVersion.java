package com.axonivy.utils.rule.engine.runtime.api.model;

import java.time.OffsetDateTime;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * One version of a model on the decision runtime.
 */
@Schema(name = "Version")
@JsonInclude(Include.NON_NULL)
public record ModelVersion(
    String model,
    int version,
    VersionStatus status,
    VersionSource source,
    String author,
    String description,
    OffsetDateTime createdAt,
    OffsetDateTime effectiveDate,
    OffsetDateTime activatedAt,
    String activatedBy,
    Integer previousVersion,
    String dmnName,
    String dmnNamespace,
    String contentHash,
    @Schema(description = "Only on registration: false if the same content was already stored.") Boolean created) {

  @JsonIgnore
  public boolean isActive() {
    return status == VersionStatus.ACTIVE;
  }
}
