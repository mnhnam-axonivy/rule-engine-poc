package com.axonivy.utils.rule.engine.demo;

import ch.ivyteam.ivy.environment.Ivy;

/**
 * Which version of a model decided. The Business Rule Task stores it on the case as the custom field
 * <code>ruleVersion_&lt;model&gt;</code>.
 */
public final class RuleVersion {

  private static final String FIELD_PREFIX = "ruleVersion_";

  private RuleVersion() {}

  /** @return the version of the model that decided last in this case, null if none did */
  public static Integer ofCase(String model) {
    var wfCase = Ivy.wfCase();
    if (wfCase == null) {
      return null;
    }
    String version = wfCase.customFields().stringField(FIELD_PREFIX + model).getOrNull();
    return version == null ? null : Integer.valueOf(version);
  }
}
