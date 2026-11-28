# Recovering from empty literal searches

During Codec run 038, Luna searched the full reference for `H and W vowels`. The source says “H and W the same as vowels”. The literal search returned an empty view. In an offline replay, a term search over the same file surfaced both the desired Soundex constant and a constant that treats the letters differently.

For each archived query that had zero literal matches anywhere in the opened document, the diagnostic ranks that document's browser rows using only the query Luna issued. It measures the repository's IDF-weighted lexical ranker and its BM25 contract ranker. Each gets up to three matching rows, with ties in document order. Only after retrieval does the frozen grader check the target type, canonical member fragment and evidence. The evidence check can combine excerpts from the same declaration. [Rankers](../../../scripts/eval_rank.py) · [Diagnostic](search-recovery.py)

| Archive | Queries with zero literal matches | Ranker | Target in first three | Supporting evidence in first three |
| --- | ---: | --- | ---: | ---: |
| 038 | 8 | Lexical | 1 | 1 |
| 038 | 8 | BM25 | 1 | 0 |
| 040 | 5 | Lexical | 0 | 0 |
| 040 | 5 | BM25 | 0 | 0 |

The one supported result came from the 797,347-byte `llms-full.txt` download. The lexical ranker returned the genealogy constant first and the correct simplified constant second. The counts are upper bounds on recoverable query turns within those three rows. A browser window could truncate longer results, and Luna would still have to select and cite the correct behavior. All reported grades and model usage come from the original sessions. This diagnostic makes zero model calls.

The package arm in run 040 opened the correct language package on the same question. It searched for `DoubleMetaphone`, opened that class, and searched there for `H W vowels`. Applying either fallback inside that class failed. An alternative literal search for the single word `vowels` in the package file shows exactly two rows. The simplified constant treats H and W as separators when dropping duplicate codes. The genealogy constant explicitly says they do not act as separators. This example uses a word present in the user's question and was selected after inspecting the trace. The module entry and package file together cost **56,328 cold bytes**, of which **28,723 bytes** are the package file. The observed package-arm session also fetched the 6,662-byte class page and abstained.

## Reproduce

Build the archived snapshots from the [discovery reproduction guide](../discovery.md). Run the diagnostic from the repository root.

```sh
python3 docs/climb/experiments/search-recovery.py docs/climb/runs/038-codec-indexed.json \
  --questions samples/evals/codec/questions.json --site javadoc=.dev/eval-codec/javadoc \
  --site fixed=.dev/discovery-fixed/codec --site indexed=.dev/discovery-indexed/codec
python3 docs/climb/experiments/search-recovery.py docs/climb/runs/040-codec-structural.json \
  --questions samples/evals/codec/questions.json --site indexed=.dev/discovery-indexed/codec \
  --site direct=.dev/discovery-lean-direct/codec --site packages=.dev/discovery-lean-packages/codec
rg -i 'vowels' .dev/discovery-lean-packages/codec/org/apache/commons/codec/language/-members.md
```
