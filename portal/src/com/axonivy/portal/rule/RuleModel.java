package com.axonivy.portal.rule;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * A rule: a stored DMN model, or the .dmn file of a project that is not stored yet.
 *
 * @param name name of the model, the name of its .dmn file
 * @param stored false for a .dmn file that was never stored as a version
 * @param activeVersion the version that answers evaluations, null if none is active
 * @param decisions names of the decisions of the active version
 * @param project the project with a .dmn file of this name, null if none has one
 * @param file the name of that file
 * @param pendingVersion a draft from a project, newer than the active version; null if there is none
 */
public record RuleModel(String name, boolean stored, Integer activeVersion, Integer latestVersion, int versionCount,
    List<String> decisions, String project, String file, Integer pendingVersion, String pendingFrom)
    implements Serializable {

  public static RuleModel of(Map<String, Object> model) {
    Object decisions = model.get("decisions");
    return new RuleModel(
        text(model.get("name")),
        Boolean.TRUE.equals(model.get("stored")),
        number(model.get("activeVersion")),
        number(model.get("latestVersion")),
        model.get("versionCount") instanceof Number count ? count.intValue() : 0,
        decisions instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of(),
        text(model.get("project")),
        text(model.get("file")),
        number(model.get("pendingVersion")),
        text(model.get("pendingFrom")));
  }

  static Integer number(Object value) {
    return value instanceof Number number ? Integer.valueOf(number.intValue()) : null;
  }

  static String text(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
