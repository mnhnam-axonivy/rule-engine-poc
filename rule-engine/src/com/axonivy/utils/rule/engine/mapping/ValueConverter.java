package com.axonivy.utils.rule.engine.mapping;

import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.axonivy.utils.rule.engine.dmn.generator.BeanProperties;
import com.axonivy.utils.rule.engine.dmn.generator.FeelNames;

import ch.ivyteam.ivy.scripting.objects.Date;
import ch.ivyteam.ivy.scripting.objects.DateTime;
import ch.ivyteam.ivy.scripting.objects.Duration;
import ch.ivyteam.ivy.scripting.objects.Time;

public final class ValueConverter {

  private static final int MAX_DEPTH = 10;

  private ValueConverter() {}

  public static Object toJson(Object value) {
    return toJson(value, new IdentityHashMap<>(), 0);
  }

  private static Object toJson(Object value, IdentityHashMap<Object, Boolean> path, int depth) {
    if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) {
      return value;
    }
    if (value instanceof Character || value instanceof Enum<?>) {
      return value instanceof Enum<?> constant ? constant.name() : value.toString();
    }
    if (value instanceof Date date) {
      return LocalDate.of(date.getYear(), date.getMonth(), date.getDay()).toString();
    }
    if (value instanceof DateTime dateTime) {
      return LocalDateTime.of(dateTime.getYear(), dateTime.getMonth(), dateTime.getDay(),
          dateTime.getHours(), dateTime.getMinutes(), dateTime.getSeconds()).toString() + secondsIfMissing(dateTime);
    }
    if (value instanceof Time time) {
      return String.format("%02d:%02d:%02d", time.getHours(), time.getMinutes(), time.getSeconds());
    }
    if (value instanceof Duration duration) {
      return java.time.Duration.ofSeconds(duration.toNumber()).toString();
    }
    if (value instanceof java.sql.Date date) {
      return date.toLocalDate().toString();
    }
    if (value instanceof java.sql.Time time) {
      return time.toLocalTime().truncatedTo(ChronoUnit.SECONDS).toString();
    }
    if (value instanceof java.util.Date date) {
      return isoSeconds(LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()));
    }
    if (value instanceof java.util.Calendar calendar) {
      return isoSeconds(LocalDateTime.ofInstant(calendar.toInstant(), calendar.getTimeZone().toZoneId()));
    }
    if (value instanceof java.time.temporal.Temporal || value instanceof java.time.temporal.TemporalAmount) {
      return value.toString();
    }
    if (depth >= MAX_DEPTH || path.containsKey(value)) {
      return null; // a data structure that refers to itself ends here
    }
    path.put(value, Boolean.TRUE);
    try {
      if (value instanceof Map<?, ?> map) {
        var json = new LinkedHashMap<String, Object>();
        map.forEach((key, entry) -> json.put(String.valueOf(key), toJson(entry, path, depth + 1)));
        return json;
      }
      if (value instanceof Collection<?> collection) {
        var json = new ArrayList<>();
        collection.forEach(element -> json.add(toJson(element, path, depth + 1)));
        return json;
      }
      if (value.getClass().isArray()) {
        if (value instanceof byte[]) {
          return null;
        }
        var json = new ArrayList<>();
        for (int i = 0; i < Array.getLength(value); i++) {
          json.add(toJson(Array.get(value, i), path, depth + 1));
        }
        return json;
      }
      var properties = BeanProperties.of(value.getClass());
      if (properties.isEmpty()) {
        return value.toString();
      }
      var json = new LinkedHashMap<String, Object>();
      for (var property : properties) {
        json.put(FeelNames.toFeelName(property.name()), toJson(property.read(value), path, depth + 1));
      }
      return json;
    } finally {
      path.remove(value);
    }
  }

  /** LocalDateTime omits ":00" seconds, FEEL needs them. */
  private static String secondsIfMissing(DateTime dateTime) {
    return dateTime.getSeconds() == 0 ? ":00" : "";
  }

  private static String isoSeconds(LocalDateTime dateTime) {
    var truncated = dateTime.truncatedTo(ChronoUnit.SECONDS);
    return truncated.toString() + (truncated.getSecond() == 0 ? ":00" : "");
  }

  public static Object fromJson(Object json, Type target) {
    Class<?> type = rawType(target);
    if (json == null) {
      return null;
    }
    if (type == Object.class) {
      return json;
    }
    try {
      if (type == String.class || type == CharSequence.class) {
        return json instanceof String || json instanceof Number || json instanceof Boolean ? json.toString()
            : String.valueOf(json);
      }
      if (type == Boolean.class || type == boolean.class) {
        return json instanceof Boolean bool ? bool : Boolean.valueOf(json.toString());
      }
      if (Number.class.isAssignableFrom(type) || (type.isPrimitive() && type != char.class)) {
        return toNumber(json, type);
      }
      if (type.isEnum()) {
        return enumConstant(type, json.toString());
      }
      if (type == Date.class) {
        var date = toLocalDate(json.toString());
        return new Date(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
      }
      if (type == DateTime.class) {
        var dateTime = toLocalDateTime(json.toString());
        return new DateTime(dateTime.getYear(), dateTime.getMonthValue(), dateTime.getDayOfMonth(),
            dateTime.getHour(), dateTime.getMinute(), dateTime.getSecond());
      }
      if (type == Time.class) {
        var time = toLocalTime(json.toString());
        return new Time(time.getHour(), time.getMinute(), time.getSecond());
      }
      if (type == Duration.class) {
        return new Duration(java.time.Duration.parse(json.toString()).getSeconds());
      }
      if (type == LocalDate.class) {
        return toLocalDate(json.toString());
      }
      if (type == LocalDateTime.class) {
        return toLocalDateTime(json.toString());
      }
      if (type == LocalTime.class) {
        return toLocalTime(json.toString());
      }
      if (type == OffsetDateTime.class) {
        return toLocalDateTime(json.toString()).atZone(ZoneId.systemDefault()).toOffsetDateTime();
      }
      if (type == ZonedDateTime.class) {
        return toLocalDateTime(json.toString()).atZone(ZoneId.systemDefault());
      }
      if (type == Instant.class) {
        return toLocalDateTime(json.toString()).atZone(ZoneId.systemDefault()).toInstant();
      }
      if (type == java.time.Duration.class) {
        return java.time.Duration.parse(json.toString());
      }
      if (type == java.time.Period.class) {
        return java.time.Period.parse(json.toString());
      }
      if (type == java.util.Date.class) {
        return java.util.Date.from(toLocalDateTime(json.toString()).atZone(ZoneId.systemDefault()).toInstant());
      }
      if (Collection.class.isAssignableFrom(type)) {
        return toCollection(json, type, elementType(target));
      }
      if (type.isArray()) {
        var elements = (List<?>) toCollection(json, List.class, type.getComponentType());
        Object array = Array.newInstance(type.getComponentType(), elements.size());
        for (int i = 0; i < elements.size(); i++) {
          Array.set(array, i, elements.get(i));
        }
        return array;
      }
      if (Map.class.isAssignableFrom(type)) {
        return json;
      }
      if (json instanceof Map<?, ?> map) {
        return toBean(map, type);
      }
    } catch (DateTimeParseException | ClassCastException | ArithmeticException ex) {
      throw mismatch(json, type, ex);
    }
    throw mismatch(json, type, null);
  }

  private static Object toNumber(Object json, Class<?> type) {
    BigDecimal number;
    if (json instanceof BigDecimal decimal) {
      number = decimal;
    } else if (json instanceof Number other) {
      number = new BigDecimal(other.toString());
    } else {
      try {
        number = new BigDecimal(json.toString().trim());
      } catch (NumberFormatException ex) {
        throw mismatch(json, type, ex);
      }
    }
    if (type == Integer.class || type == int.class) {
      return number.intValueExact();
    }
    if (type == Long.class || type == long.class) {
      return number.longValueExact();
    }
    if (type == Double.class || type == double.class) {
      return number.doubleValue();
    }
    if (type == Float.class || type == float.class) {
      return number.floatValue();
    }
    if (type == Short.class || type == short.class) {
      return number.shortValueExact();
    }
    if (type == Byte.class || type == byte.class) {
      return number.byteValueExact();
    }
    if (type == BigInteger.class) {
      return number.toBigIntegerExact();
    }
    if (type == BigDecimal.class) {
      return number;
    }
    // java.lang.Number, the type of an Ivy 'Number' field: whole numbers stay whole
    if (number.stripTrailingZeros().scale() <= 0) {
      try {
        return number.intValueExact();
      } catch (ArithmeticException tooLarge) {
        try {
          return number.longValueExact();
        } catch (ArithmeticException evenLarger) {
          return number;
        }
      }
    }
    return number.doubleValue();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object enumConstant(Class<?> type, String name) {
    try {
      return Enum.valueOf((Class<? extends Enum>) type, name);
    } catch (IllegalArgumentException ex) {
      throw mismatch(name, type, ex);
    }
  }

  private static Object toCollection(Object json, Class<?> type, Type elementType) {
    Collection<Object> target = Set.class.isAssignableFrom(type)
        ? new LinkedHashSet<>()
        : ch.ivyteam.ivy.scripting.objects.List.create();
    Collection<?> source = json instanceof Collection<?> collection ? collection : List.of(json);
    for (Object element : source) {
      target.add(fromJson(element, elementType));
    }
    return target;
  }

  private static Object toBean(Map<?, ?> json, Class<?> type) {
    Object bean;
    try {
      bean = type.getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException ex) {
      throw mismatch(json, type, ex);
    }
    for (var property : BeanProperties.of(type)) {
      if (!property.isWritable()) {
        continue;
      }
      String key = FeelNames.toFeelName(property.name());
      if (json.containsKey(key)) {
        property.write(bean, fromJson(json.get(key), property.type()));
      } else if (json.containsKey(property.name())) {
        property.write(bean, fromJson(json.get(property.name()), property.type()));
      }
    }
    return bean;
  }

  private static LocalDate toLocalDate(String text) {
    return text.length() > 10 ? toLocalDateTime(text).toLocalDate() : LocalDate.parse(text);
  }

  private static LocalDateTime toLocalDateTime(String text) {
    if (text.length() <= 10) {
      return LocalDate.parse(text).atStartOfDay();
    }
    try {
      return LocalDateTime.parse(text);
    } catch (DateTimeParseException noLocalDateTime) {
      try {
        return OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
      } catch (DateTimeParseException noOffset) {
        return ZonedDateTime.parse(text).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
      }
    }
  }

  private static LocalTime toLocalTime(String text) {
    try {
      return LocalTime.parse(text);
    } catch (DateTimeParseException noLocalTime) {
      return java.time.OffsetTime.parse(text).toLocalTime();
    }
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

  private static Type elementType(Type type) {
    if (type instanceof ParameterizedType parameterized && parameterized.getActualTypeArguments().length == 1) {
      return parameterized.getActualTypeArguments()[0];
    }
    return Object.class;
  }

  private static IllegalArgumentException mismatch(Object json, Class<?> type, Exception cause) {
    return new IllegalArgumentException("The value '" + json + "' (" + json.getClass().getSimpleName()
        + ") cannot be converted to " + type.getSimpleName(), cause);
  }
}
