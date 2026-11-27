package com.axonivy.utils.rule.engine.deploy;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.axonivy.utils.rule.engine.api.EvaluateRequest;
import com.axonivy.utils.rule.engine.api.EvaluateResponse;
import com.axonivy.utils.rule.engine.api.LoadedVersion;
import com.axonivy.utils.rule.engine.api.ModelVersion;
import com.axonivy.utils.rule.engine.client.RuleEngineClient;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;
import com.axonivy.utils.rule.engine.version.VersionStore;

/**
 * The runtime keeps no data: after a restart, or once it made room for other versions, it answers that it does not
 * have a version, and the version is loaded again.
 */
public final class RuntimeLoader {

  private static final Map<String, Loaded> LOADED = new ConcurrentHashMap<>();

  /** A stored version is told apart by its creation time: a model that is stored again starts at version 1 again. */
  private record Loaded(OffsetDateTime createdAt, LoadedVersion version) {}

  private RuntimeLoader() {}

  /** @param runtimeModel with the model prefix, see {@link com.axonivy.utils.rule.engine.RuleEngineConfig#runtimeModel(String)} */
  public static LoadedVersion ensureLoaded(RuleEngineClient client, VersionStore store, String runtimeModel,
      ModelVersion version) {
    var loaded = LOADED.get(key(client, runtimeModel, version));
    if (loaded != null && Objects.equals(loaded.createdAt(), version.createdAt())) {
      return loaded.version();
    }
    return load(client, store, runtimeModel, version);
  }

  /** If the runtime does not hold the version, it is loaded and evaluated again. */
  public static EvaluateResponse evaluate(RuleEngineClient client, VersionStore store, String runtimeModel,
      ModelVersion version, EvaluateRequest request) {
    ensureLoaded(client, store, runtimeModel, version);
    try {
      return client.evaluate(runtimeModel, version.version(), request);
    } catch (RuleEngineApiException ex) {
      if (!ex.isNotLoaded()) {
        throw ex;
      }
      load(client, store, runtimeModel, version);
      return client.evaluate(runtimeModel, version.version(), request);
    }
  }

  public static void reset() {
    LOADED.clear();
  }

  private static LoadedVersion load(RuleEngineClient client, VersionStore store, String runtimeModel,
      ModelVersion version) {
    var loaded = client.load(runtimeModel, version.version(), store.content(version.model(), version.version()));
    LOADED.put(key(client, runtimeModel, version), new Loaded(version.createdAt(), loaded));
    return loaded;
  }

  private static String key(RuleEngineClient client, String runtimeModel, ModelVersion version) {
    return client.baseUrl() + "|" + runtimeModel + "|" + version.version();
  }
}
