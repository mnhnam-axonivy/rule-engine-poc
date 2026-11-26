package com.axonivy.utils.rule.engine.dmn.generator;

public record DmnField(String name, DmnType type) {

  public DmnField {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("A field needs a name");
    }
    if (type == null) {
      type = DmnType.ANY;
    }
  }

  public static DmnField of(String name, DmnType type) {
    return new DmnField(name, type);
  }

  public static DmnField of(String name, java.lang.reflect.Type javaType) {
    return new DmnField(name, new JavaTypeMapper().toDmnType(javaType));
  }
}
