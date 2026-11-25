package com.axonivy.utils.rule.engine.dmn.generator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns field names and field paths into names that are safe in FEEL expressions.
 * <p>The same rules are used when a model is generated and when a decision is evaluated,
 * so inputs and results are mapped by name without any configuration.</p>
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

  /**
   * @return the field name a FEEL name was derived from, e.g. <code>function</code> for <code>function_</code>
   */
  public static String toFieldName(String feelName) {
    if (feelName != null && feelName.endsWith(RESERVED_SUFFIX)) {
      var stripped = feelName.substring(0, feelName.length() - RESERVED_SUFFIX.length());
      if (isReserved(stripped)) {
        return stripped;
      }
    }
    return feelName;
  }

  /**
   * Names for a list of field paths like <code>in.member.type</code>.
   * <p>The name is the last segment of the path. If two paths end with the same segment, parent segments are
   * added until the names differ: <code>in.member.type</code> and <code>in.order.type</code> become
   * <code>memberType</code> and <code>orderType</code>.</p>
   *
   * @param paths field paths, duplicates are ignored
   * @return the unique FEEL name of every path, in the order of the paths
   */
  public static Map<String, String> uniqueNames(List<String> paths) {
    var distinct = new ArrayList<String>();
    for (String path : paths) {
      if (path != null && !path.isBlank() && !distinct.contains(path.trim())) {
        distinct.add(path.trim());
      }
    }
    // how many trailing segments of each path form its name
    var depths = new LinkedHashMap<String, Integer>();
    distinct.forEach(path -> depths.put(path, 1));
    boolean changed = true;
    while (changed) {
      changed = false;
      var counts = new LinkedHashMap<String, Integer>();
      for (String path : distinct) {
        counts.merge(toFeelName(lastSegments(path, depths.get(path))), 1, Integer::sum);
      }
      for (String path : distinct) {
        int depth = depths.get(path);
        boolean collides = counts.get(toFeelName(lastSegments(path, depth))) > 1;
        if (collides && depth < segments(path).length) {
          depths.put(path, depth + 1);
          changed = true;
        }
      }
    }
    // paths that still share a name cannot be told apart by their segments: number them
    var names = new LinkedHashMap<String, String>();
    var taken = new HashSet<String>();
    for (String path : distinct) {
      String name = toFeelName(lastSegments(path, depths.get(path)));
      String unique = name;
      for (int index = 2; taken.contains(unique); index++) {
        unique = name + index;
      }
      taken.add(unique);
      names.put(path, unique);
    }
    return names;
  }

  /**
   * @return the last segments of a path joined in camel case, e.g. <code>memberType</code> for
   *         <code>in.member.type</code> and depth 2. The root variable (<code>in</code>) is never part of a name.
   */
  private static String lastSegments(String path, int depth) {
    String[] segments = segments(path);
    int from = Math.max(0, segments.length - depth);
    var name = new StringBuilder(segments[from]);
    for (int i = from + 1; i < segments.length; i++) {
      String segment = segments[i];
      name.append(segment.substring(0, 1).toUpperCase(Locale.ROOT)).append(segment.substring(1));
    }
    return name.toString();
  }

  /**
   * @return the segments of a path without its root variable: <code>[member, type]</code> for
   *         <code>in.member.type</code>
   */
  public static String[] segments(String path) {
    String[] all = path.trim().split("\\.");
    if (all.length > 1 && isRoot(all[0])) {
      String[] withoutRoot = new String[all.length - 1];
      System.arraycopy(all, 1, withoutRoot, 0, withoutRoot.length);
      return withoutRoot;
    }
    return all;
  }

  private static boolean isRoot(String segment) {
    return "in".equals(segment) || "out".equals(segment);
  }
}
