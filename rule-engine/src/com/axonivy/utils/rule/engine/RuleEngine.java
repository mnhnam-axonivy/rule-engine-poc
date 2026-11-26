package com.axonivy.utils.rule.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.axonivy.utils.rule.engine.api.ActivateRequest;
import com.axonivy.utils.rule.engine.api.EvaluateRequest;
import com.axonivy.utils.rule.engine.api.EvaluateResponse;
import com.axonivy.utils.rule.engine.api.ModelVersion;
import com.axonivy.utils.rule.engine.api.RegisterVersionRequest;
import com.axonivy.utils.rule.engine.api.VersionSource;
import com.axonivy.utils.rule.engine.client.RuleEngineClient;
import com.axonivy.utils.rule.engine.deploy.ModelDeployer;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;

import ch.ivyteam.ivy.application.project.Project;

/**
 * Java API of the Axon Ivy Rules Engine: evaluate decisions and manage the versions of DMN models.
 * <p>This is the API for a Rules UI. Everything goes to the decision runtime that is configured in the
 * variables <code>RuleEngine.*</code>. A change is live on the runtime as soon as the call returns.</p>
 *
 * <pre><code>
 * // a business user changed a rule in the editor of the Portal
 * ModelVersion draft = RuleEngine.register("discount", dmnXml, "rule.editor", "Gold discount raised");
 * RuleEngine.approve("discount", draft.version());
 * RuleEngine.activate("discount", draft.version(), "rule.approver");
 * // something is wrong: back to the version that was active before
 * RuleEngine.rollback("discount", "rule.approver");
 * </code></pre>
 */
public final class RuleEngine {

  private RuleEngine() {}

  /** @return a client of the decision runtime, configured by the variables <code>RuleEngine.*</code> */
  public static RuleEngineClient client() {
    return client(RuleEngineConfig.fromVariables());
  }

  public static RuleEngineClient client(RuleEngineConfig config) {
    if (config.url().isBlank()) {
      throw new IllegalStateException("The URL of the rule engine is not configured. Set the variable "
          + RuleEngineConfig.Var.URL + ".");
    }
    return RuleEngineClient.builder()
        .baseUrl(config.url())
        .apiKey(config.apiKey())
        .connectTimeout(config.connectTimeout())
        .readTimeout(config.timeout())
        .logRequests(config.logRequests())
        .build();
  }

  /**
   * Evaluates a decision with the active version of a model.
   *
   * @param model name of the model, the name of the .dmn file without extension
   * @param decision name of the decision, or null to evaluate all decisions of the model
   * @param input values by the name of the DMN input
   */
  public static EvaluateResponse evaluate(String model, String decision, Map<String, Object> input) {
    var config = RuleEngineConfig.fromVariables();
    return client(config).evaluate(config.runtimeModel(model),
        EvaluateRequest.builder().decision(decision).input(input).build());
  }

  /** @return all versions of a model, newest first */
  public static List<ModelVersion> versions(String model) {
    var config = RuleEngineConfig.fromVariables();
    return client(config).versions(config.runtimeModel(model));
  }

  /**
   * Stores a changed DMN file as a new draft, e.g. from a Rules UI. It does not go live before it is activated.
   */
  public static ModelVersion register(String model, String dmn, String author, String description) {
    var config = RuleEngineConfig.fromVariables();
    return client(config).registerVersion(config.runtimeModel(model), RegisterVersionRequest.builder()
        .content(dmn)
        .author(author)
        .description(description)
        .source(VersionSource.PORTAL)
        .activate(false)
        .build());
  }

  /** Releases a draft for activation. */
  public static ModelVersion approve(String model, int version) {
    var config = RuleEngineConfig.fromVariables();
    return client(config).approve(config.runtimeModel(model), version);
  }

  /** Makes a version live on the runtime at once. The version that was active is retired. */
  public static ModelVersion activate(String model, int version, String user) {
    var config = RuleEngineConfig.fromVariables();
    return client(config).activate(config.runtimeModel(model), version, ActivateRequest.now(user));
  }

  /** Makes a version live at the effective date. Until then the current version stays active. */
  public static ModelVersion activate(String model, int version, String user, OffsetDateTime effectiveDate) {
    var config = RuleEngineConfig.fromVariables();
    return client(config).activate(config.runtimeModel(model), version, ActivateRequest.at(user, effectiveDate));
  }

  /** Activates the version that was active before the current one. */
  public static ModelVersion rollback(String model, String user) {
    var config = RuleEngineConfig.fromVariables();
    return client(config).rollback(config.runtimeModel(model), user);
  }

  /** Withdraws a version that is not active. */
  public static ModelVersion retire(String model, int version) {
    var config = RuleEngineConfig.fromVariables();
    return client(config).retire(config.runtimeModel(model), version);
  }

  /**
   * Registers the .dmn files of a project as versions on the runtime. This is what happens on a deployment.
   * A version from the project goes live, unless the active version was activated from the Portal.
   *
   * @return the version of every .dmn file of the project
   */
  public static List<ModelVersion> deploy(Project project) {
    var config = RuleEngineConfig.fromVariables();
    return ModelDeployer.deployAll(project, client(config), config);
  }

  /**
   * Writes the DMN file of a version back into the dmn folder of a project,
   * so the project holds the rules that are live.
   *
   * @return the written file
   */
  public static Path export(String model, int version, Project project) {
    var config = RuleEngineConfig.fromVariables();
    String dmn = client(config).content(config.runtimeModel(model), version);
    Path file = ProjectDmnFiles.find(project, model)
        .map(ProjectDmnFiles.DmnFile::path)
        .orElseGet(() -> project.directory().resolve(ProjectDmnFiles.DMN_FOLDER)
            .resolve(model + ProjectDmnFiles.DMN_EXTENSION));
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, dmn, StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException("Cannot write " + file, ex);
    }
    ProjectDmnFiles.refresh();
    return file;
  }
}
