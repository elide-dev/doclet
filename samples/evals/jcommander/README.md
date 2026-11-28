# JCommander 1.78 holdout

This final replication fixture contains eight questions derived from public source comments before any retrieval run. It covers usage formatting, option parsing, and converters. The frozen fixture SHA-256 is `bdcaf5acc943f3137a8e92726b479f34c5f8bfb0a0bacc481d06c72be1635670`.

## Source draw

The sampling frame consists of public methods and constructors preceded by Javadoc in the four documented packages. The draw excludes internal packages, declarations the lightweight source matcher cannot parse, and comments with fewer than ten whitespace-delimited words after removing inline tags. Sort by SHA-256 of the seed `2026-11-27-jcommander`, source path, and whitespace-normalized declaration joined by colons. Take the first eight distinct declaring types, counting a nested type separately. Skipping repeated owners increases coverage without consulting the reader or the generated site.

| Draw rank | Declaring type | Member |
|---:|---|---|
| 1 | JCommander.Builder | `expandAtSign(Boolean)` |
| 2 | JCommander | `getMainParameterValue()` |
| 5 | ParameterDescription | `isAssigned()` |
| 7 | DefaultUsageFormatter | `wrapDescription(StringBuilder, int, String)` |
| 11 | DefaultListConverter | Constructor with splitter and converter |
| 13 | EnumConverter | Constructor with option name and enum type |
| 23 | UnixStyleUsageFormatter | `appendAllParametersDetails(StringBuilder, int, String, List)` |
| 33 | Parameterized | `findFieldGenericType()` |

All intervening draws repeat an owner already selected. Run `python3 samples/evals/jcommander/draw.py /path/to/jcommander-1.78-sources.jar` to reconstruct the raw ordering from the archive.

## Build and provenance

Run `scripts/build-eval-jcommander` with JDK 25 and Elide. It reads the cached Maven artifact `com.beust:jcommander:1.78:sources` or the first argument, checks SHA-256 `9e2aee95666a586918d0931bd1bee02628167da907e6adab86489708bbf2b39a`, and builds each site from the unmodified package sources. Outputs go under `.dev/eval-jcommander/{javadoc,markdown}`. Both sites list the same 52 public types and 321 declaration keys. Every expected anchor and evidence pattern was verified in the exact published member section in both formats. [Maven source archive](https://repo.maven.apache.org/maven2/com/beust/jcommander/1.78/jcommander-1.78-sources.jar)

Archived excerpts come from JCommander 1.78, copyright 2010 Cedric Beust and the original contributors. [Apache License 2.0](LICENSE.txt) · [Upstream notice](NOTICE.md)
