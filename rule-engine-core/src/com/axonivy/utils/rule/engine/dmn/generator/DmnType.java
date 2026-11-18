package com.axonivy.utils.rule.engine.dmn.generator;

import java.util.List;

/**
 * The type of a field in terms of DMN.
 */
public sealed interface DmnType {

  DmnType STRING = new Simple("string");
  DmnType NUMBER = new Simple("number");
  DmnType BOOLEAN = new Simple("boolean");
  DmnType DATE = new Simple("date");
  DmnType TIME = new Simple("time");
  DmnType DATE_TIME = new Simple("date and time");
  DmnType DAYS_TIME_DURATION = new Simple("days and time duration");
  DmnType YEARS_MONTHS_DURATION = new Simple("years and months duration");
  DmnType ANY = new Simple("Any");

  /** A built-in FEEL type like <code>string</code> or <code>date and time</code>. */
  record Simple(String feelName) implements DmnType {}

  /** A string that is limited to the constants of an enum. */
  record Enumeration(String name, List<String> values) implements DmnType {
    public Enumeration {
      values = List.copyOf(values);
    }
  }

  /** A nested data class. */
  record Structure(String name, List<DmnField> fields) implements DmnType {
    public Structure {
      fields = List.copyOf(fields);
    }
  }

  /** A list of values. */
  record Collection(DmnType element) implements DmnType {}
}
