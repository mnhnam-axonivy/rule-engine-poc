package com.axonivy.utils.rule.engine.runtime.api;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import com.axonivy.utils.rule.engine.runtime.service.ModelRegistry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Ready once the active versions are loaded from the store. Reports active versions that could not be loaded.
 */
@Readiness
@ApplicationScoped
public class ModelsHealthCheck implements HealthCheck {

  @Inject
  ModelRegistry registry;

  @Override
  public HealthCheckResponse call() {
    var failures = registry.failures();
    var response = HealthCheckResponse.named("Active DMN models")
        .status(registry.isLoaded() && failures.isEmpty())
        .withData("activeModels", registry.activeCount());
    failures.forEach((model, failure) -> response.withData("failed." + model, failure));
    return response.build();
  }
}
