package com.axonivy.portal.rule;

import java.io.Serializable;
import java.util.Map;

/**
 * @param effectiveDate when the version went live, or goes live if its activation is planned
 * @param created only after a save: false if exactly this content was stored already
 */
public record RuleVersion(String model, int version, String status, String source, String author, String description,
    String createdAt, String effectiveDate, String activatedBy, Integer previousVersion,
    Boolean created) implements Serializable {

  public static final String ACTIVE = "ACTIVE";

  /** The keys of a version in the answers of ruleManagementVersions(String), save, activate and rollback. */
  private interface Field {
    String MODEL = "model";
    String VERSION = "version";
    String STATUS = "status";
    String SOURCE = "source";
    String AUTHOR = "author";
    String DESCRIPTION = "description";
    String CREATED_AT = "createdAt";
    String EFFECTIVE_DATE = "effectiveDate";
    String ACTIVATED_BY = "activatedBy";
    String PREVIOUS_VERSION = "previousVersion";
    String CREATED = "created";
  }

  public static RuleVersion of(Map<String, Object> version) {
    Integer number = RuleModel.number(version.get(Field.VERSION));
    return new RuleVersion(
        RuleModel.text(version.get(Field.MODEL)),
        number == null ? 0 : number,
        RuleModel.text(version.get(Field.STATUS)),
        RuleModel.text(version.get(Field.SOURCE)),
        RuleModel.text(version.get(Field.AUTHOR)),
        RuleModel.text(version.get(Field.DESCRIPTION)),
        RuleModel.text(version.get(Field.CREATED_AT)),
        RuleModel.text(version.get(Field.EFFECTIVE_DATE)),
        RuleModel.text(version.get(Field.ACTIVATED_BY)),
        RuleModel.number(version.get(Field.PREVIOUS_VERSION)),
        version.get(Field.CREATED) instanceof Boolean flag ? flag : null);
  }

  public boolean isActive() {
    return ACTIVE.equals(status);
  }
}
