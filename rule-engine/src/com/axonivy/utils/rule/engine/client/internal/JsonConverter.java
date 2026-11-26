package com.axonivy.utils.rule.engine.client.internal;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class JsonConverter {

  private static final ObjectMapper MAPPER = JsonMapper.builder()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
      .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
      .build();

  private JsonConverter() {}

  public static String toJson(Object value) {
    return MAPPER.writeValueAsString(value);
  }

  public static <T> T fromJson(String json, Class<T> type) {
    return MAPPER.readValue(json, type);
  }

  public static <T> T fromJson(String json, TypeReference<T> type) {
    return MAPPER.readValue(json, type);
  }

  public static ObjectMapper mapper() {
    return MAPPER;
  }
}
