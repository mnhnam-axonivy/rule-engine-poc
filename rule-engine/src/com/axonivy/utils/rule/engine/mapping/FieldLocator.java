package com.axonivy.utils.rule.engine.mapping;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * The fields of the process data that the mapping of a Business Rule Task names, e.g. <code>in.order.amount</code>.
 */
public final class FieldLocator {

  private FieldLocator() {}

  /**
   * @param root class of the process data
   * @param expression a field path that starts with the variable of the process data, e.g. <code>in.member.type</code>
   * @return the declared type of the field, empty if the path does not lead to a field
   */
  public static Optional<Type> typeOf(Class<?> root, String expression) {
    String[] segments = expression.trim().split("\\.");
    Type type = root;
    for (int i = 1; i < segments.length; i++) {
      var property = BeanProperties.find(rawType(type), segments[i].trim());
      if (property.isEmpty()) {
        return Optional.empty();
      }
      type = property.get().type();
    }
    return Optional.of(type);
  }

  /**
   * Reads a field without touching the process data: a nested object that is not set gives null,
   * it is not created on the way.
   *
   * @param data the process data
   * @param expression a field path that starts with the variable of the process data, e.g. <code>in.member.type</code>
   * @return the value (which may be null), or empty if the path does not lead to a field
   */
  public static Optional<Optional<Object>> read(Object data, String expression) {
    String[] segments = expression.trim().split("\\.");
    Object current = data;
    for (int i = 1; i < segments.length; i++) {
      if (current == null) {
        return Optional.of(Optional.empty());
      }
      var property = BeanProperties.find(current.getClass(), segments[i].trim());
      if (property.isEmpty()) {
        return Optional.empty();
      }
      current = property.get().read(current);
    }
    return Optional.of(Optional.ofNullable(current));
  }

  private static Class<?> rawType(Type type) {
    if (type instanceof Class<?> raw) {
      return raw;
    }
    if (type instanceof ParameterizedType parameterized) {
      return (Class<?>) parameterized.getRawType();
    }
    return Object.class;
  }
}
