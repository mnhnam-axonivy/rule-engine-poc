package com.axonivy.portal.rule;

import java.io.Serializable;
import java.util.Map;

/**
 * A version of a rule on the decision runtime. Every change is a new version; exactly one is active.
 *
 * @param status DRAFT, APPROVED, ACTIVE or RETIRED
 * @param source PROJECT (deployed with an Ivy project), PORTAL (saved in the Portal) or API
 * @param createdAt ISO-8601 time, like the other times
 * @param effectiveDate when the version went live, or goes live if its activation is planned
 * @param created only after a save: false if exactly this content was stored already
 */
public record RuleVersion(String model, int version, String status, String source, String author, String description,
    String createdAt, String effectiveDate, String activatedBy, Integer previousVersion,
    Boolean created) implements Serializable {

  public static final String ACTIVE = "ACTIVE";

  public static RuleVersion of(Map<String, Object> version) {
    Integer number = RuleModel.number(version.get("version"));
    return new RuleVersion(
        RuleModel.text(version.get("model")),
        number == null ? 0 : number,
        RuleModel.text(version.get("status")),
        RuleModel.text(version.get("source")),
        RuleModel.text(version.get("author")),
        RuleModel.text(version.get("description")),
        RuleModel.text(version.get("createdAt")),
        RuleModel.text(version.get("effectiveDate")),
        RuleModel.text(version.get("activatedBy")),
        RuleModel.number(version.get("previousVersion")),
        version.get("created") instanceof Boolean flag ? flag : null);
  }

  public boolean isActive() {
    return ACTIVE.equals(status);
  }
}
