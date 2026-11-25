package com.axonivy.utils.rule.engine.api;

/**
 * Where a version came from. Decides whether a project deployment may replace the active version: it never
 * replaces a version that was activated from the Portal or another API client.
 */
public enum VersionSource {
  /** Registered from the .dmn file of an Ivy project. */
  PROJECT,
  /** Changed by a business user, e.g. in the DMN editor of the runtime or a Rules UI of the Portal. */
  PORTAL,
  /** Loaded by any other API client. */
  API
}
