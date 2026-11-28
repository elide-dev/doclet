# CommonMark Java 0.32.0 holdout

The 13 questions cover public parser, scanner, source-span, renderer, and builder contracts. The fixture was frozen from Java source comments before running either reader. Its SHA-256 is `6611630a2a869f990b2abc697d80dcf86ff234ff14bf45cb8fe7f96c3c417f42`. Each question has an expected page, one or more exact decoded HTML anchors, an accepted answer pattern, and a source-evidence pattern. `answer_all` can require additional terms in the same answer. All expected anchors are present and all evidence patterns match the expected full page in both outputs.

Use JDK 25 and run `scripts/build-eval-commonmark` from the repository. The script reads the cached Maven Central artifact `org.commonmark:commonmark:0.32.0:sources`, checks its SHA-256, and generates both sites from the same files. The optional first argument accepts a local path to that artifact. The sites appear in `.dev/eval-commonmark/javadoc` and `.dev/eval-commonmark/markdown`. Both doclets enumerate the same 96 types and 650 declarations across the 11 public packages listed in the source module descriptor. [Upstream source archive](https://repo.maven.apache.org/maven2/org/commonmark/commonmark/0.32.0/commonmark-0.32.0-sources.jar)

Archived evaluation excerpts contain documentation from the CommonMark Java source artifact, copyright Atlassian Pty Ltd and its contributors. The source archive includes the BSD 2-Clause license reproduced here. [Included license](LICENSE.txt)

Record changes to the questions or grading patterns as a separately versioned experiment.
