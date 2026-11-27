package com.axonivy.utils.rule.engine.runtime.api;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import com.axonivy.utils.rule.engine.runtime.service.ModelRegistry;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Ready from the start: the runtime holds no data, the Ivy application loads the versions it needs.
 */
@Readiness
@ApplicationScoped
public class ModelsHealthCheck implements HealthCheck {

  private final ModelRegistry registry;

  ModelsHealthCheck(ModelRegistry registry) {
    this.registry = registry;
  }

  @Override
  public HealthCheckResponse call() {
    return HealthCheckResponse.named("Loaded DMN models")
        .up()
        .withData("loadedVersions", registry.size())
        .build();
  }
}
