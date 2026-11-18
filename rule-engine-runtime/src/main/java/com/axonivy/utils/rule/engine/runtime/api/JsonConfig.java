package com.axonivy.utils.rule.engine.runtime.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

/**
 * Decimal numbers are read as BigDecimal, the number type of FEEL. Nothing is lost on the way into a decision.
 */
@Singleton
public class JsonConfig implements ObjectMapperCustomizer {

  @Override
  public void customize(ObjectMapper mapper) {
    mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  }
}
