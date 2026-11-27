package com.axonivy.utils.rule.engine.version;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.axonivy.utils.rule.engine.api.ModelVersion;
import com.axonivy.utils.rule.engine.api.RegisterVersionRequest;
import com.axonivy.utils.rule.engine.api.VersionSource;
import com.axonivy.utils.rule.engine.api.VersionStatus;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;
import com.axonivy.utils.rule.engine.dmn.DmnModelInfo;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;

import ch.ivyteam.ivy.environment.Ivy;

/**
 * Changes are serialized within this engine only. In a cluster, the file area has to be on a shared file system, and
 * two nodes changing the same model at the same moment are not coordinated.
 */
public final class VersionStore {

  private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]{0,198}[A-Za-z0-9_-])?");
  private static final Object CHANGES = new Object();

  private final VersionFiles files;

  private VersionStore(VersionFiles files) {
    this.files = files;
  }

  public static VersionStore current() {
    return new VersionStore(VersionFiles.ofApplication());
  }

  public Path directory() {
    return files.root();
  }

  /**
   * Stores the content as a new version of the model. Content that is stored already is not stored again: its version
   * is answered, with <code>created</code> false. The content is not checked here, the decision runtime checks it
   * before: see {@link com.axonivy.utils.rule.engine.deploy.ModelDeployer}.
   */
  public ModelVersion register(String model, RegisterVersionRequest request) {
    requireValidName(model);
    if (request == null || request.content() == null || request.content().isBlank()) {
      throw invalidRequest("The request needs the DMN file in 'content'.");
    }
    String content = request.content();
    String hash = ProjectDmnFiles.hash(content);
    VersionSource source = request.source() == null ? VersionSource.API : request.source();
    synchronized (CHANGES) {
      var index = files.read(model).orElseGet(() -> ModelIndex.of(model));
      boolean changed = activateDue(index);
      var existing = stored(model, index, hash);
      var entry = existing.orElseGet(() -> newVersion(model, index, request, source, content));
      if (shouldActivate(request, source, entry, index)) {
        activate(index, entry, request.author(), request.effectiveDate(), false);
        changed = true;
      }
      if (changed || existing.isEmpty()) {
        files.write(model, index);
      }
      if (existing.isEmpty()) {
        Ivy.log().info("Rule engine: stored version " + entry.version + " of model '" + model + "' (source "
            + entry.source + ", " + entry.status + ")");
      }
      return entry.toApi(model, existing.isEmpty());
    }
  }

  public ModelVersion approve(String model, int version) {
    return change(model, index -> {
      var entry = require(index, model, version);
      if (entry.status != VersionStatus.DRAFT) {
        throw invalidState("Only a draft can be approved. Version " + version + " of '" + model + "' is "
            + entry.status.name().toLowerCase() + ".");
      }
      entry.status = VersionStatus.APPROVED;
      return entry;
    });
  }

  /** Makes a version live, now or at the effective date. The version that was active is retired. */
  public ModelVersion activate(String model, int version, String user, OffsetDateTime effectiveDate) {
    return change(model, index -> {
      var entry = require(index, model, version);
      activate(index, entry, user, effectiveDate, false);
      return entry;
    });
  }

  public ModelVersion retire(String model, int version) {
    return change(model, index -> {
      var entry = require(index, model, version);
      if (entry.status == VersionStatus.ACTIVE) {
        throw invalidState("Version " + version + " of '" + model
            + "' is active. Activate another version or roll back before retiring it.");
      }
      if (entry.status == VersionStatus.APPROVED) {
        entry.effectiveDate = null; // a planned activation is cancelled; a version that was live keeps its date
      }
      entry.status = VersionStatus.RETIRED;
      return entry;
    });
  }

  /** Activates the version that was active before the current one. */
  public ModelVersion rollback(String model, String user) {
    return change(model, index -> {
      var active = index.active().orElseThrow(() -> noActiveVersion(model));
      if (active.previousVersion == null) {
        throw invalidState("Version " + active.version + " of '" + model
            + "' is the first active version, there is nothing to roll back to.");
      }
      var previous = require(index, model, active.previousVersion);
      activate(index, previous, user, null, true);
      return previous;
    });
  }

  public void delete(String model) {
    synchronized (CHANGES) {
      index(model);
      files.delete(model);
    }
  }

  public List<String> models() {
    return files.models().stream().filter(VersionStore::isValidName).toList();
  }

  public boolean exists(String model) {
    return isValidName(model) && files.read(model).isPresent();
  }

  public List<ModelVersion> versions(String model) {
    return index(model).newestFirst().stream().map(entry -> entry.toApi(model, null)).toList();
  }

  public ModelVersion version(String model, int version) {
    return require(index(model), model, version).toApi(model, null);
  }

  public String content(String model, int version) {
    require(index(model), model, version);
    return files.content(model, version);
  }

  /**
   * @return the version that answers evaluations. A version whose effective date has been reached is activated
   *         first.
   * @throws RuleEngineApiException MODEL_NOT_FOUND or NO_ACTIVE_VERSION
   */
  public ModelVersion active(String model) {
    var index = index(model);
    return index.active().orElseThrow(() -> noActiveVersion(model)).toApi(model, null);
  }

  /** @return what a version decides and needs, read from its DMN file; null if the file cannot be read as DMN */
  public DmnModelInfo describe(ModelVersion version) {
    return parse(content(version.model(), version.version()));
  }

  /** @return the versions of the model, with the versions whose effective date has been reached activated */
  private ModelIndex index(String model) {
    requireValidName(model);
    var index = files.read(model).orElseThrow(() -> modelNotFound(model));
    if (index.due(now()).isEmpty()) {
      return index;
    }
    synchronized (CHANGES) {
      var current = files.read(model).orElseThrow(() -> modelNotFound(model));
      if (activateDue(current)) {
        files.write(model, current);
      }
      return current;
    }
  }

  private ModelVersion change(String model, Function<ModelIndex, ModelIndex.Entry> change) {
    requireValidName(model);
    synchronized (CHANGES) {
      var index = files.read(model).orElseThrow(() -> modelNotFound(model));
      activateDue(index);
      var changed = change.apply(index);
      files.write(model, index);
      return changed.toApi(model, null);
    }
  }

  private ModelIndex.Entry newVersion(String model, ModelIndex index, RegisterVersionRequest request,
      VersionSource source, String content) {
    var entry = new ModelIndex.Entry();
    entry.version = index.nextVersion();
    entry.status = VersionStatus.DRAFT;
    entry.source = source;
    entry.author = request.author();
    entry.description = request.description();
    entry.createdAt = now();
    files.writeContent(model, entry.version, content);
    index.versions.add(entry);
    return entry;
  }

  /** @return the newest version whose DMN file has this content, line endings ignored */
  private Optional<ModelIndex.Entry> stored(String model, ModelIndex index, String hash) {
    return index.newestFirst().stream()
        .filter(entry -> hash.equals(ProjectDmnFiles.hash(files.content(model, entry.version))))
        .findFirst();
  }

  /**
   * The source of truth rule.
   * <ul>
   * <li>An explicit choice of the caller always wins.</li>
   * <li>The first version of a model goes live at once.</li>
   * <li>A project deployment goes live as long as the active version also came from a project.</li>
   * <li>Everything else stays a draft: a version activated from the Portal is never overwritten by a deployment.</li>
   * </ul>
   */
  private static boolean shouldActivate(RegisterVersionRequest request, VersionSource source,
      ModelIndex.Entry candidate, ModelIndex index) {
    if (candidate.status == VersionStatus.ACTIVE) {
      return false;
    }
    if (request.activate() != null) {
      return request.activate();
    }
    if (request.effectiveDate() != null) {
      return true; // an effective date is a request to go live at that time
    }
    var active = index.active();
    if (active.isEmpty()) {
      return true;
    }
    return source == VersionSource.PROJECT && active.get().source == VersionSource.PROJECT;
  }

  private static boolean activateDue(ModelIndex index) {
    var due = index.due(now());
    for (var entry : due) {
      // live from its effective date: no evaluation ran in between, this read is the first after it
      goLive(index, entry, entry.activatedBy, entry.effectiveDate, false);
      Ivy.log().info("Rule engine: version " + entry.version + " of model '" + index.model
          + "' reached its effective date");
    }
    return !due.isEmpty();
  }

  private static void activate(ModelIndex index, ModelIndex.Entry entry, String user, OffsetDateTime effectiveDate,
      boolean rollback) {
    if (entry.status == VersionStatus.ACTIVE) {
      return;
    }
    var now = now();
    if (effectiveDate != null && effectiveDate.isAfter(now)) {
      // goes live later: the first read after the effective date activates it
      entry.status = VersionStatus.APPROVED;
      entry.effectiveDate = effectiveDate.withOffsetSameInstant(ZoneOffset.UTC);
      entry.activatedBy = user;
      return;
    }
    goLive(index, entry, user, now, rollback);
  }

  private static void goLive(ModelIndex index, ModelIndex.Entry entry, String user, OffsetDateTime from,
      boolean rollback) {
    var current = index.active();
    current.ifPresent(active -> active.status = VersionStatus.RETIRED);
    if (!rollback) {
      // a rollback keeps the history of the target, so repeated rollbacks walk further back
      entry.previousVersion = current.map(active -> active.version).orElse(null);
    }
    entry.status = VersionStatus.ACTIVE;
    entry.effectiveDate = from;
    entry.activatedBy = user;
  }

  /** Times are stored in UTC. */
  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static DmnModelInfo parse(String content) {
    try {
      return DmnModelInfo.parse(content);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static ModelIndex.Entry require(ModelIndex index, String model, int version) {
    return index.find(version).orElseThrow(() -> new RuleEngineApiException(404,
        RuleEngineApiException.VERSION_NOT_FOUND, "Model '" + model + "' has no version " + version + ".", null, null));
  }

  private static boolean isValidName(String model) {
    return model != null && MODEL_NAME.matcher(model).matches();
  }

  private static void requireValidName(String model) {
    if (!isValidName(model)) {
      throw invalidRequest("Invalid model name '" + model + "'. Use letters, digits, '.', '_' and '-' "
          + "(max. 200 characters, not ending with '.').");
    }
  }

  private static RuleEngineApiException modelNotFound(String model) {
    return new RuleEngineApiException(404, RuleEngineApiException.MODEL_NOT_FOUND,
        "There is no model '" + model + "'.", null, null);
  }

  private static RuleEngineApiException noActiveVersion(String model) {
    return new RuleEngineApiException(409, RuleEngineApiException.NO_ACTIVE_VERSION,
        "Model '" + model + "' has no active version. Activate one of its versions first.", null, null);
  }

  private static RuleEngineApiException invalidState(String message) {
    return new RuleEngineApiException(409, RuleEngineApiException.INVALID_STATE, message, null, null);
  }

  private static RuleEngineApiException invalidRequest(String message) {
    return new RuleEngineApiException(400, RuleEngineApiException.INVALID_REQUEST, message, null, null);
  }
}
