package dev.elide.doclet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownCommentsTest {
  private static final String SHELF =
      """
      package shelf;

      /// A shelf of [Book]s, documented in **Markdown**.
      ///
      /// ## Usage
      ///
      /// - add with [#add(Book)]
      /// - count with {@link #size() the size}
      ///
      /// ```java
      /// shelf.add(book);
      /// ```
      ///
      /// | Kind | Limit |
      /// |------|-------|
      /// | `small` | 10 |
      ///
      /// See [the guide](https://example.com/guide).
      public interface Shelf {
        /// Adds a book.
        ///
        /// @param book the *book* to add
        /// @return whether the shelf [changed][Shelf]
        boolean add(Book book);

        /// {@return the number of books}
        int size();
      }
      """;

  private static final String BOOK =
      """
      package shelf;

      /** A book. */
      public interface Book {
        /// {@inheritDoc}
        @Override
        String toString();
      }
      """;

  @Test
  void rendersMarkdownComments(@TempDir Path directory) throws Exception {
    var result =
        Javadoc.run(
            directory, Map.of("shelf/Shelf.java", SHELF, "shelf/Book.java", BOOK), List.of());
    assertTrue(result.ok(), result::toString);
    assertTrue(result.errors().isEmpty(), result::toString);
    var model = result.model();
    assertTrue(model.contains("{\"kind\":\"strong\""), model);
    assertTrue(model.contains("{\"kind\":\"heading\",\"level\":2"), model);
    assertTrue(model.contains("\"kind\":\"list\""), model);
    assertTrue(model.contains("\"kind\":\"table\""), model);
    assertTrue(model.contains("\"language\":\"java\",\"text\":\"shelf.add(book);\""), model);
    assertTrue(model.contains("\"url\":\"https://example.com/guide\""), model);
    assertTrue(model.contains("\"id\":\"shelf.Shelf#add(shelf.Book)\""), model);
    assertTrue(
        model.contains(
            "{\"kind\":\"emphasis\",\"children\":[{\"kind\":\"text\",\"text\":\"book\"}]}"),
        model);
    assertFalse(model.contains("```"), model);
    assertFalse(model.contains("##"), model);
    var page = result.page("shelf/Shelf.md");
    assertTrue(page.contains("Returns the number of books."), page);
  }
}
