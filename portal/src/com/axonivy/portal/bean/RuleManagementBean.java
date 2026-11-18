package com.axonivy.portal.bean;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.primefaces.PrimeFaces;

import com.axonivy.portal.components.service.DateTimeGlobalSettingService;
import com.axonivy.portal.components.util.FacesMessageUtils;
import com.axonivy.portal.rule.RuleEngineAnswer;
import com.axonivy.portal.rule.RuleEngineBridge;
import com.axonivy.portal.rule.RuleEngineError;
import com.axonivy.portal.rule.RuleModel;
import com.axonivy.portal.rule.RuleVersion;

import ch.ivy.addon.portalkit.util.PermissionUtils;
import ch.ivyteam.ivy.environment.Ivy;

/**
 * The tab "Rule Management" of the Admin Settings: the rules (DMN models) of the Axon Ivy Rules Engine, their
 * versions, and the DMN editor of Apache KIE to change them. Everything goes through {@link RuleEngineBridge}; the
 * editor itself runs in the browser (rule-management.js).
 */
@Named
@ViewScoped
public class RuleManagementBean implements Serializable {

  private static final long serialVersionUID = 1L;
  private static final String CMS = "/ch.ivy.addon.portalkit.ui.jsf/components/RuleManagement/";
  private static final int MAX_FINDINGS = 12;

  private String runtimeUrl;
  private boolean reachable;
  private String editorScript;
  private String loadProblem;
  private List<RuleModel> models = new ArrayList<>();
  private List<RuleModel> filteredModels;

  private RuleModel versionsOf;
  private List<RuleVersion> versions = new ArrayList<>();

  private boolean editing;
  private String editModel;
  private Integer editVersion;
  private String editStatus;
  private String editProject;
  private String dmn;
  private String description;

  @PostConstruct
  public void init() {
    load();
  }

  /** Reads the state of the runtime and its models again. */
  public void refresh() {
    load();
  }

  private void load() {
    if (!allowed()) {
      return;
    }
    RuleEngineAnswer info = RuleEngineBridge.info();
    runtimeUrl = info.isOk() ? (String) info.map().get("runtimeUrl") : null;
    reachable = info.isOk() && Boolean.TRUE.equals(info.map().get("reachable"));
    editorScript = info.isOk() ? (String) info.map().get("editorScript") : null;
    RuleEngineAnswer answer = RuleEngineBridge.models();
    if (answer.isOk()) {
      models = answer.list().stream().map(RuleModel::of).toList();
      loadProblem = null;
    } else {
      models = List.of();
      loadProblem = describe(answer.error());
    }
    filteredModels = null;
  }

  // ── the editor ────────────────────────────────────────────────────────────────

  /** Opens the active version of a rule, or the .dmn file of its project if the runtime does not know it yet. */
  public void edit(RuleModel model) {
    open(model.name(), null);
  }

  /** Opens a version from the list of versions. */
  public void editVersion(RuleVersion version) {
    open(versionsOf.name(), version.version());
  }

  private void open(String model, Integer version) {
    if (!allowed()) {
      return;
    }
    RuleEngineAnswer answer = RuleEngineBridge.content(model, version);
    if (!answer.isOk()) {
      error(answer.error());
      return;
    }
    var content = answer.map();
    editModel = model;
    editVersion = content.get("version") instanceof Number number ? number.intValue() : null;
    editProject = (String) content.get("project");
    editStatus = statusOf(model, editVersion);
    dmn = (String) content.get("dmn");
    description = null;
    editing = true;
    PrimeFaces.current().ajax().addCallbackParam("opened", true);
  }

  public void closeEditor() {
    editing = false;
    dmn = null;
    load();
  }

  /** Stores what the editor holds as a new version; the request parameter activate says whether it goes live. */
  public void save() {
    if (!allowed()) {
      return;
    }
    boolean activate = "true".equals(FacesContext.getCurrentInstance().getExternalContext()
        .getRequestParameterMap().get("activate"));
    RuleEngineAnswer answer = RuleEngineBridge.save(editModel, dmn, author(), description, activate);
    if (!answer.isOk()) {
      error(answer.error());
      return;
    }
    RuleVersion version = RuleVersion.of(answer.map());
    editVersion = version.version();
    editStatus = version.status();
    editProject = null;
    String key = Boolean.FALSE.equals(version.created()) ? "Messages/Unchanged"
        : version.isActive() ? "Messages/SavedActive" : "Messages/SavedDraft";
    info(cms(key, editModel, version.version()));
    description = null;
    PrimeFaces.current().ajax().addCallbackParam("saved", true);
    load();
  }

  /** Lets the DMN engine check what the editor holds, without storing it. */
  public void validate() {
    if (!allowed()) {
      return;
    }
    RuleEngineAnswer answer = RuleEngineBridge.validate(dmn);
    if (!answer.isOk()) {
      error(answer.error());
      return;
    }
    if (Boolean.TRUE.equals(answer.map().get("valid"))) {
      info(cms("Messages/Valid", editModel));
    } else {
      Object messages = answer.map().get("messages");
      List<String> findings = messages instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
      error(new RuleEngineError(RuleEngineError.INVALID_MODEL, cms("Messages/Invalid", editModel), findings));
    }
  }

  // ── versions ──────────────────────────────────────────────────────────────────

  public void showVersions(RuleModel model) {
    versionsOf = model;
    loadVersions();
  }

  private void loadVersions() {
    if (!allowed() || versionsOf == null) {
      return;
    }
    RuleEngineAnswer answer = RuleEngineBridge.versions(versionsOf.name());
    versions = answer.isOk() ? answer.list().stream().map(RuleVersion::of).toList() : List.of();
    if (!answer.isOk()) {
      error(answer.error());
    }
  }

