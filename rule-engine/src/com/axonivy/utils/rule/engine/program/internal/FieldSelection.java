package com.axonivy.utils.rule.engine.program.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import com.axonivy.utils.rule.engine.dmn.generator.FeelNames;

public record FieldSelection(Map<String, String> expressionsByName) {

  public static FieldSelection parse(String text) {
    var paths = new ArrayList<String>();
    if (text != null) {
      for (String entry : text.split("[,;\\r\\n]")) {
        String path = entry.trim();
        if (!path.isEmpty() && !paths.contains(path)) {
          paths.add(path);
        }
      }
    }
    var byName = new LinkedHashMap<String, String>();
    FeelNames.uniqueNames(paths).forEach((path, name) -> byName.putIfAbsent(name, path));
    return new FieldSelection(byName);
  }

  public boolean isEmpty() {
    return expressionsByName().isEmpty();
  }

  public int size() {
    return expressionsByName().size();
  }
}
