package com.axonivy.utils.rule.engine.editor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.axonivy.utils.rule.engine.api.VersionStatus;
import com.axonivy.utils.rule.engine.portal.RuleManagement;
import com.axonivy.utils.rule.engine.portal.RuleManagementAnswer;
import com.axonivy.utils.rule.engine.version.VersionStore;

import ch.ivyteam.ivy.environment.Ivy;

/**
 * The rule in the dialog RuleEditor: its model, the version it was opened from, its DMN, and what the last check or
 * save said. The DMN editor of Apache KIE runs in the browser (rule-editor.js) and hands the DMN over before a check
 * or a save.
 */
public class RuleEditorController implements Serializable {

  private static final long serialVersionUID = 1L;
  private static final String CMS = "/Dialogs/com/axonivy/utils/rule/engine/editor/RuleEditor/";
  private static final int MAX_FINDINGS = 12;

  /** A message of the dialog; the severity is one of PrimeFaces: info, warn, error. */
  public record Notice(String severity, String text) implements Serializable {}

  private final String model;
  private Integer version;
  private String status;
  private String project;
  private String dmn;
  private String description;
  private boolean saved;
  private final List<Notice> notices = new ArrayList<>();

  private RuleEditorController(String model) {
    this.model = model;
  }

  /**
   * @param version the version to open; null or 0 opens the active one, or the .dmn file of the project if the model
   *          is not stored yet
   */
  public static RuleEditorController open(String model, Integer version) {
    var editor = new RuleEditorController(model);
    if (StringUtils.isBlank(model)) {
      editor.notice("error", cms("Messages/NoModel"));
      return editor;
    }
    RuleManagementAnswer answer = RuleManagement.content(model, version);
    if (answer.error() != null) {
      editor.problem(answer.error());
      return editor;
    }
    Map<?, ?> content = (Map<?, ?>) answer.result();
    editor.dmn = (String) content.get("dmn");
    editor.project = (String) content.get("project");
    editor.at(content.get("version") instanceof Number number ? number.intValue() : null);
    return editor;
  }

  /** Lets the DMN engine check the DMN, without storing it. */
  public void check() {
    start();
    RuleManagementAnswer answer = RuleManagement.validate(dmn);
    if (answer.error() != null) {
      problem(answer.error());
      return;
    }
    Map<?, ?> validation = (Map<?, ?>) answer.result();
    if (Boolean.TRUE.equals(validation.get("valid"))) {
      notice("info", cms("Messages/Valid", model));
    }
    else {
      notice("error", cms("Messages/Invalid", model));
    }
    findings(validation.get("messages"));
  }

  /** Stores the DMN as a new version: live at once, or as a draft. The DMN engine checks it first. */
  public void save(boolean activate) {
    start();
    RuleManagementAnswer answer = RuleManagement.save(model, dmn, Ivy.session().getSessionUserName(), description, activate);
    if (answer.error() != null) {
      problem(answer.error());
      return;
    }
    Map<?, ?> stored = (Map<?, ?>) answer.result();
    int number = ((Number) stored.get("version")).intValue();
    project = null;
    at(number);
    String key = Boolean.FALSE.equals(stored.get("created")) ? "Messages/Unchanged"
        : VersionStatus.ACTIVE.name().equals(status) ? "Messages/SavedActive" : "Messages/SavedDraft";
    notice("info", cms(key, model, number));
    description = null;
    saved = true;
  }

  /** @return the version and its status, or the project of a file that is not stored yet */
  public String getState() {
    if (version == null) {
      return project == null ? "" : cms("ProjectFile", project);
    }
    String label = cms("VersionLabel", version);
    return status == null ? label : label + " · " + cms("Status/" + status);
  }

  private void at(Integer number) {
    version = number;
    status = number == null ? null : VersionStore.current().versions(model).stream()
        .filter(candidate -> candidate.version() == number.intValue())
        .findFirst().map(candidate -> candidate.status().name()).orElse(null);
  }

  private void start() {
    notices.clear();
    saved = false;
  }

  private void problem(Object error) {
    Map<?, ?> map = (Map<?, ?>) error;
    notice("error", String.valueOf(map.get("message")));
    findings(map.get("details"));
  }

  private void findings(Object messages) {
    List<?> list = messages instanceof List<?> found ? found : List.of();
    list.stream().limit(MAX_FINDINGS).forEach(finding -> notice("warn", String.valueOf(finding)));
    if (list.size() > MAX_FINDINGS) {
      notice("warn", cms("Messages/MoreFindings", list.size() - MAX_FINDINGS));
    }
  }

  private void notice(String severity, String text) {
    notices.add(new Notice(severity, text));
  }

  private static String cms(String key, Object... params) {
    return params.length == 0 ? Ivy.cms().co(CMS + key) : Ivy.cms().co(CMS + key, List.of(params));
  }

  public String getModel() {
    return model;
  }

  public Integer getVersion() {
    return version;
  }

  public String getStatus() {
    return status;
  }

  public String getDmn() {
    return dmn;
  }

  public void setDmn(String dmn) {
    this.dmn = dmn;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  /** @return true if the last action stored a version */
  public boolean isSaved() {
    return saved;
  }

  /** @return true if there is a DMN to edit */
  public boolean isOpened() {
    return dmn != null;
  }

  public List<Notice> getNotices() {
    return notices;
  }
}
