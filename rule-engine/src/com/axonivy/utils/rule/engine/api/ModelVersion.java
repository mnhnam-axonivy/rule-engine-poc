package com.axonivy.utils.rule.engine.api;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

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
    Boolean created) {

  @JsonIgnore
  public boolean isActive() {
    return status == VersionStatus.ACTIVE;
  }
}
