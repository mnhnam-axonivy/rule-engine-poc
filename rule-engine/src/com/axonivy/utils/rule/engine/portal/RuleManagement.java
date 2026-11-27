package com.axonivy.utils.rule.engine.portal;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;

import com.axonivy.utils.rule.engine.RuleEngine;
import com.axonivy.utils.rule.engine.RuleEngineConfig;
import com.axonivy.utils.rule.engine.api.Message;
import com.axonivy.utils.rule.engine.api.ModelVersion;
import com.axonivy.utils.rule.engine.api.RegisterVersionRequest;
import com.axonivy.utils.rule.engine.api.VersionSource;
import com.axonivy.utils.rule.engine.api.VersionStatus;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineTimeoutException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineUnreachableException;
import com.axonivy.utils.rule.engine.deploy.ModelDeployer;
import com.axonivy.utils.rule.engine.dmn.DmnModelInfo;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles.DmnFile;
import com.axonivy.utils.rule.engine.version.VersionStore;

import ch.ivyteam.ivy.application.app.Application;
import ch.ivyteam.ivy.environment.Ivy;

/**
 * What the tab "Rule Management" of the Axon Ivy Portal shows and changes. The Portal calls it through the
 * callable sub process <code>Portal/RuleManagement</code> of this project.
 *
 * <p>The Portal has no dependency on the rule engine. It finds the callable by its signatures in the security
 * context, the way it finds Smart Workflow, and shows the tab only if it is there. So everything that crosses
 * is a JDK type: String, Integer, Boolean, List and Map.</p>
 *
 * <p>The versions are stored in the file area of this application ({@link VersionStore}). The DMN engine checks a
 * model before it is saved.</p>
 *
 * <p>Every call answers with a <code>result</code> and an <code>error</code>. On success the error is null.
 * Otherwise it is a map with <code>code</code> (UNREACHABLE, TIMEOUT, NOT_CONFIGURED, an error code such as
 * INVALID_MODEL, MODEL_NOT_FOUND or INVALID_STATE, or FAILED), <code>message</code> and <code>details</code>
 * (the findings of the DMN engine, as text).</p>
 *
 * <p>Model names are the names of the .dmn files, as in the Business Rule Task.</p>
 */
public final class RuleManagement {

  /** Path of the script of {@link com.axonivy.utils.rule.engine.editor.DmnEditor} under the REST API. */
  public static final String REST_PATH = "rule-engine/dmn-editor";
  public static final String EDITOR_SCRIPT = "dmn-editor.js";

  public static final String UNREACHABLE = "UNREACHABLE";
  public static final String TIMEOUT = "TIMEOUT";
  public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
  public static final String FAILED = "FAILED";

  private RuleManagement() {}

  /** @return runtimeUrl (of the DMN engine), reachable and editorScript (the link of the DMN editor script) */
  public static RuleManagementAnswer info() {
    var config = RuleEngineConfig.fromVariables();
    var info = new LinkedHashMap<String, Object>();
    info.put("runtimeUrl", config.url());
    info.put("editorScript", editorScriptLink());
    boolean reachable = false;
    try {
      reachable = !config.url().isBlank() && RuleEngine.client(config).isReady();
    } catch (RuntimeException ex) {
      Ivy.log().debug("Rule management: the DMN engine is not ready", ex);
    }
    info.put("reachable", reachable);
    return new RuleManagementAnswer(info, null);
  }

  /**
   * @return the stored models and the .dmn files of the projects of this application that are not stored yet, by
   *         name. A model has name, stored, activeVersion, latestVersion, versionCount, decisions (names), and
   *         project and file if a project has a .dmn file of that name. If the application brought a version
   *         that is not live, see {@link #pending(List)}, also pendingVersion and pendingFrom (its author: the
   *         application and its version).
   */
  public static RuleManagementAnswer models() {
    return answer(() -> {
      var store = VersionStore.current();
      var models = new TreeMap<String, Map<String, Object>>(String.CASE_INSENSITIVE_ORDER);
      for (String name : store.models()) {
        var versions = store.versions(name);
        var active = versions.stream().filter(ModelVersion::isActive).findFirst();
        var model = new LinkedHashMap<String, Object>();
        model.put("name", name);
        model.put("stored", true);
        model.put("activeVersion", active.map(ModelVersion::version).orElse(null));
        model.put("latestVersion", versions.get(0).version());
        model.put("versionCount", versions.size());
        model.put("decisions", decisions(store.describe(active.orElse(versions.get(0)))));
        pending(versions).ifPresent(version -> {
          model.put("pendingVersion", version.version());
          model.put("pendingFrom", version.author());
        });
        models.put(name, model);
      }
      for (DmnFile file : projectFiles()) {
        var model = models.computeIfAbsent(file.model(), name -> {
          var notStored = new LinkedHashMap<String, Object>();
          notStored.put("name", name);
          notStored.put("stored", false);
          notStored.put("versionCount", 0);
          notStored.put("decisions", decisions(ProjectDmnFiles.load(file).info()));
          return notStored;
        });
        model.putIfAbsent("project", file.project());
        model.putIfAbsent("file", file.path().getFileName().toString());
      }
      return new ArrayList<>(models.values());
    });
  }

