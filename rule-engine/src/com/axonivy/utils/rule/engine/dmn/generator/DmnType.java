package com.axonivy.utils.rule.engine.dmn.generator;

import java.util.List;

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

  record Simple(String feelName) implements DmnType {}

  record Enumeration(String name, List<String> values) implements DmnType {
    public Enumeration {
      values = List.copyOf(values);
    }
  }

  record Structure(String name, List<DmnField> fields) implements DmnType {
    public Structure {
      fields = List.copyOf(fields);
    }
  }

  record Collection(DmnType element) implements DmnType {}
}
