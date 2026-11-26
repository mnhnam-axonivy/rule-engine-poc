package com.axonivy.utils.rule.engine.runtime.store;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.axonivy.utils.rule.engine.runtime.api.model.VersionSource;
import com.axonivy.utils.rule.engine.runtime.api.model.VersionStatus;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * One stored version of a DMN model.
 */
@Entity
@Table(name = "model_version")
public class ModelVersionEntity extends PanacheEntityBase {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "model_name", nullable = false)
  public String model;

  @Column(name = "version_no", nullable = false)
  public int version;

  @Lob
  @Column(nullable = false)
  public String content;

  @Column(name = "content_hash", nullable = false)
  public String contentHash;

  @Column(name = "dmn_name")
  public String dmnName;

  @Column(name = "dmn_namespace")
  public String dmnNamespace;

  public String author;

  public String description;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public VersionSource source;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public VersionStatus status;

  @Column(name = "created_at", nullable = false)
  public OffsetDateTime createdAt;

  @Column(name = "effective_date")
  public OffsetDateTime effectiveDate;

  @Column(name = "activated_at")
  public OffsetDateTime activatedAt;

  @Column(name = "activated_by")
  public String activatedBy;

  /** The version that was active before this one was activated. Target of a rollback. */
  @Column(name = "previous_version")
  public Integer previousVersion;

  public static List<ModelVersionEntity> versionsOf(String model) {
    return list("model = ?1 order by version desc", model);
  }

  public static Optional<ModelVersionEntity> find(String model, int version) {
    return find("model = ?1 and version = ?2", model, version).firstResultOptional();
  }

  public static Optional<ModelVersionEntity> activeOf(String model) {
    return find("model = ?1 and status = ?2", model, VersionStatus.ACTIVE).firstResultOptional();
  }

  public static Optional<ModelVersionEntity> byHash(String model, String contentHash) {
    return find("model = ?1 and contentHash = ?2 order by version desc", model, contentHash).firstResultOptional();
  }

  public static List<ModelVersionEntity> allActive() {
    return list("status", VersionStatus.ACTIVE);
  }

  public static List<ModelVersionEntity> dueForActivation(OffsetDateTime now) {
    return list("status = ?1 and effectiveDate is not null and effectiveDate <= ?2 order by effectiveDate, version",
        VersionStatus.APPROVED, now);
  }

  public static int nextVersion(String model) {
    Integer max = find("select max(v.version) from ModelVersionEntity v where v.model = ?1", model)
        .project(Integer.class)
        .firstResult();
    return max == null ? 1 : max + 1;
  }

  public static List<String> modelNames() {
    return find("select distinct v.model from ModelVersionEntity v order by v.model")
        .project(String.class)
        .list();
  }
}
