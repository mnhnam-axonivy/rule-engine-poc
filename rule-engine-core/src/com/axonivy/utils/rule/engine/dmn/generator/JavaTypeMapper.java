package com.axonivy.utils.rule.engine.dmn.generator;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps Java types to DMN types. Works with plain reflection and class names only,
 * so the types of the Ivy scripting language are supported without a dependency on Ivy.
 */
public class JavaTypeMapper {

  private static final int MAX_DEPTH = 6;

  private static final Set<String> DATE = Set.of(
      "java.time.LocalDate", "java.sql.Date", "ch.ivyteam.ivy.scripting.objects.Date");
  private static final Set<String> DATE_TIME = Set.of(
      "java.time.LocalDateTime", "java.time.OffsetDateTime", "java.time.ZonedDateTime", "java.time.Instant",
      "java.util.Date", "java.sql.Timestamp", "java.util.Calendar", "java.util.GregorianCalendar",
      "ch.ivyteam.ivy.scripting.objects.DateTime");
  private static final Set<String> TIME = Set.of(
      "java.time.LocalTime", "java.time.OffsetTime", "java.sql.Time", "ch.ivyteam.ivy.scripting.objects.Time");
  private static final Set<String> DAYS_TIME_DURATION = Set.of(
      "java.time.Duration", "ch.ivyteam.ivy.scripting.objects.Duration");
  private static final Set<String> YEARS_MONTHS_DURATION = Set.of("java.time.Period");
  private static final Set<String> TEXT = Set.of(
      "java.lang.String", "java.lang.CharSequence", "java.lang.Character", "char", "java.util.UUID",
      "java.net.URI", "java.net.URL", "java.util.Locale", "java.util.Currency");

  /** Names of the structures created so far, so two classes with the same simple name stay apart. */
  private final Map<String, Class<?>> structureNames = new HashMap<>();

  public DmnType toDmnType(Type javaType) {
    return toDmnType(javaType, new ArrayList<>());
  }

  private DmnType toDmnType(Type javaType, List<Class<?>> path) {
    if (javaType instanceof ParameterizedType parameterized) {
      Class<?> raw = (Class<?>) parameterized.getRawType();
      if (isCollection(raw)) {
        return new DmnType.Collection(toDmnType(parameterized.getActualTypeArguments()[0], path));
      }
      return toDmnType(raw, path);
    }
    if (javaType instanceof GenericArrayType array) {
      return new DmnType.Collection(toDmnType(array.getGenericComponentType(), path));
    }
    if (javaType instanceof WildcardType wildcard) {
      return toDmnType(wildcard.getUpperBounds()[0], path);
    }
    if (javaType instanceof TypeVariable<?> variable) {
      return toDmnType(variable.getBounds()[0], path);
    }
    if (javaType instanceof Class<?> type) {
      return classToDmnType(type, path);
    }
    return DmnType.ANY;
  }

  private DmnType classToDmnType(Class<?> type, List<Class<?>> path) {
    String name = type.getName();
    if (TEXT.contains(name)) {
      return DmnType.STRING;
    }
    if (type == boolean.class || type == Boolean.class) {
      return DmnType.BOOLEAN;
    }
    if (type.isPrimitive() || Number.class.isAssignableFrom(type)) {
      return type == void.class ? DmnType.ANY : DmnType.NUMBER;
    }
    if (DATE.contains(name)) {
      return DmnType.DATE;
    }
    if (DATE_TIME.contains(name)) {
      return DmnType.DATE_TIME;
    }
    if (TIME.contains(name)) {
      return DmnType.TIME;
    }
    if (DAYS_TIME_DURATION.contains(name)) {
      return DmnType.DAYS_TIME_DURATION;
    }
    if (YEARS_MONTHS_DURATION.contains(name)) {
      return DmnType.YEARS_MONTHS_DURATION;
    }
    if (type.isEnum()) {
      var values = Arrays.stream(type.getEnumConstants()).map(constant -> ((Enum<?>) constant).name()).toList();
      return new DmnType.Enumeration(typeName(type), values);
    }
    if (type.isArray()) {
      return type.getComponentType() == byte.class
          ? DmnType.ANY
          : new DmnType.Collection(toDmnType(type.getComponentType(), path));
    }
    if (isCollection(type)) {
      return new DmnType.Collection(DmnType.ANY);
    }
    if (isOpaque(type)) {
      return DmnType.ANY;
    }
    return structure(type, path);
  }

  private DmnType structure(Class<?> type, List<Class<?>> path) {
    if (path.contains(type) || path.size() >= MAX_DEPTH) {
      return DmnType.ANY; // recursive data structures end here
    }
    var properties = BeanProperties.of(type);
    if (properties.isEmpty()) {
      return DmnType.ANY;
    }
    path.add(type);
    try {
      var fields = new ArrayList<DmnField>();
      for (var property : properties) {
        fields.add(new DmnField(property.name(), toDmnType(property.type(), path)));
      }
      return new DmnType.Structure(typeName(type), fields);
    } finally {
      path.remove(path.size() - 1);
    }
  }

  /**
   * Types without a meaning for a decision: maps, objects and the technical types of Ivy
   * like File, Binary, Xml, Record, Recordset and Tree.
   */
  private static boolean isOpaque(Class<?> type) {
    String name = type.getName();
    return type == Object.class
        || Map.class.isAssignableFrom(type)
        || type.isInterface()
        || name.startsWith("java.")
        || name.startsWith("javax.")
        || name.startsWith("jakarta.")
        || name.startsWith("ch.ivyteam.");
  }

  private static boolean isCollection(Class<?> type) {
    return java.util.Collection.class.isAssignableFrom(type) || Iterable.class == type;
  }

  private String typeName(Class<?> type) {
    String base = "t" + type.getSimpleName();
    String name = base;
    for (int index = 2; structureNames.containsKey(name) && structureNames.get(name) != type; index++) {
      name = base + index;
    }
    structureNames.put(name, type);
    return name;
  }
}
