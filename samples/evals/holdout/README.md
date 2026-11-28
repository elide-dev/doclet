# Gson 2.8.9 holdout

The 15 questions use a search corpus of 26 public types in the Java package `com.google.gson`. Each question has one expected page, an answer pattern, and an evidence pattern. The initial fixture was written against the original source comments before running either retriever. Its SHA-256 is `3a3050c98ee090e571ddd0e07242a1cdc380583c80c970a506ddd53138c41b9f`.

Run `scripts/build-eval-holdout` from the checkout with JDK 25 and Elide installed. It reads the locally cached `com.google.code.gson:gson:2.8.9:sources` archive, verifies its SHA-256, and invokes both doclets on the same package. An optional argument selects a different local path to that archive. Build products live under `.dev/eval-holdout/javadoc` and `.dev/eval-holdout/markdown`. [Upstream source archive](https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.8.9/gson-2.8.9-sources.jar)

Keep the original fixture for paired comparisons. Record any revised questions or grading patterns as a separately versioned experiment.

## Excerpt attribution

The documentation excerpts in the archived Gson evaluation results come from comments in Google Gson 2.8.9, copyright 2008–2011 and 2021 Google Inc. The two generators convert the comments to Javadoc HTML or Markdown; the local evaluation selects and truncates passages. Google distributes the original material under the Apache License, Version 2.0. [Included license](Gson-LICENSE.txt) · [Upstream release and source](https://github.com/google/gson/tree/gson-parent-2.8.9/gson) · [Archived evaluation](../../../docs/climb/runs/007-gson.json)
