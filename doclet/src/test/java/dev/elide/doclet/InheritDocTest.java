package dev.elide.doclet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InheritDocTest {
  @Test
  void inheritDocFromOutsideTheDocumentedSetIsEmpty(@TempDir Path directory) throws Exception {
    var result =
        Javadoc.run(
            directory,
            Map.of(
                "shelf/Book.java",
                """
                package shelf;

                /** A book on the shelf. */
                public final class Book implements Comparable<Book>, Iterable<String> {
                  /** {@inheritDoc} */
                  @Override
                  public boolean equals(Object other) {
                    return other == this;
                  }

                  /**
                   * {@inheritDoc}
                   *
                   * @return {@inheritDoc}
                   */
                  @Override
                  public int hashCode() {
                    return 1;
                  }

                  /** {@inheritDoc} Includes the title. */
                  @Override
                  public String toString() {
                    return "Book";
                  }

                  /** @param other {@inheritDoc} */
                  @Override
                  public int compareTo(Book other) {
                    return 0;
                  }

                  /** {@inheritDoc} */
                  @Override
                  public java.util.Iterator<String> iterator() {
                    return java.util.List.<String>of().iterator();
                  }
                }
                """),
            List.of());
    assertTrue(result.ok(), result::toString);
    assertTrue(result.errors().isEmpty(), result::toString);
    assertTrue(result.model().contains("Includes the title."));
  }

  @Test
  void inheritDocInsideTheDocumentedSetStillInherits(@TempDir Path directory) throws Exception {
    var result =
        Javadoc.run(
            directory,
            Map.of(
                "shelf/Item.java",
                """
                package shelf;

                /** Something on the shelf. */
                public interface Item {
                  /** The item's catalogue title. */
                  String title();
                }
                """,
                "shelf/Book.java",
                """
                package shelf;

                /** A book. */
                public final class Book implements Item {
                  /** {@inheritDoc} */
                  @Override
                  public String title() {
                    return "";
                  }
                }
                """),
            List.of());
    assertTrue(result.ok(), result::toString);
    assertTrue(result.model().contains("The item's catalogue title."));
  }

  @Test
  void inheritDocUsesAnUndocumentedSupertypeOnTheSourcePath(@TempDir Path directory)
      throws Exception {
    var library = directory.resolve("library/other");
    java.nio.file.Files.createDirectories(library);
    java.nio.file.Files.writeString(
        library.resolve("Base.java"),
        """
        package other;

        /** A base class outside the documented set. */
        public class Base {
          /** The name from the base class. */
          public String name() {
            return "";
          }
        }
        """);
    var result =
        Javadoc.run(
            directory,
            Map.of(
                "shelf/Book.java",
                """
                package shelf;

                /** A book. */
                public final class Book extends other.Base {
                  /** {@inheritDoc} */
                  @Override
                  public String name() {
                    return "";
                  }
                }
                """),
            List.of("-sourcepath", directory.resolve("library").toString()));
    assertTrue(result.ok(), result::toString);
    assertTrue(result.model().contains("The name from the base class."));
  }

  @Test
  void inheritDocWithoutAnOverriddenMethodIsAnError(@TempDir Path directory) throws Exception {
    var result =
        Javadoc.run(
            directory,
            Map.of(
                "shelf/Book.java",
                """
                package shelf;

                /** A book. */
                public final class Book {
                  /** {@inheritDoc} */
                  public String title() {
                    return "";
                  }
                }
                """),
            List.of());
    assertFalse(result.ok());
    assertTrue(
        result.errors().stream().anyMatch(e -> e.contains("does not override or implement")),
        result::toString);
  }

  @Test
  void inheritDocFromAnUndocumentedPartInTheSetIsAnError(@TempDir Path directory) throws Exception {
    var result =
        Javadoc.run(
            directory,
            Map.of(
                "shelf/Item.java",
                """
                package shelf;

                /** Something on the shelf. */
                public interface Item {
                  /** The title. */
                  String title();
                }
                """,
                "shelf/Book.java",
                """
                package shelf;

                /** A book. */
                public final class Book implements Item {
                  /**
                   * The title.
                   *
                   * @return {@inheritDoc}
                   */
                  @Override
                  public String title() {
                    return "";
                  }
                }
                """),
            List.of());
    assertFalse(result.ok());
    assertTrue(
        result.errors().stream().anyMatch(e -> e.contains("finds nothing to inherit")),
        result::toString);
  }
}
