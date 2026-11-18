package com.axonivy.utils.rule.engine.runtime.api;

import com.axonivy.utils.rule.engine.api.ModelVersion;
import com.axonivy.utils.rule.engine.runtime.store.ModelVersionEntity;

/**
 * Turns what the runtime stores into the records of its API (rule-engine-core).
 */
final class Responses {

  private Responses() {}

  static ModelVersion version(ModelVersionEntity entity) {
    return version(entity, null);
  }

  /** @param created only on registration: false if the same content was already stored */
  static ModelVersion version(ModelVersionEntity entity, Boolean created) {
    return new ModelVersion(entity.model, entity.version, entity.status, entity.source, entity.author,
        entity.description, entity.createdAt, entity.effectiveDate, entity.activatedAt, entity.activatedBy,
        entity.previousVersion, entity.dmnName, entity.dmnNamespace, entity.contentHash, created);
  }
}
