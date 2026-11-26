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

  /**
   * Selects books for a catalog.
   *
   * @param collectionIdentifier collection name
   * @param minimumPublicationYear earliest publication year
   * @param interestedReaders readers to notify
   * @return selected titles
   */
  java.util.List<String> selectBooksForCollection(
      String collectionIdentifier,
      int minimumPublicationYear,
      java.util.List<String> interestedReaders);
}
