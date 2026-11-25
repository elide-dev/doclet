package example.runtime;

import java.lang.annotation.*;

/** Marks a runtime API whose contract may evolve before the next release. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@java.lang.annotation.Target({ElementType.TYPE, ElementType.METHOD})
public @interface Experimental {
  /** The version in which the experiment began. */
  String since() default "1.0";
}
