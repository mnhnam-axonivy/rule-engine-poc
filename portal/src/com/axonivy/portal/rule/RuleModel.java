package com.axonivy.portal.rule;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public record RuleModel(String name, boolean onRuntime, Integer activeVersion, Integer latestVersion, int versionCount,
    List<String> decisions, String project, String file) implements Serializable {

  public static RuleModel of(Map<String, Object> model) {
    Object decisions = model.get("decisions");
    return new RuleModel(
        text(model.get("name")),
        Boolean.TRUE.equals(model.get("onRuntime")),
        number(model.get("activeVersion")),
        number(model.get("latestVersion")),
        model.get("versionCount") instanceof Number count ? count.intValue() : 0,
        decisions instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of(),
        text(model.get("project")),
        text(model.get("file")));
  }

  static Integer number(Object value) {
    return value instanceof Number number ? Integer.valueOf(number.intValue()) : null;
  }

  static String text(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
