package com.axonivy.utils.rule.engine.deploy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.axonivy.utils.rule.engine.api.ModelInfo;
import com.axonivy.utils.rule.engine.api.VersionSource;
import com.axonivy.utils.rule.engine.client.RuleEngineClient;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;

/**
 * A rule owner may change a model in the Portal so that it needs inputs that the .dmn file of the project does not
 * have, e.g. a new column <code>customerType</code>. That version answers the evaluations, so the Business Rule Task
 * has to send its inputs too. The runtime is asked at most every few seconds per model.
 */
public final class ActiveVersionInputs {

  public record Active(int version, VersionSource source, List<String> inputs) {}

  private static final long TTL_MILLIS = 5_000;

  private record Cached(long loadedAt, ModelInfo info, VersionSource source) {}

  private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

  private ActiveVersionInputs() {}

  public static Optional<Active> of(RuleEngineClient client, String runtimeModel, String decision) {
    String key = key(client, runtimeModel);
    Cached cached = CACHE.get(key);
    if (cached == null || System.currentTimeMillis() - cached.loadedAt() > TTL_MILLIS) {
      cached = load(client, runtimeModel);
      CACHE.put(key, cached);
    }
    ModelInfo info = cached.info();
    if (info == null || info.activeVersion() == null) {
      return Optional.empty();
    }
    List<String> inputs = decision == null
        ? info.inputs().stream().map(ModelInfo.Input::name).toList()
        : info.decisions().stream()
            .filter(candidate -> candidate.name().equals(decision))
            .findFirst()
            .map(ModelInfo.Decision::requiredInputs)
            .orElse(List.of());
    return Optional.of(new Active(info.activeVersion(), cached.source(), inputs == null ? List.of() : inputs));
  }

  public static void forget(RuleEngineClient client, String runtimeModel) {
    CACHE.remove(key(client, runtimeModel));
  }

  public static void reset() {
    CACHE.clear();
  }

  private static Cached load(RuleEngineClient client, String runtimeModel) {
    long now = System.currentTimeMillis();
    try {
      ModelInfo info = client.model(runtimeModel);
      VersionSource source = info.activeVersion() == null
          ? null
          : client.version(runtimeModel, info.activeVersion()).source();
      return new Cached(now, info, source);
    } catch (RuleEngineApiException ex) {
      if (ex.isModelMissing()) {
        return new Cached(now, null, null);
      }
      throw ex;
    }
  }

  private static String key(RuleEngineClient client, String runtimeModel) {
    return client.baseUrl() + "|" + runtimeModel;
  }
}
