/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.model;

import java.util.List;

/**
 * A declaration's documentation as a small tree, not an HTML string: Javadoc's HTML subset and
 * KDoc's Markdown both map to it. Tags that describe parts of a declaration (parameters, return
 * value, exceptions) live on those parts in {@link Api}; this record holds the rest.
 *
 * @param summary the first sentence (or {@code {@summary}}), as inline content
 * @param body the full description, first sentence included
 * @param notes {@code @apiNote}, {@code @implSpec} and {@code @implNote} sections, in source order
 * @param see {@code @see} entries, each as inline content (usually one link)
 * @param inheritedFrom the member whose documentation this is, when it was inherited rather than
 *     written here
 */
public record Doc(
    List<Inline> summary,
    List<Block> body,
    List<Note> notes,
    List<List<Inline>> see,
    Api.Ref inheritedFrom) {

  /** What a note section is. */
  public enum NoteKind {
    /** {@code @apiNote}: commentary, rationale or examples about the API. */
    API_NOTE,
    /** {@code @implSpec}: what every implementation must do. */
    IMPL_SPEC,
    /** {@code @implNote}: how this implementation behaves. */
    IMPL_NOTE,
  }

  /**
   * A note section.
   *
   * @param kind which tag it came from
   * @param body its content
   */
  public record Note(NoteKind kind, List<Block> body) {}

  /** Block content. Each node writes its {@code kind} first. */
  public sealed interface Block extends Json.Tagged
      permits Paragraph, ListBlock, CodeBlock, Table, Heading, Quote, RawBlock {}

  /** Inline content. Each node writes its {@code kind} first. */
  public sealed interface Inline extends Json.Tagged
      permits Text, Code, Emphasis, Strong, Link, Break, RawInline {}

  /**
   * A paragraph.
   *
   * @param children its content
   */
  public record Paragraph(List<Inline> children) implements Block {
    @Override
    public String tag() {
      return "paragraph";
    }
  }

  /**
   * A list.
   *
   * @param ordered whether items are numbered
   * @param items each item's blocks
   */
  public record ListBlock(boolean ordered, List<List<Block>> items) implements Block {
    @Override
    public String tag() {
      return "list";
    }
  }

  /**
   * A code block, highlighted by the renderer.
   *
   * @param language the language name ({@code java}, {@code kotlin}, ...), or {@code null} for the
   *     declaration's
   * @param text the code, without a trailing newline
   */
  public record CodeBlock(String language, String text) implements Block {
    @Override
    public String tag() {
      return "code";
    }
  }

  /**
   * A table.
   *
   * @param caption the caption, or empty
   * @param head header rows, each a list of cells
   * @param rows body rows, each a list of cells
   */
  public record Table(
      List<Inline> caption, List<List<List<Block>>> head, List<List<List<Block>>> rows)
      implements Block {
    @Override
    public String tag() {
      return "table";
    }
  }

  /**
   * A heading inside documentation.
   *
   * @param level 1 to 6, as written; the renderer shifts it under the page's own headings
   * @param children its content
   */
  public record Heading(int level, List<Inline> children) implements Block {
    @Override
    public String tag() {
      return "heading";
    }
  }

  /**
   * A block quotation.
   *
   * @param children its blocks
   */
  public record Quote(List<Block> children) implements Block {
    @Override
    public String tag() {
      return "quote";
    }
  }

  /**
   * Markup that has no node of its own: the HTML page shows {@code html}, the Markdown page shows
   * {@code text}.
   *
   * @param html the markup as written
   * @param text its text content
   */
  public record RawBlock(String html, String text) implements Block {
    @Override
    public String tag() {
      return "raw";
    }
  }

  /**
   * Plain text.
   *
   * @param text the text, entities decoded
   */
  public record Text(String text) implements Inline {
    @Override
    public String tag() {
      return "text";
    }
  }

  /**
   * Code ({@code {@code}}, {@code <code>}, {@code {@literal}} is {@link Text}).
   *
   * @param text the code
   */
  public record Code(String text) implements Inline {
    @Override
    public String tag() {
      return "code";
    }
  }

  /**
   * Emphasis ({@code <em>}, {@code <i>}).
   *
   * @param children its content
   */
  public record Emphasis(List<Inline> children) implements Inline {
    @Override
    public String tag() {
      return "emphasis";
    }
  }

  /**
   * Strong importance ({@code <strong>}, {@code <b>}).
   *
   * @param children its content
   */
  public record Strong(List<Inline> children) implements Inline {
    @Override
    public String tag() {
      return "strong";
    }
  }

  /**
   * A link to a declaration or a URL. A declaration link that resolves neither in the model nor
   * through an external renders as its text.
   *
   * @param target the declaration, or {@code null} for a URL link
   * @param url the URL of a hyperlink ({@code <a href>}, {@code @see "<a href>"}), or {@code null}
   * @param code whether the label is code ({@code {@link}}) rather than prose ({@code
   *     {@linkplain}})
   * @param children the label
   */
  public record Link(Api.Ref target, String url, boolean code, List<Inline> children)
      implements Inline {
    @Override
    public String tag() {
      return "link";
    }
  }

  /** A line break ({@code <br>}). */
  public record Break() implements Inline {
    @Override
    public String tag() {
      return "break";
    }
  }

  /**
   * Inline markup that has no node of its own: the HTML page shows {@code html}, the Markdown page
   * shows {@code text}.
   *
   * @param html the markup as written
   * @param text its text content
   */
  public record RawInline(String html, String text) implements Inline {
    @Override
    public String tag() {
      return "raw";
    }
  }
}
