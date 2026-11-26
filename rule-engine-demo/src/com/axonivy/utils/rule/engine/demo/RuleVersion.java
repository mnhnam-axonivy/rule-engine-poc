package com.axonivy.utils.rule.engine.demo;

import ch.ivyteam.ivy.environment.Ivy;

public final class RuleVersion {

  private static final String FIELD_PREFIX = "ruleVersion_";

  private RuleVersion() {}

  public static Integer ofCase(String model) {
    var wfCase = Ivy.wfCase();
    if (wfCase == null) {
      return null;
    }
    String version = wfCase.customFields().stringField(FIELD_PREFIX + model).getOrNull();
    return version == null ? null : Integer.valueOf(version);
  }
}