  /**
   * A new .dmn file of a project is stored as a draft while a version that a rule owner activated stays active.
   * @param versions newest first
   */
  static Optional<ModelVersion> pending(List<ModelVersion> versions) {
    var active = versions.stream().filter(ModelVersion::isActive).findFirst();
    return versions.stream()
        .filter(version -> version.source() == VersionSource.PROJECT)
        .findFirst()
        .filter(version -> version.status() == VersionStatus.DRAFT)
        .filter(version -> active.map(live -> version.version() > live.version()).orElse(false));
  }

  /** @return the versions of a model, newest first: see {@link #version(ModelVersion)} */
  public static RuleManagementAnswer versions(String model) {
    return answer(() -> VersionStore.current().versions(model).stream().map(RuleManagement::version).toList());
  }

  /**
   * @param version the version to open, or null for the active one (the newest, if none is active). A model that
   *          is not stored yet is read from the .dmn file of its project. 0 counts as null: the parameter
   *          mapping of a callable sub process turns a null Integer into 0, and versions start at 1.
   * @return model, version (null for a project file), dmn and project (for a project file)
   */
  public static RuleManagementAnswer content(String model, Integer version) {
    return answer(() -> {
      var store = VersionStore.current();
      var content = new LinkedHashMap<String, Object>();
      content.put("model", model);
      Integer wanted = version == null || version.intValue() <= 0 ? null : version;
      if (wanted == null) {
        if (!store.exists(model)) {
          var file = projectFiles().stream().filter(candidate -> candidate.model().equals(model)).findFirst()
              .orElseThrow(() -> new RuleEngineApiException(404, RuleEngineApiException.MODEL_NOT_FOUND,
                  "There is no model '" + model + "', neither stored nor in a project.", null, null));
          content.put("version", null);
          content.put("project", file.project());
          content.put("dmn", ProjectDmnFiles.load(file).content());
          return content;
        }
        var versions = store.versions(model);
        wanted = versions.stream().filter(ModelVersion::isActive).findFirst().orElse(versions.get(0)).version();
      }
      content.put("version", wanted);
      content.put("dmn", store.content(model, wanted.intValue()));
      return content;
    });
  }

  /** @return valid and messages: the findings of the DMN engine, as text */
  public static RuleManagementAnswer validate(String dmn) {
    return answer(() -> {
      var result = RuleEngine.client().validate(dmn);
      var validation = new LinkedHashMap<String, Object>();
      validation.put("valid", result.valid());
      validation.put("messages", result.messages().stream().map(RuleManagement::text).toList());
      return validation;
    });
  }

  /**
   * Stores a changed model as a new version from the Portal. The DMN engine checks it first and refuses a model with
   * errors (code INVALID_MODEL, the findings in the details). A version activated from the Portal is never
   * replaced by a project deployment.
   *
   * @param activate true: go live now; false: keep it as a draft
   * @return the version, see {@link #version(ModelVersion)}. Its <code>created</code> is false if exactly this
   *         content was stored already.
   */
  public static RuleManagementAnswer save(String model, String dmn, String author, String description, Boolean activate) {
    return answer(() -> {
      if (StringUtils.isBlank(model) || StringUtils.isBlank(dmn)) {
        throw new RuleEngineApiException(400, RuleEngineApiException.INVALID_REQUEST,
            "A model name and its DMN are required.", null, null);
      }
      return version(ModelDeployer.register(RuleEngine.client(), VersionStore.current(), model,
          RegisterVersionRequest.builder()
              .content(dmn)
              .author(author)
              .description(StringUtils.trimToNull(description))
              .source(VersionSource.PORTAL)
              .activate(Boolean.TRUE.equals(activate))
              .build()));
    });
  }

  /** Makes a version live at once. The version that was active is retired. */
  public static RuleManagementAnswer activate(String model, Integer version, String author) {
    return answer(() -> version(VersionStore.current().activate(model, required(model, version), author, null)));
  }