  public void activate(RuleVersion version) {
    if (!allowed()) {
      return;
    }
    RuleEngineAnswer answer = RuleEngineBridge.activate(versionsOf.name(), version.version(), author());
    if (answer.isOk()) {
      info(cms("Messages/Activated", versionsOf.name(), version.version()));
    } else {
      error(answer.error());
    }
    reloadAfterChange();
  }

  public void rollback() {
    if (!allowed()) {
      return;
    }
    RuleEngineAnswer answer = RuleEngineBridge.rollback(versionsOf.name(), author());
    if (answer.isOk()) {
      info(cms("Messages/RolledBack", versionsOf.name(), RuleVersion.of(answer.map()).version()));
    } else {
      error(answer.error());
    }
    reloadAfterChange();
  }

  private void reloadAfterChange() {
    String name = versionsOf.name();
    load();
    versionsOf = models.stream().filter(model -> model.name().equals(name)).findFirst().orElse(versionsOf);
    loadVersions();
  }

  // ── what the page shows ───────────────────────────────────────────────────────

  /** EL sees only the components of a record, so derived values come from here. */
  public String decisionNames(RuleModel model) {
    return String.join(", ", model.decisions());
  }

  public String status(String status) {
    return StringUtils.isBlank(status) ? "" : cms("Status/" + status);
  }

  public String source(String source) {
    return StringUtils.isBlank(source) ? "" : cms("Source/" + source);
  }

  /** @return an ISO-8601 time of the runtime in the date format of the Portal */
  public String time(String iso) {
    if (StringUtils.isBlank(iso)) {
      return "";
    }
    try {
      var formatter = DateTimeFormatter.ofPattern(DateTimeGlobalSettingService.getInstance().getDateTimePattern(),
          Ivy.session().getFormattingLocale());
      return OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(formatter);
    } catch (DateTimeParseException | IllegalArgumentException ex) {
      return iso;
    }
  }

  /** @return what is open in the editor, e.g. "Version 3 · Active" or "dmn/discount.dmn of rule-engine-demo" */
  public String getEditorState() {
    if (editVersion == null) {
      return cms("ProjectFile", StringUtils.defaultString(editProject));
    }
    String state = cms("VersionLabel", editVersion);
    return StringUtils.isBlank(editStatus) ? state : state + " · " + status(editStatus);
  }

  public boolean isNotConfigured() {
    return StringUtils.isBlank(runtimeUrl);
  }

  private String statusOf(String model, Integer version) {
    if (version == null) {
      return null;
    }
    return models.stream()
        .filter(candidate -> candidate.name().equals(model))
        .findFirst()
        .map(candidate -> version.equals(candidate.activeVersion()) ? RuleVersion.ACTIVE : null)
        .orElse(null);
  }

  // ── helpers ───────────────────────────────────────────────────────────────────

  /** Admin Settings are for administrators; the bean checks it again, the tab is not the only way in. */
  private static boolean allowed() {
    if (PermissionUtils.isSessionUserHasAdminRole()) {
      return true;
    }
    FacesContext.getCurrentInstance().addMessage(null, FacesMessageUtils.sanitizedMessage(FacesMessage.SEVERITY_ERROR,
        Ivy.cms().co("/ch.ivy.addon.portalkit.ui.jsf/permission/noPermission"), null));
    return false;
  }

  private static String author() {
    return Ivy.session().getSessionUserName();
  }

  private String describe(RuleEngineError error) {
    if (RuleEngineError.NOT_CONFIGURED.equals(error.code())) {
      return cms("Messages/NotConfigured");
    }
    if (error.isConnectionProblem()) {
      return cms("Messages/Unreachable", StringUtils.defaultString(runtimeUrl));
    }
    return error.message();
  }

  private void error(RuleEngineError error) {
    var context = FacesContext.getCurrentInstance();
    context.addMessage(null, FacesMessageUtils.sanitizedMessage(FacesMessage.SEVERITY_ERROR, describe(error), null));
    List<String> details = error.details();
    details.stream().limit(MAX_FINDINGS).forEach(finding -> context.addMessage(null,
        FacesMessageUtils.sanitizedMessage(FacesMessage.SEVERITY_WARN, finding, null)));
    if (details.size() > MAX_FINDINGS) {
      context.addMessage(null, FacesMessageUtils.sanitizedMessage(FacesMessage.SEVERITY_WARN,
          cms("Messages/MoreFindings", details.size() - MAX_FINDINGS), null));
    }
  }

  private static void info(String message) {
    FacesContext.getCurrentInstance().addMessage(null,
        FacesMessageUtils.sanitizedMessage(FacesMessage.SEVERITY_INFO, message, null));
  }

  private static String cms(String key, Object... params) {
    return params.length == 0 ? Ivy.cms().co(CMS + key) : Ivy.cms().co(CMS + key, List.of(params));
  }

  // ── getters and setters ───────────────────────────────────────────────────────

  public String getRuntimeUrl() {
    return runtimeUrl;
  }

  public boolean isReachable() {
    return reachable;
  }

  public String getEditorScript() {
    return editorScript;
  }

  public String getLoadProblem() {
    return loadProblem;
  }

  public List<RuleModel> getModels() {
    return models;
  }

  public List<RuleModel> getFilteredModels() {
    return filteredModels;
  }

  public void setFilteredModels(List<RuleModel> filteredModels) {
    this.filteredModels = filteredModels;
  }

  public RuleModel getVersionsOf() {
    return versionsOf;
  }

  public List<RuleVersion> getVersions() {
    return versions;
  }

  public boolean isEditing() {
    return editing;
  }

  public String getEditModel() {
    return editModel;
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
}
