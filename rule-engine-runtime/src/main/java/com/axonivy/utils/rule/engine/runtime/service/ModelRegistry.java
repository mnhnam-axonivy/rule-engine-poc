package com.axonivy.utils.rule.engine.runtime.service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.axonivy.utils.rule.engine.runtime.dmn.CompiledModel;
import com.axonivy.utils.rule.engine.runtime.dmn.DmnCompiler;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * The executable models held in memory.
 * <p>The active version of every model is loaded at startup and replaced whenever another version becomes active.
 * A replacement only swaps a reference: evaluations that are running keep the version they started with.</p>
 */
@ApplicationScoped
public class ModelRegistry {

  private static final Logger LOG = Logger.getLogger(ModelRegistry.class);

  @Inject
  VersionStore store;

  @Inject
  DmnCompiler compiler;

  @ConfigProperty(name = "rule.engine.version-cache-size", defaultValue = "20")
  int versionCacheSize;

  private final Map<String, CompiledModel> active = new ConcurrentHashMap<>();
  private final Map<String, String> failures = new ConcurrentHashMap<>();
  private final Map<String, CompiledModel> versionCache = new LinkedHashMap<>(16, 0.75f, true);
  private volatile boolean loaded;

  void onStart(@Observes StartupEvent event) {
    sync();
    LOG.infof("Rules engine started with %d active model(s): %s", active.size(), active.keySet());
  }

  /**
   * Aligns the models in memory with the active versions in the store.
   */
  public synchronized void sync() {
    var refs = store.activeRefs();
    var models = new HashSet<String>();
    for (var ref : refs) {
      models.add(ref.model());
      var current = active.get(ref.model());
      if (current != null && current.version() == ref.version()) {
        continue;
      }
      load(ref.model(), ref.version());
    }
    active.keySet().removeIf(model -> !models.contains(model));
    failures.keySet().removeIf(model -> !models.contains(model));
    loaded = true;
  }

  private void load(String model, int version) {
    try {
      var content = store.get(model, version).content;
      var compiled = compiler.compile(model, version, content);
      var previous = active.put(model, compiled);
      failures.remove(model);
      LOG.infof("Model '%s' is now served by version %d%s", model, version,
          previous == null ? "" : " (was " + previous.version() + ")");
    } catch (Exception ex) {
      failures.put(model, "Version " + version + " cannot be loaded: " + ex.getMessage());
      LOG.errorf(ex, "Version %d of model '%s' cannot be loaded", version, model);
    }
  }

  public Optional<CompiledModel> active(String model) {
    return Optional.ofNullable(active.get(model));
  }

  /**
   * @return a specific version, compiled on demand. Used for test runs of versions that are not active.
   */
  public CompiledModel version(String model, int version) {
    var current = active.get(model);
    if (current != null && current.version() == version) {
      return current;
    }
    var key = model + "#" + version;
    synchronized (versionCache) {
      var cached = versionCache.get(key);
      if (cached != null) {
        return cached;
      }
    }
    var compiled = compiler.compile(model, version, store.get(model, version).content);
    synchronized (versionCache) {
      versionCache.put(key, compiled);
      var eldest = versionCache.keySet().iterator();
      while (versionCache.size() > Math.max(1, versionCacheSize) && eldest.hasNext()) {
        eldest.next();
        eldest.remove();
      }
    }
    return compiled;
  }

  /**
   * Forgets everything about a model that was deleted.
   */
  public synchronized void evict(String model) {
    active.remove(model);
    failures.remove(model);
    synchronized (versionCache) {
      versionCache.keySet().removeIf(key -> key.startsWith(model + "#"));
    }
  }

  public boolean isLoaded() {
    return loaded;
  }

  public int activeCount() {
    return active.size();
  }

  public Map<String, String> failures() {
    return Map.copyOf(failures);
  }
}
