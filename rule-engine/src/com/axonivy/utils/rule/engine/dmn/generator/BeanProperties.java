package com.axonivy.utils.rule.engine.dmn.generator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class BeanProperties {

  public record Property(String name, Type type, Method getter, Method setter) {

    public Object read(Object bean) {
      try {
        return getter.invoke(bean);
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException("Cannot read '" + name + "' of " + bean.getClass().getName(), ex);
      }
    }

    public boolean isWritable() {
      return setter != null;
    }

    public void write(Object bean, Object value) {
      if (setter == null) {
        throw new IllegalStateException("'" + name + "' of " + bean.getClass().getName() + " is read-only");
      }
      try {
        setter.invoke(bean, value);
      } catch (ReflectiveOperationException | IllegalArgumentException ex) {
        throw new IllegalStateException("Cannot write '" + name + "' of " + bean.getClass().getName(), ex);
      }
    }
  }

  private BeanProperties() {}

  public static List<Property> of(Class<?> beanClass) {
    var properties = new LinkedHashMap<String, Property>();
    // declared fields first: they define the order a developer expects
    for (Class<?> type : hierarchy(beanClass)) {
      for (Field field : type.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
          continue;
        }
        getter(beanClass, field.getName())
            .ifPresent(getter -> properties.putIfAbsent(field.getName(), property(beanClass, field.getName(), getter)));
      }
    }
    for (Method method : beanClass.getMethods()) {
      propertyName(method).ifPresent(name -> properties.putIfAbsent(name, property(beanClass, name, method)));
    }
    return new ArrayList<>(properties.values());
  }

  public static Optional<Property> find(Class<?> beanClass, String name) {
    return of(beanClass).stream().filter(property -> property.name().equals(name)).findFirst();
  }

  public static Map<String, Property> byName(Class<?> beanClass) {
    var byName = new LinkedHashMap<String, Property>();
    of(beanClass).forEach(property -> byName.put(property.name(), property));
    return byName;
  }

  private static Property property(Class<?> beanClass, String name, Method getter) {
    return new Property(name, getter.getGenericReturnType(), getter, setter(beanClass, name, getter.getReturnType()));
  }

  private static List<Class<?>> hierarchy(Class<?> beanClass) {
    var hierarchy = new ArrayList<Class<?>>();
    for (Class<?> type = beanClass; type != null && !isFrameworkClass(type); type = type.getSuperclass()) {
      hierarchy.add(0, type);
    }
    return hierarchy;
  }

  private static boolean isFrameworkClass(Class<?> type) {
    String name = type.getName();
    return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jakarta.")
        || name.startsWith("ch.ivyteam.");
  }

  private static Optional<Method> getter(Class<?> beanClass, String field) {
    String suffix = capitalize(field);
    for (String prefix : List.of("get", "is")) {
      try {
        Method method = beanClass.getMethod(prefix + suffix);
        if (isGetter(method)) {
          return Optional.of(method);
        }
      } catch (NoSuchMethodException ex) {
      }
    }
    return Optional.empty();
  }

  private static Method setter(Class<?> beanClass, String field, Class<?> type) {
    try {
      Method method = beanClass.getMethod("set" + capitalize(field), type);
      return Modifier.isStatic(method.getModifiers()) ? null : method;
    } catch (NoSuchMethodException ex) {
      return null;
    }
  }

  private static Optional<String> propertyName(Method method) {
    if (!isGetter(method) || isFrameworkClass(method.getDeclaringClass())) {
      return Optional.empty();
    }
    String name = method.getName();
    String suffix = name.startsWith("get") ? name.substring(3) : name.substring(2);
    return Optional.of(suffix.substring(0, 1).toLowerCase(Locale.ROOT) + suffix.substring(1));
  }

  private static boolean isGetter(Method method) {
    if (Modifier.isStatic(method.getModifiers()) || !Modifier.isPublic(method.getModifiers())
        || method.getParameterCount() != 0 || method.getReturnType() == void.class) {
      return false;
    }
    String name = method.getName();
    if (name.startsWith("get") && name.length() > 3) {
      return true;
    }
    return name.startsWith("is") && name.length() > 2
        && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class);
  }

  private static String capitalize(String name) {
    return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
  }
}
