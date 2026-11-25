/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.model;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A deterministic JSON writer for the model's records, with no dependencies: the same model always
 * gives the same bytes.
 *
 * <p>Records write their components in declaration order, under the component's name or its {@link
 * Name}. A {@link Tagged} record writes {@code "kind": tag()} first. {@code null}, {@code false}
 * and empty lists are left out. Enum constants write in lower camel case ({@code ENUM_CONSTANT} is
 * {@code "enumConstant"}). Maps write their keys sorted. Strings escape quotes, backslashes,
 * control characters and U+2028/U+2029, and keep other characters as they are (the output is
 * UTF-8).
 */
public final class Json {
  private final StringBuilder out = new StringBuilder();
  private final boolean pretty;
  private int depth;

  private Json(boolean pretty) {
    this.pretty = pretty;
  }

  /** A record that writes a {@code kind} discriminator before its components. */
  public interface Tagged {
    /** The discriminator. */
    String tag();
  }

  /** The JSON key of a record component, where its Java name cannot be the key ({@code throws}). */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.RECORD_COMPONENT)
  public @interface Name {
    /** The key. */
    String value();
  }

  /**
   * {@code value} as JSON.
   *
   * @param value a record, list, map, string, number, boolean or enum constant
   * @param pretty whether to indent by two spaces and end with a newline
   * @return the JSON text
   */
  public static String write(Object value, boolean pretty) {
    var json = new Json(pretty);
    json.value(value);
    if (pretty) {
      json.out.append('\n');
    }
    return json.out.toString();
  }

  /** Whether {@code value} is written at all as a member. */
  private static boolean present(Object value) {
    return value != null
        && !Boolean.FALSE.equals(value)
        && !(value instanceof List<?> list && list.isEmpty())
        && !(value instanceof Map<?, ?> map && map.isEmpty());
  }

  private void value(Object value) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String s) {
      string(s);
    } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
      out.append(value);
    } else if (value instanceof Enum<?> e) {
      string(camel(e.name()));
    } else if (value instanceof List<?> list) {
      array(list);
    } else if (value instanceof Map<?, ?> map) {
      object(map);
    } else if (value instanceof Record r) {
      record(r);
    } else {
      throw new IllegalArgumentException("Not a model value: " + value.getClass().getName());
    }
  }

  private void array(List<?> list) {
    if (list.isEmpty()) {
      out.append("[]");
      return;
    }
    // Short scalar arrays and signature tokens stay on one line; they are read as a unit.
    boolean flat =
        !pretty
            || list.stream().allMatch(Json::scalar)
            || list.stream().allMatch(Api.Token.class::isInstance);
    out.append('[');
    depth++;
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        out.append(',');
        if (flat && pretty) {
          out.append(' ');
        }
      }
      if (!flat) {
        newline();
      }
      if (flat && list.get(i) instanceof Record r) {
        compact(r);
      } else {
        value(list.get(i));
      }
    }
    depth--;
    if (!flat) {
      newline();
    }
    out.append(']');
  }

  private static boolean scalar(Object value) {
    return value == null
        || value instanceof String
        || value instanceof Number
        || value instanceof Boolean
        || value instanceof Enum<?>;
  }

  /** A record on one line, even when pretty. */
  private void compact(Record r) {
    var json = new Json(false);
    json.record(r);
    out.append(json.out);
  }

  private void object(Map<?, ?> map) {
    var sorted = new TreeMap<String, Object>();
    map.forEach((k, v) -> sorted.put((String) k, v));
    List<Map.Entry<String, Object>> members = new ArrayList<>();
    for (var entry : sorted.entrySet()) {
      if (present(entry.getValue())) {
        members.add(entry);
      }
    }
    members(members);
  }

  private void record(Record r) {
    List<Map.Entry<String, Object>> members = new ArrayList<>();
    if (r instanceof Tagged tagged) {
      members.add(Map.entry("kind", tagged.tag()));
    }
    for (RecordComponent component : r.getClass().getRecordComponents()) {
      Object value;
      try {
        value = component.getAccessor().invoke(r);
      } catch (IllegalAccessException | InvocationTargetException e) {
        throw new IllegalStateException("Cannot read " + component, e);
      }
      if (present(value)) {
        var name = component.getAnnotation(Name.class);
        members.add(Map.entry(name == null ? component.getName() : name.value(), value));
      }
    }
    members(members);
  }

  private void members(List<Map.Entry<String, Object>> members) {
    if (members.isEmpty()) {
      out.append("{}");
      return;
    }
    out.append('{');
    depth++;
    for (int i = 0; i < members.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      newline();
      string(members.get(i).getKey());
      out.append(pretty ? ": " : ":");
      value(members.get(i).getValue());
    }
    depth--;
    newline();
    out.append('}');
  }

  private void newline() {
    if (pretty) {
      out.append('\n').append("  ".repeat(depth));
    }
  }

  private void string(String s) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> {
          if (c < 0x20 || c == ' ' || c == ' ') {
            out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  /** {@code ENUM_CONSTANT} as {@code enumConstant}. */
  static String camel(String constant) {
    var words = constant.toLowerCase(Locale.ROOT).split("_");
    var camel = new StringBuilder(words[0]);
    for (int i = 1; i < words.length; i++) {
      camel
          .append(Character.toUpperCase(words[i].charAt(0)))
          .append(words[i], 1, words[i].length());
    }
    return camel.toString();
  }
}
