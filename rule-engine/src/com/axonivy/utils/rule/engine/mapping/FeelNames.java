package com.axonivy.utils.rule.engine.mapping;

import java.util.Set;

/**
 * Turns field names into names that are safe in FEEL expressions, and back.
 * <p>A field is found by the name of a DMN input or result, so both sides follow the same rules.</p>
 */
public final class FeelNames {

  /** Words of the FEEL grammar. A variable with one of these names cannot be referenced in an expression. */
  private static final Set<String> KEYWORDS = Set.of(
      "and", "between", "else", "every", "external", "false", "for", "function", "if", "in", "instance",
      "not", "null", "of", "or", "return", "satisfies", "some", "then", "true");

  /** Suffix that makes a reserved word a usable name, e.g. <code>function</code> becomes <code>function_</code>. */
  public static final String RESERVED_SUFFIX = "_";

  private FeelNames() {}

  public static boolean isReserved(String name) {
    return name != null && KEYWORDS.contains(name);
  }

  /**
   * @return a valid FEEL name for the field name: reserved words get a suffix, illegal characters are replaced
   */
  public static String toFeelName(String fieldName) {
    if (fieldName == null || fieldName.isBlank()) {
      return "_";
    }
    var name = new StringBuilder();
    for (int i = 0; i < fieldName.length(); i++) {
      char c = fieldName.charAt(i);
      boolean valid = i == 0 ? Character.isLetter(c) || c == '_' : Character.isLetterOrDigit(c) || c == '_';
      if (valid) {
        name.append(c);
      } else if (i == 0 && Character.isDigit(c)) {
        name.append('_').append(c);
      } else {
        name.append('_');
      }
    }
    var result = name.toString();
    return isReserved(result) ? result + RESERVED_SUFFIX : result;
  }
}
