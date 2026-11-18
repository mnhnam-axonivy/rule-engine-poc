package com.axonivy.utils.rule.engine.api;

/**
 * Life cycle of a model version on the decision runtime. Exactly one version of a model is {@link #ACTIVE}.
 */
public enum VersionStatus {
  /** Stored, not yet released. Can be evaluated explicitly for test runs. */
  DRAFT,
  /** Released for activation. With an effective date it becomes active automatically. */
  APPROVED,
  /** The version that answers evaluations of the model. */
  ACTIVE,
  /** Was replaced or withdrawn. Stays available for a rollback. */
  RETIRED
}
