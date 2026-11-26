package com.axonivy.utils.rule.engine.portal;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;

import com.axonivy.utils.rule.engine.RuleEngine;
import com.axonivy.utils.rule.engine.RuleEngineConfig;
import com.axonivy.utils.rule.engine.api.ActivateRequest;
import com.axonivy.utils.rule.engine.api.Message;
import com.axonivy.utils.rule.engine.api.ModelInfo;
import com.axonivy.utils.rule.engine.api.ModelVersion;
import com.axonivy.utils.rule.engine.api.RegisterVersionRequest;
import com.axonivy.utils.rule.engine.api.VersionSource;
import com.axonivy.utils.rule.engine.client.RuleEngineClient;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineTimeoutException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineUnreachableException;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles.DmnFile;

import ch.ivyteam.ivy.application.app.Application;
import ch.ivyteam.ivy.environment.Ivy;

/**
 * The Portal has no dependency on the rule engine: it finds the callable sub process <code>Portal/RuleManagement</code>
 * by its signatures in the security context. So everything that crosses is a JDK type: String, Integer, Boolean, List
 * and Map.
 */
public final class RuleManagement {

  public static final String REST_PATH = "rule-engine/dmn-editor";
  public static final String EDITOR_SCRIPT = "dmn-editor.js";

  public static final String UNREACHABLE = "UNREACHABLE";
  public static final String TIMEOUT = "TIMEOUT";
  public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
  public static final String FAILED = "FAILED";

  private RuleManagement() {}

  public static RuleManagementAnswer info() {
    var config = RuleEngineConfig.fromVariables();
    var info = new LinkedHashMap<String, Object>();
    info.put("runtimeUrl", config.url());
    info.put("modelPrefix", config.modelPrefix());
    info.put("editorScript", editorScriptLink());
    boolean reachable = false;
    try {
      reachable = !config.url().isBlank() && RuleEngine.client(config).isReady();
    } catch (RuntimeException ex) {
      Ivy.log().debug("Rule management: the runtime is not ready", ex);
    }
    info.put("reachable", reachable);
    return new RuleManagementAnswer(info, null);
  }

  public static RuleManagementAnswer models() {
    return answer(() -> {
      var config = RuleEngineConfig.fromVariables();
      String prefix = config.modelPrefix();
      var models = new TreeMap<String, Map<String, Object>>(String.CASE_INSENSITIVE_ORDER);
      for (ModelInfo info : client(config).models()) {
        if (!info.name().startsWith(prefix)) {
          continue; // another application on the same runtime
        }
        String name = info.name().substring(prefix.length());
        var model = new LinkedHashMap<String, Object>();
        model.put("name", name);
        model.put("onRuntime", true);
        model.put("activeVersion", info.activeVersion());
        model.put("latestVersion", info.latestVersion());
        model.put("versionCount", info.versionCount());
        model.put("decisions", info.decisions().stream().map(ModelInfo.Decision::name).toList());
        models.put(name, model);
      }
      for (DmnFile file : projectFiles()) {
        var model = models.computeIfAbsent(file.model(), name -> {
          var notYetOnRuntime = new LinkedHashMap<String, Object>();
          notYetOnRuntime.put("name", name);
          notYetOnRuntime.put("onRuntime", false);
          notYetOnRuntime.put("versionCount", 0);
          notYetOnRuntime.put("decisions", List.of());
          return notYetOnRuntime;
        });
        model.putIfAbsent("project", file.project());
        model.putIfAbsent("file", file.path().getFileName().toString());
      }
      return new ArrayList<>(models.values());
    });
  }

  public static RuleManagementAnswer versions(String model) {
    return answer(() -> {
      var config = RuleEngineConfig.fromVariables();
      return client(config).versions(config.runtimeModel(model)).stream()
          .sorted(Comparator.comparingInt(ModelVersion::version).reversed())
          .map(version -> version(version, config.modelPrefix()))
          .toList();
    });
  }

