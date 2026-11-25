package com.axonivy.utils.rule.engine.runtime.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

import com.axonivy.utils.rule.engine.runtime.api.model.ModelInfo;
import com.axonivy.utils.rule.engine.runtime.api.model.VersionSource;
import com.axonivy.utils.rule.engine.runtime.dmn.CompiledModel;
import com.axonivy.utils.rule.engine.runtime.dmn.DmnCompiler;
import com.axonivy.utils.rule.engine.runtime.dmn.DmnEvaluator;
import com.axonivy.utils.rule.engine.runtime.dmn.DmnEvaluator.Evaluation;
import com.axonivy.utils.rule.engine.runtime.service.RuleEngineException.Code;
import com.axonivy.utils.rule.engine.runtime.service.VersionStore.NewVersion;
import com.axonivy.utils.rule.engine.runtime.service.VersionStore.Registration;
import com.axonivy.utils.rule.engine.runtime.store.ModelVersionEntity;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Use cases of the runtime: load versions, change which one is active and evaluate decisions.
 */
@ApplicationScoped
public class VersionService {

  private static final Logger LOG = Logger.getLogger(VersionService.class);
  private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,199}");

  @Inject
  VersionStore store;

  @Inject
  ModelRegistry registry;

  @Inject
  DmnCompiler compiler;

  @Inject
  DmnEvaluator evaluator;

  /** Serializes all changes, so version numbers and the single active version stay consistent. */
  private final ReentrantLock changes = new ReentrantLock();

  public record RegisterCommand(
      String content,
      String author,
      String description,
      VersionSource source,
      Boolean activate,
      OffsetDateTime effectiveDate) {}

  public record EvaluationOutcome(CompiledModel model, String decision, Evaluation evaluation) {}

  /**
   * Stores the content as a new version of the model. Content that is already stored is not duplicated.
   * @throws com.axonivy.utils.rule.engine.runtime.dmn.InvalidModelException if the content is not a valid DMN model
   */
  public Registration register(String model, RegisterCommand command) {
    requireValidName(model);
    var content = command.content();
    var compiled = compiler.validateAndCompile(model, 0, content);
    var request = new NewVersion(model, content, sha256(content), compiled.dmnName(), compiled.dmnNamespace(),
        command.author(), command.description(),
        command.source() == null ? VersionSource.API : command.source(),
        command.activate(), command.effectiveDate());
    var registration = changing(() -> store.register(request));
    if (registration.created()) {
      LOG.infof("Registered version %d of model '%s' (source %s, status %s)", registration.version().version,
          model, registration.version().source, registration.version().status);
    }
    return registration;
  }

  public ModelVersionEntity approve(String model, int version) {
    return changing(() -> store.approve(model, version));
  }

  public ModelVersionEntity activate(String model, int version, String user, OffsetDateTime effectiveDate) {
    return changing(() -> store.activate(model, version, user, effectiveDate));
  }

  public ModelVersionEntity retire(String model, int version) {
    return changing(() -> store.retire(model, version));
  }

  public ModelVersionEntity rollback(String model, String user) {
    return changing(() -> store.rollback(model, user));
  }

  public void delete(String model) {
    changing(() -> store.delete(model));
    registry.evict(model);
  }

  public List<ModelVersionEntity> versions(String model) {
    return store.versions(model);
  }

  public ModelVersionEntity version(String model, int version) {
    return store.get(model, version);
  }

  public List<String> modelNames() {
    return store.modelNames();
  }

  /**
   * Evaluates the active version of a model.
   */
  public EvaluationOutcome evaluate(String model, String decision, java.util.Map<String, Object> input) {
    var compiled = registry.active(model).orElseThrow(() -> missing(model));
    return evaluate(compiled, decision, input);
  }

  /**
   * Evaluates a specific version, active or not. Meant for test runs before a version goes live.
   */
  public EvaluationOutcome evaluate(String model, int version, String decision, java.util.Map<String, Object> input) {
    return evaluate(registry.version(model, version), decision, input);
  }

  private EvaluationOutcome evaluate(CompiledModel compiled, String decision, java.util.Map<String, Object> input) {
    var name = decision == null || decision.isBlank() ? null : decision.trim();
    if (name != null && !compiled.hasDecision(name)) {
      var known = compiled.decisions().stream().map(ModelInfo.Decision::name).toList();
      throw new RuleEngineException(Code.DECISION_NOT_FOUND, "Model '" + compiled.model() + "' version "
          + compiled.version() + " has no decision '" + name + "'. Available decisions: " + known);
    }
    var evaluation = evaluator.evaluate(compiled, name, input);
    if (evaluation.hasErrors()) {
      var first = evaluation.messages().stream()
          .filter(message -> message.isError())
          .map(message -> message.message())
          .findFirst()
          .orElse("unknown error");
      throw new RuleEngineException(Code.EVALUATION_FAILED, "Evaluation of model '" + compiled.model()
          + "' version " + compiled.version() + " failed: " + first, evaluation.messages());
    }
    return new EvaluationOutcome(compiled, name, evaluation);
  }

  private RuleEngineException missing(String model) {
    if (!store.exists(model)) {
      return RuleEngineException.modelNotFound(model);
    }
    var failure = registry.failures().get(model);
    if (failure != null) {
      return new RuleEngineException(Code.INTERNAL_ERROR, "Model '" + model + "' is not available. " + failure);
    }
    return RuleEngineException.noActiveVersion(model);
  }

  /**
   * Makes scheduled versions live once their effective date is reached and picks up activations
   * that were written to the store by another runtime instance.
   */
  @Scheduled(every = "${rule.engine.sync-interval:5s}", concurrentExecution = ConcurrentExecution.SKIP)
  void syncWithStore() {
    try {
      changes.lock();
      try {
        for (var activated : store.activateDueVersions()) {
          LOG.infof("Version %d of model '%s' reached its effective date", activated.version, activated.model);
        }
      } finally {
        changes.unlock();
      }
      registry.sync();
    } catch (RuntimeException ex) {
      LOG.error("Synchronizing the models with the store failed", ex);
    }
  }

  private <T> T changing(Supplier<T> change) {
    changes.lock();
    try {
      T result = change.get();
      registry.sync();
      return result;
    } finally {
      changes.unlock();
    }
  }

  private static void requireValidName(String model) {
    if (model == null || !MODEL_NAME.matcher(model).matches()) {
      throw RuleEngineException.invalidRequest("Invalid model name '" + model
          + "'. Use letters, digits, '.', '_' and '-' (max. 200 characters).");
    }
  }

  /**
   * Line endings are ignored, so the same file checked out on Windows and Linux is one version.
   */
  static String sha256(String content) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      var normalized = content.replace("\r\n", "\n").replace('\r', '\n').strip();
      return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}

