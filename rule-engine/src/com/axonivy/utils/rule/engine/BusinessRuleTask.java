package com.axonivy.utils.rule.engine;

import java.net.URI;
import java.util.Map;

import com.axonivy.utils.rule.engine.deploy.ModelDeployer;
import com.axonivy.utils.rule.engine.program.internal.DesignTimeCreation;
import com.axonivy.utils.rule.engine.program.internal.RuleTaskEditor;
import com.axonivy.utils.rule.engine.program.internal.RuleTaskExecutor;
import com.axonivy.utils.rule.engine.version.VersionStore;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.environment.Ivy;
import ch.ivyteam.ivy.process.program.activity.AbortableExecution;
import ch.ivyteam.ivy.process.program.activity.ProgramExecutor;
import ch.ivyteam.ivy.process.program.element.ProgramIconDecorator;
import ch.ivyteam.ivy.process.program.exec.ProgramContext;
import ch.ivyteam.ivy.process.program.migrate.ProgramConfigMigrator;
import ch.ivyteam.ivy.process.program.ui.ProgramEditorUi;
import ch.ivyteam.ivy.process.program.ui.ProgramUiBuilder;

/**
 * Business Rule Task: evaluates a DMN decision with process data, without code.
 * <p>The decision is evaluated by the decision runtime of the Axon Ivy Rules Engine. Inputs and results are
 * mapped by name. Runtime not reachable, DMN errors and timeouts raise BPMN errors.</p>
 */
public class BusinessRuleTask implements ProgramExecutor, ProgramEditorUi, ProgramIconDecorator, ProgramConfigMigrator {

  @Override
  public AbortableExecution newExecution() {
    return new AbortableExecution() {

      private volatile Thread worker;

      @Override
      public void execute(ProgramContext context) {
        worker = Thread.currentThread();
        try {
          new RuleTaskExecutor(context).execute();
        } finally {
          worker = null;
        }
      }

      @Override
      public void abort() {
        Thread running = worker;
        if (running != null) {
          running.interrupt();
        }
      }
    };
  }

  /**
   * On project start, e.g. after a deployment: the .dmn files of the project are stored as versions.
   * If the runtime is not reachable now to check them, they are stored with the first evaluation.
   */
  @Override
  public void start() {
    try {
      Project project = Project.current();
      var config = RuleEngineConfig.fromVariables();
      if (project == null || !config.autoDeploy() || config.url().isBlank()) {
        return;
      }
      ModelDeployer.deployAll(project, RuleEngine.client(config), VersionStore.current());
    } catch (RuntimeException ex) {
      Ivy.log().debug("Rule engine: models are registered with the first evaluation: " + ex.getMessage());
    }
  }

  @Override
  public void editor(ProgramUiBuilder ui) {
    new RuleTaskEditor().editor(ui);
  }

  @Override
  public URI icon() {
    return URI.create("res:/webContent/logo/business-rule-task.png");
  }

  @Override
  public Map<String, String> migrateConfig(Map<String, String> config) {
    try {
      return DesignTimeCreation.selectCreated(Project.current(), config);
    } catch (RuntimeException ex) {
      return config;
    }
  }
}
