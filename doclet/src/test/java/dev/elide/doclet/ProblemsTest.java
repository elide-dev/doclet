package dev.elide.doclet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProblemsTest {
  /** One problem each in the inline-tag and reference categories. */
  private static final Map<String, String> SOURCES =
      Map.of(
          "shelf/Book.java",
          """
          package shelf;

          /** A book with {@custom tag} that links to {@link Missing}. */
          public interface Book {}
          """);

  @Test
  void problemsAreErrorsByDefault(@TempDir Path directory) throws Exception {
    var result = Javadoc.run(directory, SOURCES, List.of());
    assertFalse(result.ok());
    assertEquals(2, result.errors().size(), result::toString);
    assertTrue(result.errors().stream().anyMatch(e -> e.endsWith("[inline-tag]")));
    assertTrue(result.errors().stream().anyMatch(e -> e.endsWith("[reference]")));
  }

  @Test
  void warningDowngradesEveryProblem(@TempDir Path directory) throws Exception {
    var result = Javadoc.run(directory, SOURCES, List.of("--problems", "warning"));
    assertTrue(result.ok(), result::toString);
    assertTrue(result.errors().isEmpty(), result::toString);
    assertEquals(2, result.warnings().size(), result::toString);
    assertTrue(result.model().contains("A book with"));
  }

  @Test
  void equalsFormIsAccepted(@TempDir Path directory) throws Exception {
    var result = Javadoc.run(directory, SOURCES, List.of("--problems=warning"));
    assertTrue(result.ok(), result::toString);
  }

  @Test
  void categoriesAreIndependent(@TempDir Path directory) throws Exception {
    var result = Javadoc.run(directory, SOURCES, List.of("--problems", "error,inline-tag=warning"));
    assertFalse(result.ok());
    assertEquals(1, result.errors().size(), result::toString);
    assertTrue(result.errors().get(0).endsWith("[reference]"), result::toString);
    assertTrue(result.warnings().stream().anyMatch(w -> w.endsWith("[inline-tag]")));
  }

  @Test
  void categoryCanStayAnErrorUnderAWarningDefault(@TempDir Path directory) throws Exception {
    var result =
        Javadoc.run(
            directory, SOURCES, List.of("--problems", "warning", "--problems", "reference=error"));
    assertFalse(result.ok());
    assertEquals(1, result.errors().size(), result::toString);
  }

  @Test
  void malformedBlockTagIsAProblem(@TempDir Path directory) throws Exception {
    var sources =
        Map.of(
            "shelf/Shelf.java",
            """
            package shelf;

            /**
             * A shelf.
             *
             * @throws
             */
            public interface Shelf {}
            """);
    var failed = Javadoc.run(directory.resolve("error"), sources, List.of());
    assertFalse(failed.ok());
    assertEquals(1, failed.errors().size(), failed::toString);
    assertTrue(failed.errors().get(0).endsWith("[malformed]"), failed::toString);

    var lenient =
        Javadoc.run(
            directory.resolve("warning"), sources, List.of("--problems", "malformed=warning"));
    assertTrue(lenient.ok(), lenient::toString);
    assertTrue(lenient.model().contains("A shelf."), lenient::toString);
  }

  @Test
  void unknownCategoryIsRejected(@TempDir Path directory) throws Exception {
    var result = Javadoc.run(directory, SOURCES, List.of("--problems", "bogus=warning"));
    assertFalse(result.ok());
  }
}
