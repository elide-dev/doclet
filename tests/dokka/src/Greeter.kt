package example

/** A greeting for [name]. */
public data class Greeter(val name: String) {
  /** Returns the greeting as plain text. */
  public fun greet(): String = "Hello, $name"
}
