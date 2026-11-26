package com.axonivy.utils.rule.engine.runtime.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.axonivy.utils.rule.engine.runtime.api.model.VersionSource;
import com.axonivy.utils.rule.engine.runtime.api.model.VersionStatus;
import com.axonivy.utils.rule.engine.runtime.store.ModelVersionEntity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class VersionStore {

  public record NewVersion(
      String model,
      String content,
      String contentHash,
      String dmnName,
      String dmnNamespace,
      String author,
      String description,
      VersionSource source,
      Boolean activate,
      OffsetDateTime effectiveDate) {}

  public record Registration(ModelVersionEntity version, boolean created) {}

  public record ActiveRef(String model, int version) {}

  @Transactional
  public Registration register(NewVersion request) {
    var existing = ModelVersionEntity.byHash(request.model(), request.contentHash());
    if (existing.isPresent()) {
      var version = existing.get();
      if (shouldActivate(request, version)) {
        activate(version, request.author(), request.effectiveDate(), false);
      }
      return new Registration(version, false);
    }
    var version = new ModelVersionEntity();
    version.model = request.model();
    version.version = ModelVersionEntity.nextVersion(request.model());
    version.content = request.content();
    version.contentHash = request.contentHash();
    version.dmnName = request.dmnName();
    version.dmnNamespace = request.dmnNamespace();
    version.author = request.author();
    version.description = request.description();
    version.source = request.source();
    version.status = VersionStatus.DRAFT;
    version.createdAt = OffsetDateTime.now();
    version.persist();
    if (shouldActivate(request, version)) {
      activate(version, request.author(), request.effectiveDate(), false);
    }
    return new Registration(version, true);
  }

  /**
   * The source of truth rule.
   * <ul>
   * <li>An explicit choice of the caller always wins.</li>
   * <li>The first version of a model goes live at once.</li>
   * <li>A project deployment goes live as long as the active version also came from a project.</li>
   * <li>Everything else stays a draft: a version activated from the Portal is never overwritten by a deployment.</li>
   * </ul>
   */
  private static boolean shouldActivate(NewVersion request, ModelVersionEntity candidate) {
    if (candidate.status == VersionStatus.ACTIVE) {
      return false;
    }
    if (request.activate() != null) {
      return request.activate();
    }
    if (request.effectiveDate() != null) {
      return true; // an effective date is a request to go live at that time
    }
    var active = ModelVersionEntity.activeOf(request.model());
    if (active.isEmpty()) {
      return true;
    }
    return request.source() == VersionSource.PROJECT && active.get().source == VersionSource.PROJECT;
  }

  @Transactional
  public ModelVersionEntity approve(String model, int version) {
    var entity = require(model, version);
    if (entity.status != VersionStatus.DRAFT) {
      throw RuleEngineException.invalidState("Only a draft can be approved. Version " + version
          + " of '" + model + "' is " + entity.status.name().toLowerCase() + ".");
    }
    entity.status = VersionStatus.APPROVED;
    return entity;
  }

  @Transactional
  public ModelVersionEntity activate(String model, int version, String user, OffsetDateTime effectiveDate) {
    var entity = require(model, version);
    activate(entity, user, effectiveDate, false);
    return entity;
  }

  @Transactional
  public ModelVersionEntity retire(String model, int version) {
    var entity = require(model, version);
    if (entity.status == VersionStatus.ACTIVE) {
      throw RuleEngineException.invalidState("Version " + version + " of '" + model
          + "' is active. Activate another version or roll back before retiring it.");
    }
    entity.status = VersionStatus.RETIRED;
    entity.effectiveDate = null;
    return entity;
  }

  @Transactional
  public ModelVersionEntity rollback(String model, String user) {
    requireModel(model);
    var active = ModelVersionEntity.activeOf(model)
        .orElseThrow(() -> RuleEngineException.noActiveVersion(model));
    if (active.previousVersion == null) {
      throw RuleEngineException.invalidState("Version " + active.version + " of '" + model
          + "' is the first active version, there is nothing to roll back to.");
    }
    var previous = require(model, active.previousVersion);
    activate(previous, user, null, true);
    return previous;
  }

  @Transactional
  public List<ModelVersionEntity> activateDueVersions() {
    var activated = new ArrayList<ModelVersionEntity>();
    for (var due : ModelVersionEntity.dueForActivation(OffsetDateTime.now())) {
      activate(due, due.activatedBy, null, false);
      activated.add(due);
    }
    return activated;
  }

  @Transactional
  public long delete(String model) {
    requireModel(model);
    return ModelVersionEntity.delete("model", model);
  }

  @Transactional
  public List<ActiveRef> activeRefs() {
    return ModelVersionEntity.allActive().stream()
        .map(version -> new ActiveRef(version.model, version.version))
        .toList();
  }

  @Transactional
  public Optional<ModelVersionEntity> find(String model, int version) {
    return ModelVersionEntity.find(model, version);
  }

  @Transactional
  public ModelVersionEntity get(String model, int version) {
    return require(model, version);
  }

  @Transactional
  public List<ModelVersionEntity> versions(String model) {
    requireModel(model);
    return ModelVersionEntity.versionsOf(model);
  }

  @Transactional
  public List<String> modelNames() {
    return ModelVersionEntity.modelNames();
  }

  @Transactional
  public boolean exists(String model) {
    return ModelVersionEntity.count("model", model) > 0;
  }

  private static void activate(ModelVersionEntity entity, String user, OffsetDateTime effectiveDate, boolean rollback) {
    if (entity.status == VersionStatus.ACTIVE) {
      return;
    }
    if (effectiveDate != null && effectiveDate.isAfter(OffsetDateTime.now())) {
      // goes live later: the scheduler activates it once the effective date is reached
      entity.status = VersionStatus.APPROVED;
      entity.effectiveDate = effectiveDate;
      entity.activatedBy = user;
      return;
    }
    var current = ModelVersionEntity.activeOf(entity.model);
    current.ifPresent(active -> active.status = VersionStatus.RETIRED);
    if (!rollback) {
      // a rollback keeps the history of the target, so repeated rollbacks walk further back
      entity.previousVersion = current.map(active -> active.version).orElse(null);
    }
    entity.status = VersionStatus.ACTIVE;
    entity.activatedAt = OffsetDateTime.now();
    entity.activatedBy = user;
    if (entity.effectiveDate == null) {
      entity.effectiveDate = entity.activatedAt;
    }
  }

  private static ModelVersionEntity require(String model, int version) {
    requireModel(model);
    return ModelVersionEntity.find(model, version)
        .orElseThrow(() -> RuleEngineException.versionNotFound(model, version));
  }

  private static void requireModel(String model) {
    if (ModelVersionEntity.count("model", model) == 0) {
      throw RuleEngineException.modelNotFound(model);
    }
  }
}
