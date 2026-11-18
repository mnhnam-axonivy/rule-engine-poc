package com.axonivy.utils.rule.engine.deploy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.axonivy.utils.rule.engine.RuleEngineConfig;
import com.axonivy.utils.rule.engine.api.ModelVersion;
import com.axonivy.utils.rule.engine.api.RegisterVersionRequest;
import com.axonivy.utils.rule.engine.api.VersionSource;
import com.axonivy.utils.rule.engine.client.RuleEngineClient;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles.LoadedDmn;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.environment.Ivy;

/**
 * Registers the .dmn files of Ivy projects as versions on the decision runtime.
 * <p>The runtime stores a content only once, so registering the same file again is harmless. Whether a new
 * version goes live is decided by the runtime: a project version replaces a project version, but never a
 * version that was activated from the Portal.</p>
 */
public final class ModelDeployer {

  /** What this engine has already registered: runtime URL and model name to the fingerprint of the content. */
  private static final Map<String, String> REGISTERED = new ConcurrentHashMap<>();

  private ModelDeployer() {}

  /**
   * Registers the file, unless this engine already registered exactly this content.
   * Called before a decision is evaluated, so a changed file is live with the next evaluation.
   */
  public static void ensureRegistered(RuleEngineClient client, String runtimeModel, LoadedDmn dmn) {
    String key = key(client, runtimeModel);
    if (dmn.hash().equals(REGISTERED.get(key))) {
      return;
    }
    register(client, runtimeModel, dmn);
  }

  public static ModelVersion register(RuleEngineClient client, String runtimeModel, LoadedDmn dmn) {
    ModelVersion version = client.registerVersion(runtimeModel, RegisterVersionRequest.builder()
        .content(dmn.content())
        .author(dmn.file().projectCoordinates())
        .description("Deployed with project " + dmn.file().project())
        .source(VersionSource.PROJECT)
        .build());
    REGISTERED.put(key(client, runtimeModel), dmn.hash());
    ActiveVersionInputs.forget(client, runtimeModel); // the active version may have changed
    if (Boolean.TRUE.equals(version.created())) {
      Ivy.log().info("Rule engine: registered " + dmn.file().path().getFileName() + " as version "
          + version.version() + " of model '" + runtimeModel + "' (" + version.status() + ")");
    }
    if (!version.isActive()) {
      Ivy.log().info("Rule engine: version " + version.version() + " of model '" + runtimeModel
          + "' from project " + dmn.file().project() + " is not active (" + version.status()
          + "). A newer version was activated on the runtime, it stays the source of truth.");
    }
    return version;
  }

  /** The runtime does not know the model any more, e.g. its data was deleted: register it again next time. */
  public static void forget(RuleEngineClient client, String runtimeModel) {
    REGISTERED.remove(key(client, runtimeModel));
    ActiveVersionInputs.forget(client, runtimeModel);
  }

  /**
   * Registers all .dmn files of a project and of the projects it requires.
   * @return the version of each file. A file that the runtime rejects is logged and skipped.
   */
  public static List<ModelVersion> deployAll(Project project, RuleEngineClient client, RuleEngineConfig config) {
    var versions = new ArrayList<ModelVersion>();
    for (var file : ProjectDmnFiles.list(project)) {
      try {
        versions.add(register(client, config.runtimeModel(file.model()), ProjectDmnFiles.load(file)));
      } catch (RuntimeException ex) {
        Ivy.log().error("Rule engine: cannot register " + file.path() + ": " + ex.getMessage(), ex);
      }
    }
    return versions;
  }

  /** Forgets everything that was registered, so every file is registered again with its next use. */
  public static void reset() {
    REGISTERED.clear();
    ActiveVersionInputs.reset();
  }

  private static String key(RuleEngineClient client, String runtimeModel) {
    return client.baseUrl() + "|" + runtimeModel;
  }
}