  public static RuleManagementAnswer retire(String model, Integer version) {
    return answer(() -> version(VersionStore.current().retire(model, required(model, version))));
  }

  private static int required(String model, Integer version) {
    if (version == null || version.intValue() <= 0) {
      throw new RuleEngineApiException(400, RuleEngineApiException.INVALID_REQUEST,
          "Which version of '" + model + "' is meant?", null, null);
    }
    return version.intValue();
  }

  /** Activates the version that was active before the current one. */
  public static RuleManagementAnswer rollback(String model, String author) {
    return answer(() -> version(VersionStore.current().rollback(model, author)));
  }

  /**
   * @return model, version, status, source, author, description, createdAt, effectiveDate (live from; in the
   *         future if the activation is planned), activatedBy, previousVersion and created. Times are ISO-8601 texts,
   *         enums their names.
   */
  static Map<String, Object> version(ModelVersion version) {
    var map = new LinkedHashMap<String, Object>();
    map.put("model", version.model());
    map.put("version", version.version());
    map.put("status", version.status() == null ? null : version.status().name());
    map.put("source", version.source() == null ? null : version.source().name());
    map.put("author", version.author());
    map.put("description", version.description());
    map.put("createdAt", text(version.createdAt()));
    map.put("effectiveDate", text(version.effectiveDate()));
    map.put("activatedBy", version.activatedBy());
    map.put("previousVersion", version.previousVersion());
    map.put("created", version.created());
    return map;
  }

  /**
   * @return the link of the DMN editor script on this engine, relative to the server, or null outside of a
   *         request
   */
  static String editorScriptLink() {
    try {
      String base = Ivy.html().appBaseLink().getRelative();
      while (base.endsWith("/")) {
        base = base.substring(0, base.length() - 1);
      }
      return base + "/api/" + REST_PATH + "/" + EDITOR_SCRIPT;
    } catch (RuntimeException ex) {
      Ivy.log().debug("Rule management: no link to the DMN editor script outside of a request", ex);
      return null;
    }
  }

  private static List<String> decisions(DmnModelInfo info) {
    return info == null ? List.of() : info.decisions().stream().map(DmnModelInfo.Decision::name).toList();
  }

  /** The .dmn files of the projects of this application. A model of a project hides one of a later project. */
  private static List<DmnFile> projectFiles() {
    try {
      var files = new ArrayList<DmnFile>();
      Application.current().projects().all()
          .flatMap(project -> ProjectDmnFiles.filesOf(project).stream())
          .filter(file -> files.stream().noneMatch(known -> known.model().equals(file.model())))
          .forEach(files::add);
      return files;
    } catch (RuntimeException ex) {
      Ivy.log().warn("Rule management: cannot list the .dmn files of the projects", ex);
      return List.of();
    }
  }

  private static RuleManagementAnswer answer(Supplier<Object> call) {
    try {
      return new RuleManagementAnswer(call.get(), null);
    } catch (RuleEngineUnreachableException ex) {
      return new RuleManagementAnswer(null, error(UNREACHABLE, ex.getMessage(), List.of()));
    } catch (RuleEngineTimeoutException ex) {
      return new RuleManagementAnswer(null, error(TIMEOUT, ex.getMessage(), List.of()));
    } catch (RuleEngineApiException ex) {
      return new RuleManagementAnswer(null, error(ex.code(), ex.getMessage(), ex.details().stream().map(RuleManagement::text).toList()));
    } catch (IllegalStateException ex) {
      boolean notConfigured = ex.getMessage() != null && ex.getMessage().contains(RuleEngineConfig.Var.URL);
      return new RuleManagementAnswer(null, error(notConfigured ? NOT_CONFIGURED : FAILED, ex.getMessage(), List.of()));
    } catch (RuntimeException ex) {
      Ivy.log().error("Rule management: the call failed", ex);
      return new RuleManagementAnswer(null, error(FAILED, StringUtils.defaultIfBlank(ex.getMessage(), ex.getClass().getSimpleName()),
          List.of()));
    }
  }

  private static Map<String, Object> error(String code, String message, List<String> details) {
    var error = new LinkedHashMap<String, Object>();
    error.put("code", code);
    error.put("message", message);
    error.put("details", details);
    return error;
  }

  private static String text(Message message) {
    String severity = StringUtils.defaultIfBlank(message.severity(), Message.ERROR);
    String source = StringUtils.isBlank(message.sourceId()) ? "" : " (" + message.sourceId() + ")";
    return severity + ": " + message.message() + source;
  }

  private static String text(OffsetDateTime time) {
    return time == null ? null : time.toString();
  }
}
