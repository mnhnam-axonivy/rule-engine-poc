package com.axonivy.utils.rule.engine.runtime.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.axonivy.utils.rule.engine.runtime.dmn.CompiledModel;
import com.axonivy.utils.rule.engine.runtime.dmn.DmnCompiler;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Memory only: the Ivy application loads a version again whenever the runtime does not have it, after a restart or
 * once the version made room for others as the least recently used one.
 */
@ApplicationScoped
public class ModelRegistry {

  private static final Logger LOG = Logger.getLogger(ModelRegistry.class);
  private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,199}");

  private static final String INVALID_NAME =
      "Invalid model name '%s'. Use letters, digits, '.', '_' and '-' (max. 200 characters).";
  private static final String INVALID_VERSION = "Invalid version %d. Versions start at 1.";
  private static final String NO_CONTENT = "The request needs the DMN file.";

  private final DmnCompiler compiler;
  private final int cacheSize;

  public record Loaded(CompiledModel model, String contentHash) {}

  private record Key(String model, int version) {}

  /** In the order of use: the first one is the least recently used. */
  private final Map<Key, Loaded> versions = new LinkedHashMap<>(16, 0.75f, true);

  ModelRegistry(DmnCompiler compiler,
      @ConfigProperty(name = "rule.engine.model-cache-size", defaultValue = "100") int cacheSize) {
    this.compiler = compiler;
    this.cacheSize = cacheSize;
  }

  /**
   * Compiles the content and holds it as this version of the model. The same content is not compiled again.
   * @throws com.axonivy.utils.rule.engine.runtime.dmn.InvalidModelException if the content is not a valid DMN model
   */
  public Loaded load(String model, int version, String content) {
    requireValidRequest(model, version, content);
    var key = new Key(model, version);
    var hash = sha256(content);
    return heldWith(key, hash).orElseGet(() -> compileAndHold(key, content, hash));
  }

  /**
   * @throws RuleEngineException MODEL_NOT_FOUND or VERSION_NOT_FOUND if the version is not loaded: load it, then
   *         evaluate again
   */
  public CompiledModel get(String model, int version) {
    synchronized (versions) {
      var loaded = versions.get(new Key(model, version));
      if (loaded == null) {
        throw notLoaded(model, version);
      }
      return loaded.model();
    }
  }

  public List<Loaded> loaded() {
    synchronized (versions) {
      return versions.values().stream()
          .sorted(Comparator.comparing((Loaded loaded) -> loaded.model().model())
              .thenComparing(loaded -> loaded.model().version(), Comparator.reverseOrder()))
          .toList();
    }
  }

  public void unload(String model) {
    synchronized (versions) {
      versions.keySet().removeIf(key -> key.model().equals(model));
    }
  }

  public int size() {
    synchronized (versions) {
      return versions.size();
    }
  }

  private Optional<Loaded> heldWith(Key key, String hash) {
    synchronized (versions) {
      return Optional.ofNullable(versions.get(key)).filter(loaded -> loaded.contentHash().equals(hash));
    }
  }

  private Loaded compileAndHold(Key key, String content, String hash) {
    var loaded = new Loaded(compiler.validateAndCompile(key.model(), key.version(), content), hash);
    synchronized (versions) {
      versions.put(key, loaded);
      evictLeastRecentlyUsed();
    }
    LOG.infof("Loaded version %d of model '%s'", key.version(), key.model());
    return loaded;
  }

  /** Called under the lock of versions. */
  private void evictLeastRecentlyUsed() {
    var eldest = versions.keySet().iterator();
    while (versions.size() > Math.max(1, cacheSize) && eldest.hasNext()) {
      var evicted = eldest.next();
      eldest.remove();
      LOG.infof("Version %d of model '%s' made room for another one", evicted.version(), evicted.model());
    }
  }

  /** Called under the lock of versions. */
  private RuleEngineException notLoaded(String model, int version) {
    boolean modelKnown = versions.keySet().stream().anyMatch(key -> key.model().equals(model));
    return modelKnown ? RuleEngineException.versionNotFound(model, version) : RuleEngineException.modelNotFound(model);
  }

  private static void requireValidRequest(String model, int version, String content) {
    if (model == null || !MODEL_NAME.matcher(model).matches()) {
      throw RuleEngineException.invalidRequest(INVALID_NAME.formatted(model));
    }
    if (version < 1) {
      throw RuleEngineException.invalidRequest(INVALID_VERSION.formatted(version));
    }
    if (content == null || content.isBlank()) {
      throw RuleEngineException.invalidRequest(NO_CONTENT);
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
