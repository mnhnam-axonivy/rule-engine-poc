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

public final class RuleEngine {

  private RuleEngine() {}

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

  public static EvaluateResponse evaluate(String model, String decision, Map<String, Object> input) {
    var config = RuleEngineConfig.fromVariables();
    return client(config).evaluate(config.runtimeModel(model),
        EvaluateRequest.builder().decision(decision).input(input).build());
  }

  public static List<ModelVersion> versions(String model) {
    var config = RuleEngineConfig.fromVariables();
    return client(config).versions(config.runtimeModel(model));
  }

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

  public static ModelVersion approve(String model, int version) {
    var config = RuleEngineConfig.fromVariables();
    return client(config).approve(config.runtimeModel(model), version);
  }

  public static ModelVersion activate(String model, int version, String user) {
    var config = RuleEngineConfig.fromVariables();
    return client(config).activate(config.runtimeModel(model), version, ActivateRequest.now(user));
  }

  public static ModelVersion activate(String model, int version, String user, OffsetDateTime effectiveDate) {
    var config = RuleEngineConfig.fromVariables();
    return client(config).activate(config.runtimeModel(model), version, ActivateRequest.at(user, effectiveDate));
  }

  public static ModelVersion rollback(String model, String user) {
    var config = RuleEngineConfig.fromVariables();
    return client(config).rollback(config.runtimeModel(model), user);
  }

  public static ModelVersion retire(String model, int version) {
    var config = RuleEngineConfig.fromVariables();
    return client(config).retire(config.runtimeModel(model), version);
  }

  public static List<ModelVersion> deploy(Project project) {
    var config = RuleEngineConfig.fromVariables();
    return ModelDeployer.deployAll(project, client(config), config);
  }

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
