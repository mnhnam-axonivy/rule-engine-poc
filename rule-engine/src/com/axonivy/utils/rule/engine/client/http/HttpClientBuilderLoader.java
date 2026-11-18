package com.axonivy.utils.rule.engine.client.http;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

public final class HttpClientBuilderLoader {

  /** System property that names the factory class to use, if several are available. */
  public static final String FACTORY_PROPERTY = "ruleengine.http.clientBuilderFactory";

  private HttpClientBuilderLoader() {}

  /**
   * Creates a {@link HttpClientBuilder} with one of the {@link HttpClientBuilderFactory factories} on the
   * classpath. The factory named by the system property {@value #FACTORY_PROPERTY} is used if it is set,
   * otherwise the factory with the highest {@link HttpClientBuilderFactory#priority() priority}.
   *
   * @return a new builder
   * @throws IllegalStateException if no factory is found or several share the highest priority
   */
  public static HttpClientBuilder loadHttpClientBuilder() {
    List<HttpClientBuilderFactory> factories = loadFactories();
    if (factories.isEmpty()) {
      throw new IllegalStateException("No HTTP client has been found in the classpath");
    }
    String selected = System.getProperty(FACTORY_PROPERTY);
    if (selected != null) {
      return factories.stream()
          .filter(factory -> selected.equals(factory.getClass().getName()))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("The system property '" + FACTORY_PROPERTY + "' names '"
              + selected + "', which is none of the available HTTP clients: " + names(factories)))
          .create();
    }
    int highest = factories.stream().mapToInt(HttpClientBuilderFactory::priority).max().orElseThrow();
    List<HttpClientBuilderFactory> preferred = factories.stream()
        .filter(factory -> factory.priority() == highest)
        .toList();
    if (preferred.size() > 1) {
      throw new IllegalStateException("Conflict: several HTTP clients share the highest priority: "
          + names(preferred) + ". Select one with the system property '" + FACTORY_PROPERTY + "'.");
    }
    return preferred.get(0).create();
  }

  private static List<HttpClientBuilderFactory> loadFactories() {
    var factories = new ArrayList<HttpClientBuilderFactory>();
    var names = new ArrayList<String>();
    var loaders = new ClassLoader[] {
        Thread.currentThread().getContextClassLoader(),
        HttpClientBuilderLoader.class.getClassLoader()};
    for (ClassLoader loader : loaders) {
      if (loader == null) {
        continue;
      }
      try {
        for (HttpClientBuilderFactory factory : ServiceLoader.load(HttpClientBuilderFactory.class, loader)) {
          if (!names.contains(factory.getClass().getName())) {
            names.add(factory.getClass().getName());
            factories.add(factory);
          }
        }
      } catch (java.util.ServiceConfigurationError ex) {
        // a class loader that sees another copy of this interface: its factories are not for us
      }
    }
    if (factories.isEmpty()) {
      // A call from another project, e.g. the Portal through Portal/RuleManagement, may run with class loaders
      // that do not show META-INF/services of this project. The REST client of Axon Ivy is always there.
      factories.add(new com.axonivy.utils.rule.engine.client.http.ivy.IvyHttpClientBuilderFactory());
    }
    return factories;
  }

  private static String names(List<HttpClientBuilderFactory> factories) {
    return factories.stream().map(factory -> factory.getClass().getName()).collect(Collectors.joining(", "));
  }
}
