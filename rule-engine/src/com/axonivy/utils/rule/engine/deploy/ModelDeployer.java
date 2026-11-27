package com.axonivy.utils.rule.engine.deploy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.axonivy.utils.rule.engine.api.Message;
import com.axonivy.utils.rule.engine.api.ModelVersion;
import com.axonivy.utils.rule.engine.api.RegisterVersionRequest;
import com.axonivy.utils.rule.engine.api.VersionSource;
import com.axonivy.utils.rule.engine.client.RuleEngineClient;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles.LoadedDmn;
import com.axonivy.utils.rule.engine.version.VersionStore;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.environment.Ivy;

/**
 * The store keeps a content only once, so registering the same file again is harmless.
 */
public final class ModelDeployer {

  /** What this engine has already registered: folder of the store and model name to the fingerprint of the content. */
  private static final Map<String, String> REGISTERED = new ConcurrentHashMap<>();

  private ModelDeployer() {}

  /** @throws RuleEngineApiException INVALID_MODEL with the findings of the DMN engine */
  public static ModelVersion register(RuleEngineClient client, VersionStore store, String model,
      RegisterVersionRequest request) {
    var validation = client.validate(request.content());
    if (!validation.valid()) {
      throw new RuleEngineApiException(400, RuleEngineApiException.INVALID_MODEL, invalid(validation.messages()),
          validation.messages(), null);
    }
    return store.register(model, request);
  }

  /**
   * Registers the file, unless this engine already registered exactly this content.
   * Called before a decision is evaluated, so a changed file is live with the next evaluation.
   */
  public static void ensureRegistered(RuleEngineClient client, VersionStore store, String model, LoadedDmn dmn) {
    if (dmn.hash().equals(REGISTERED.get(key(store, model))) && store.exists(model)) {
      return;
    }
    register(client, store, model, dmn);
  }

  /** The author is the application that brings the file, e.g. <code>invoice-app v3</code>. */
  public static ModelVersion register(RuleEngineClient client, VersionStore store, String model, LoadedDmn dmn) {
    var file = dmn.file();
    ModelVersion version = register(client, store, model, RegisterVersionRequest.builder()
        .content(dmn.content())
        .author(file.application())
        .description("Deployed with project " + file.project()
            + (file.projectVersion() == null ? "" : " " + file.projectVersion()))
        .source(VersionSource.PROJECT)
        .build());
    REGISTERED.put(key(store, model), dmn.hash());
    if (!version.isActive()) {
      Ivy.log().info("Rule engine: version " + version.version() + " of model '" + model + "' from project "
          + file.project() + " is not active (" + version.status() + "). A newer version was activated, it "
          + "stays the source of truth. The Portal offers to activate the version of the project.");
    }
    return version;
  }

  /**
   * Registers all .dmn files of a project and of the projects it requires.
   * @return the version of each file. A file that the DMN engine rejects is logged and skipped.
   */
  public static List<ModelVersion> deployAll(Project project, RuleEngineClient client, VersionStore store) {
    var versions = new ArrayList<ModelVersion>();
    for (var file : ProjectDmnFiles.list(project)) {
      try {
        versions.add(register(client, store, file.model(), ProjectDmnFiles.load(file)));
      } catch (RuntimeException ex) {
        Ivy.log().error("Rule engine: cannot register " + file.path() + ": " + ex.getMessage(), ex);
      }
    }
    return versions;
  }

  /** Forgets everything that was registered, so every file is registered again with its next use. */
  public static void reset() {
    REGISTERED.clear();
  }

  private static String invalid(List<Message> findings) {
    var errors = findings.stream().filter(Message::isError).map(Message::message).toList();
    if (errors.isEmpty()) {
      return "The DMN model is invalid.";
    }
    String more = errors.size() > 3 ? " (and " + (errors.size() - 3) + " more)" : "";
    return "The DMN model is invalid: " + String.join(" | ", errors.subList(0, Math.min(3, errors.size()))) + more;
  }

  private static String key(VersionStore store, String model) {
    return store.directory() + "|" + model;
  }
}
