package com.axonivy.utils.rule.engine.dmn.generator;

/**
 * A field that becomes an input, an output or a component of a structure in the generated model.
 *
 * @param name the field name, e.g. <code>purchaseAmount</code>
 * @param type the DMN type of the field
 */
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

  /**
   * @param name the field name
   * @param javaType the Java type of the field, e.g. the return type of its getter
   */
  public static DmnField of(String name, java.lang.reflect.Type javaType) {
    return new DmnField(name, new JavaTypeMapper().toDmnType(javaType));
  }
}
