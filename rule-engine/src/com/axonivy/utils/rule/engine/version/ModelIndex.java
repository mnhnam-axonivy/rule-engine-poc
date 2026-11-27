package com.axonivy.utils.rule.engine.version;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.axonivy.utils.rule.engine.api.ModelVersion;
import com.axonivy.utils.rule.engine.api.VersionSource;
import com.axonivy.utils.rule.engine.api.VersionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"model", "versions"})
final class ModelIndex {

  public String model;
  public List<Entry> versions = new ArrayList<>();

  /** Changed only by {@link VersionStore}, under its lock. */
  @JsonInclude(Include.NON_NULL)
  @JsonPropertyOrder({"version", "status", "source", "author", "description", "createdAt", "effectiveDate",
      "activatedBy", "previousVersion"})
  static final class Entry {
    public int version;
    public VersionStatus status;
    public VersionSource source;
    public String author;
    public String description;
    public OffsetDateTime createdAt;
    public OffsetDateTime effectiveDate;
    public String activatedBy;
    /** The version that was active before this one was activated. Target of a rollback. */
    public Integer previousVersion;

    ModelVersion toApi(String model, Boolean created) {
      return new ModelVersion(model, version, status, source, author, description, createdAt, effectiveDate,
          activatedBy, previousVersion, created);
    }

    Entry copy() {
      var copy = new Entry();
      copy.version = version;
      copy.status = status;
      copy.source = source;
      copy.author = author;
      copy.description = description;
      copy.createdAt = createdAt;
      copy.effectiveDate = effectiveDate;
      copy.activatedBy = activatedBy;
      copy.previousVersion = previousVersion;
      return copy;
    }
  }

  static ModelIndex of(String model) {
    var index = new ModelIndex();
    index.model = model;
    return index;
  }

  ModelIndex copy() {
    var copy = of(model);
    versions.forEach(entry -> copy.versions.add(entry.copy()));
    return copy;
  }

  Optional<Entry> find(int version) {
    return versions.stream().filter(entry -> entry.version == version).findFirst();
  }

  Optional<Entry> active() {
    return versions.stream().filter(entry -> entry.status == VersionStatus.ACTIVE).findFirst();
  }

  /** @return approved versions whose effective date has been reached, the earliest first */
  List<Entry> due(OffsetDateTime now) {
    return versions.stream()
        .filter(entry -> entry.status == VersionStatus.APPROVED && entry.effectiveDate != null
            && !entry.effectiveDate.isAfter(now))
        .sorted(Comparator.comparing((Entry entry) -> entry.effectiveDate).thenComparingInt(entry -> entry.version))
        .toList();
  }

  int nextVersion() {
    return versions.stream().mapToInt(entry -> entry.version).max().orElse(0) + 1;
  }

  List<Entry> newestFirst() {
    return versions.stream().sorted(Comparator.comparingInt((Entry entry) -> entry.version).reversed()).toList();
  }
}
