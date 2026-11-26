package com.axonivy.utils.rule.engine.mapping;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.axonivy.utils.rule.engine.dmn.generator.BeanProperties;
import com.axonivy.utils.rule.engine.dmn.generator.FeelNames;

public final class FieldLocator {

  private static final int MAX_DEPTH = 4;

  public record Field(String expression, Type type) {}

  private record Node(Class<?> type, String expression, int depth, List<Class<?>> parents) {}

  private FieldLocator() {}

  /**
   * Searches level by level: a field of the process data wins over a field of a nested data class. An exact name wins
   * over a name that only differs in case, blanks or separators (<code>Risk Level</code> finds <code>riskLevel</code>).
   */
  public static Optional<Field> findByName(Class<?> root, String rootExpression, String name) {
    String fieldName = FeelNames.toFieldName(name);
    Optional<Field> exact = search(root, rootExpression, fieldName, false);
    return exact.isPresent() ? exact : search(root, rootExpression, fieldName, true);
  }

  private static Optional<Field> search(Class<?> root, String rootExpression, String name, boolean tolerant) {
    String wanted = tolerant ? normalize(name) : name;
    var queue = new ArrayDeque<Node>();
    queue.add(new Node(root, rootExpression, 0, List.of()));
    while (!queue.isEmpty()) {
      Node node = queue.poll();
      var properties = BeanProperties.of(node.type());
      for (var property : properties) {
        String candidate = tolerant ? normalize(property.name()) : property.name();
        if (candidate.equals(wanted)) {
          return Optional.of(new Field(node.expression() + "." + property.name(), property.type()));
        }
      }
      if (node.depth() >= MAX_DEPTH) {
        continue;
      }
      for (var property : properties) {
        Class<?> nested = rawType(property.type());
        if (isDataClass(nested) && !node.parents().contains(nested) && nested != node.type()) {
          var parents = new ArrayList<>(node.parents());
          parents.add(node.type());
          queue.add(new Node(nested, node.expression() + "." + property.name(), node.depth() + 1, parents));
        }
      }
    }
    return Optional.empty();
  }

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
   * Reads a field without touching the process data: a nested object that is not set gives null, it is not created on
   * the way.
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

  private static boolean isDataClass(Class<?> type) {
    if (type == null || type.isPrimitive() || type.isEnum() || type.isArray() || type.isInterface()) {
      return false;
    }
    String name = type.getName();
    return !name.startsWith("java.") && !name.startsWith("javax.") && !name.startsWith("jakarta.")
        && !name.startsWith("ch.ivyteam.");
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

  public static String normalize(String name) {
    var normalized = new StringBuilder();
    for (char c : name.toCharArray()) {
      if (Character.isLetterOrDigit(c)) {
        normalized.append(Character.toLowerCase(c));
      }
    }
    return normalized.toString().toLowerCase(Locale.ROOT);
  }
}
