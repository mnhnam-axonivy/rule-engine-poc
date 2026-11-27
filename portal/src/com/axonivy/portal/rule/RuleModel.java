package com.axonivy.portal.rule;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * A stored DMN model, or the .dmn file of a project that is not stored yet.
 *
 * @param pendingVersion a draft from a project, newer than the active version; null if there is none
 */
public record RuleModel(String name, boolean stored, Integer activeVersion, Integer latestVersion, int versionCount,
    List<String> decisions, String project, String file, Integer pendingVersion, String pendingFrom)
    implements Serializable {

  /** The keys of a model in the answer of ruleManagementModels(). */
  private interface Field {
    String NAME = "name";
    String STORED = "stored";
    String ACTIVE_VERSION = "activeVersion";
    String LATEST_VERSION = "latestVersion";
    String VERSION_COUNT = "versionCount";
    String DECISIONS = "decisions";
    String PROJECT = "project";
    String FILE = "file";
    String PENDING_VERSION = "pendingVersion";
    String PENDING_FROM = "pendingFrom";
  }

  public static RuleModel of(Map<String, Object> model) {
    Object decisions = model.get(Field.DECISIONS);
    return new RuleModel(
        text(model.get(Field.NAME)),
        Boolean.TRUE.equals(model.get(Field.STORED)),
        number(model.get(Field.ACTIVE_VERSION)),
        number(model.get(Field.LATEST_VERSION)),
        model.get(Field.VERSION_COUNT) instanceof Number count ? count.intValue() : 0,
        decisions instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of(),
        text(model.get(Field.PROJECT)),
        text(model.get(Field.FILE)),
        number(model.get(Field.PENDING_VERSION)),
        text(model.get(Field.PENDING_FROM)));
  }

  static Integer number(Object value) {
    return value instanceof Number number ? Integer.valueOf(number.intValue()) : null;
  }

  static String text(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
