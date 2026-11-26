package com.axonivy.utils.rule.engine.runtime.api;

import com.axonivy.utils.rule.engine.runtime.api.model.ModelVersion;
import com.axonivy.utils.rule.engine.runtime.store.ModelVersionEntity;

final class Responses {

  private Responses() {}

  static ModelVersion version(ModelVersionEntity entity) {
    return version(entity, null);
  }

  static ModelVersion version(ModelVersionEntity entity, Boolean created) {
    return new ModelVersion(entity.model, entity.version, entity.status, entity.source, entity.author,
        entity.description, entity.createdAt, entity.effectiveDate, entity.activatedAt, entity.activatedBy,
        entity.previousVersion, entity.dmnName, entity.dmnNamespace, entity.contentHash, created);
  }
}
