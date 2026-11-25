package example;

/** Demonstrates the published doclet. */
public interface Example {
  /**
   * Finds a book by title.
   *
   * @param title the title to find
   * @return the matching book
   */
  String find(String title);
}