  /**
   * A version of 0 counts as null: the parameter mapping of a callable sub process turns a null Integer into 0, and
   * versions start at 1.
   */
  public static RuleManagementAnswer content(String model, Integer version) {
    return answer(() -> {
      var config = RuleEngineConfig.fromVariables();
      var client = client(config);
      String runtimeModel = config.runtimeModel(model);
      var content = new LinkedHashMap<String, Object>();
      content.put("model", model);
      Integer wanted = version == null || version.intValue() <= 0 ? null : version;
      if (wanted == null) {
        var file = projectFiles().stream().filter(candidate -> candidate.model().equals(model)).findFirst();
        ModelInfo info = onRuntime(client, runtimeModel);
        if (info == null && file.isPresent()) {
          content.put("version", null);
          content.put("project", file.get().project());
          content.put("dmn", ProjectDmnFiles.load(file.get()).content());
          return content;
        }
        if (info == null) {
          throw new RuleEngineApiException(404, RuleEngineApiException.MODEL_NOT_FOUND,
              "There is no model '" + model + "', neither on the runtime nor in a project.", null, null);
        }
        Integer active = info.activeVersion();
        wanted = active != null ? active : Integer.valueOf(info.latestVersion());
      }
      content.put("version", wanted);
      content.put("dmn", client.content(runtimeModel, wanted.intValue()));
      return content;
    });
  }

  public static RuleManagementAnswer validate(String dmn) {
    return answer(() -> {
      var result = client(RuleEngineConfig.fromVariables()).validate(dmn);
      var validation = new LinkedHashMap<String, Object>();
      validation.put("valid", result.valid());
      validation.put("messages", result.messages().stream().map(RuleManagement::text).toList());
      return validation;
    });
  }

  public static RuleManagementAnswer save(String model, String dmn, String author, String description, Boolean activate) {
    return answer(() -> {
      if (StringUtils.isBlank(model) || StringUtils.isBlank(dmn)) {
        throw new RuleEngineApiException(400, RuleEngineApiException.INVALID_REQUEST,
            "A model name and its DMN are required.", null, null);
      }
      var config = RuleEngineConfig.fromVariables();
      ModelVersion version = client(config).registerVersion(config.runtimeModel(model), RegisterVersionRequest.builder()
          .content(dmn)
          .author(author)
          .description(StringUtils.trimToNull(description))
          .source(VersionSource.PORTAL)
          .activate(Boolean.TRUE.equals(activate))
          .build());
      return version(version, config.modelPrefix());
    });
  }

  public static RuleManagementAnswer activate(String model, Integer version, String author) {
    return answer(() -> {
      if (version == null || version.intValue() <= 0) {
        throw new RuleEngineApiException(400, RuleEngineApiException.INVALID_REQUEST,
            "Which version of '" + model + "' should be activated?", null, null);
      }
      var config = RuleEngineConfig.fromVariables();
      return version(client(config).activate(config.runtimeModel(model), version.intValue(),
          ActivateRequest.now(author)), config.modelPrefix());
    });
  }

  public static RuleManagementAnswer rollback(String model, String author) {
    return answer(() -> {
      var config = RuleEngineConfig.fromVariables();
      return version(client(config).rollback(config.runtimeModel(model), author), config.modelPrefix());
    });
  }

  static Map<String, Object> version(ModelVersion version, String prefix) {
    var map = new LinkedHashMap<String, Object>();
    String model = version.model();
    map.put("model", model != null && model.startsWith(prefix) ? model.substring(prefix.length()) : model);
    map.put("version", version.version());
    map.put("status", version.status() == null ? null : version.status().name());
    map.put("source", version.source() == null ? null : version.source().name());
    map.put("author", version.author());
    map.put("description", version.description());
    map.put("createdAt", text(version.createdAt()));
    map.put("activatedAt", text(version.activatedAt()));
    map.put("activatedBy", version.activatedBy());
    map.put("effectiveDate", text(version.effectiveDate()));
    map.put("previousVersion", version.previousVersion());
    map.put("created", version.created());
    return map;
  }

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

  private static RuleEngineClient client(RuleEngineConfig config) {
    return RuleEngine.client(config);
  }

  private static ModelInfo onRuntime(RuleEngineClient client, String runtimeModel) {
    try {
      return client.model(runtimeModel);
    } catch (RuleEngineApiException ex) {
      if (ex.isModelMissing()) {
        return null;
      }
      throw ex;
    }
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
