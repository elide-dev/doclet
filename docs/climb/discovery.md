# Reproduce the discovery treatment

The generated sites use the same saved API model, assets, renderer entry point and theme. The control uses the renderer at `d498661`. The qualified-symbol treatment at `90747f3` adds a searchable `symbol` to each declaration record and describes that lookup in the HTML head and `llms.txt`. The linked treatment at `f2646f5` also adds exact member fragment links to the API-at-a-glance summaries on type Markdown pages. The balanced treatment at `94019a2` restores the control’s link order in the HTML head. Its `llms.txt` points behavior searches to the alphabetical index and known-member lookups to the declaration contracts. The fixed treatment at `4a4581f` also resolves links in the combined Markdown document from its published root. The indexed treatment at `e5d527c` adds owner-qualified labels in the existing alphabetical Markdown file and promotes it in the HTML head. Each library’s original Javadoc directory supplies the JDK comparison.

From the repository root, build the libraries with JDK 25 and Elide. The fixture READMEs identify the required source archives and checksums. [Gson fixture](../../samples/evals/holdout/README.md) · [JCommander fixture](../../samples/evals/jcommander/README.md) · [CommonMark fixture](../../samples/evals/commonmark/README.md) · [Codec fixture](../../samples/evals/codec/README.md)

```sh
scripts/build-eval-holdout
scripts/build-eval-jcommander
scripts/build-eval-commonmark
scripts/build-eval-codec

renderer=doclet/src/main/resources/dev/elide/apidocs/renderer
mkdir -p .dev/discovery-render
git show "d498661:$renderer/render.js" > .dev/discovery-render/baseline.js
git show "90747f3:$renderer/render.js" > .dev/discovery-render/qualified.js
git show "f2646f5:$renderer/render.js" > .dev/discovery-render/linked.js
git show "94019a2:$renderer/render.js" > .dev/discovery-render/balanced.js
git show "4a4581f:$renderer/render.js" > .dev/discovery-render/fixed.js
git show "e5d527c:$renderer/render.js" > .dev/discovery-render/indexed.js

render_variants() {
  local name="$1" model="$2" arm bundle
  shift 2
  for arm in control treated linked balanced fixed indexed; do
    case "$arm" in
      control) bundle=.dev/discovery-render/baseline.js ;;
      treated) bundle=.dev/discovery-render/qualified.js ;;
      linked) bundle=.dev/discovery-render/linked.js ;;
      balanced) bundle=.dev/discovery-render/balanced.js ;;
      fixed|indexed) bundle=".dev/discovery-render/$arm.js" ;;
    esac
    node "$renderer/runner.mjs" --bundle "$bundle" --assets "$renderer/assets" \
      --model "$model" --out ".dev/discovery-$arm/$name" "$@"
  done
}

render_variants gson .dev/eval-holdout/markdown/api.json
render_variants jcommander .dev/eval-jcommander/markdown/api.json
render_variants commonmark .dev/eval-commonmark/markdown/api.json
render_variants codec .dev/eval-codec/markdown/api.json
```

These libraries use the default theme. To include the Runtime Kit development sample, run `scripts/build-samples`, then `render_variants runtime .dev/pages/signal/api.json --theme samples/themes/signal.json`. The control and qualified-symbol sites have identical Markdown twins and `llms-full.txt`. Between the qualified and linked sites, only type Markdown and its copy in `llms-full.txt` change. The linked and balanced sites have identical Markdown and declaration records. Every arm uses the same canonical member anchors, model, and assets.

## Run the navigation benchmark

Install the Python dependencies locally with uv and authenticate the Codex CLI with the account used for evaluation. Each site starts at its own `index.html`. The driver fixes the model to Luna low, keeps every case in a separate conversation, and rotates site order by case. Run one comparison at a time for the wall-clock measurements.

```sh
uv run --no-project --with tiktoken==0.12.0 python scripts/eval-browse.py \
  --questions samples/evals/commonmark/questions.json --cases 7 8 9 10 12 --offset 1 \
  --site javadoc=.dev/eval-commonmark/javadoc --site balanced=.dev/discovery-balanced/commonmark \
  --output .dev/commonmark-routing.json
python3 scripts/report-browse.py .dev/commonmark-routing.json --traces
```

For run 038, supply `samples/evals/codec/questions.json`, `--cases 1 3 5 8 10 --offset 0`, and sites in the order `javadoc=.dev/eval-codec/javadoc`, `fixed=.dev/discovery-fixed/codec`, `indexed=.dev/discovery-indexed/codec`.

For this campaign, `codex mcp list --json` returned an empty configured-server list. The harness also ignored user configuration, disabled the shell, apps, plugins, and web search, and inspected the emitted event types on every turn. Run 037 still encountered an MCP event. The original harness discarded the trial and resumed from its last completed case. Starting with `e645538`, an out-of-browser event stops the trial, scores a miss, charges its provider tokens and elapsed time, and stores only the event types. [OpenAI configuration reference](https://learn.chatgpt.com/docs/config-file/config-reference)

The archives contain the hashes of each input site, the questions, and the three reader source files. Reproduce the original reader with `90caaf1` for run 033, `cfdb62a` for runs 034–037, and `e5dced1` for run 038. To resume an interrupted comparison, pass `--resume` with the identical inputs and reader.

## Searchability of the Markdown index

Across the three Java libraries below, the `fixed` and `indexed` arms have identical structured records. We counted method links whose *visible label* begins with the declaring type (including nesting), a period, the exact method name and an opening parenthesis. Each check matched the decoded link destination against the method’s canonical contract href. The indexed labels also include the complete compact parameter list. A reader can disambiguate same-named parameter types from different packages by following the canonical link.

| Library | Methods with searchable labels, before → after | Alphabetical index bytes, before → after | Structured records, bytes |
| --- | ---: | ---: | ---: |
| Apache Commons Codec | 0 → 675 | 198,778 → 194,887 | 583,599 |
| CommonMark | 0 → 493 | 99,480 → 97,276 | 233,634 |
| JCommander | 0 → 187 | 49,918 → 48,558 | 117,888 |

For Codec, reading the entire new alphabetical index would take 51,792 o200k_base tokens versus 138,135 for the structured records. The model-directed trials charge only the displayed windows for document tokens and the provider's full usage for model tokens.

## Reading the scores

The automatic grade checks the answer, the expected declaration anchor, and the cited text. A checksum-bound review can add credit when a JDK table cell or a generated summary identifies the declaration and supports the answer even though the automatic grader loses its anchor. An answer that names the right fact about the wrong owner still fails. Reviewed scores retain the observed costs for every session, including abstentions. To inspect an individual answer, use the report command and look up its cited evidence IDs in the archived JSON.

```sh
python3 scripts/report-browse.py docs/climb/runs/035-commonmark-navigation.json \
  --reviews docs/climb/reviews.json --traces
```

Run 033 dispatched commands from the final response only. Runs 034–038 used the first syntactically valid command emitted in chronological order, including a command in commentary. The selection rule never consulted the fixture. Run 033 also lost declaration provenance in the aggregate file. In runs 034–037 the static browser used filenames in `llms-full.txt` source comments as link bases. A regular Markdown client resolves links relative to the file it fetched. Offline replay confirmed every displayed view, final citation and file read in runs 034–037 (49 sessions, 205 views, 139 fetches). One failed trial in run 036 followed a fragment to a different file under the old rule. Every reviewed pass was independent of this discrepancy. Run 038 uses regular URL resolution for all three sites. Its Javadoc reader also preserves the member linked by native deprecated-list summary rows.

## Isolating the type-directory hint

Run 039 uses the same `indexed` Codec snapshot as 038. To build its second arm, copy that directory to `.dev/discovery-typed/codec`. Change only the following strings, once each.

| File | Location | Original text | Trial text |
| --- | --- | --- | --- |
| `index.html` | `title` of the HTML head’s Markdown alternate for `index.md` | This page as Markdown | Module type directory in Markdown. Find the named type and open its page. |
| `llms.txt` | Description after the overview link `[⬢ commons-codec](index.md)` | packages and types | type directory. Find the named type, open its page, and match the member signature. |

Run the reader at `e645538` with `--cases 5 8 10 --offset 1`, the Codec questions, and sites `indexed=.dev/discovery-indexed/codec` and `typed=.dev/discovery-typed/codec`. The files grew by 52 and 65 bytes, respectively. All 256 other files have identical SHA-256 hashes.

In 038, those three named-owner questions took 1,922,658 bytes on the indexed site. The module HTML, the advertised `index.md` and the three type pages would cost 305,250 bytes when charged cold for each question. Each full member section contains all facts required by its question. This is the size of an available route. The trace records the route Luna actually chose. Stock Javadoc fetched 184,476 bytes for these three questions. On the entire five-question run its entry HTML cost 26,365 bytes across five cold reads, versus 347,735 for the generated module HTML.

## Completed navigation runs

Visible tokens count the exact documentation views Luna received, including repeated views. Provider input also includes prior conversation, instructions and cached tokens. The efficiency column counts reviewed grounded answers per 100,000 provider input plus output tokens. Each trial starts cold. Wall seconds are summed across trials. The reports also list cold bytes, cache hits, and costs on questions answered correctly by every arm.

| Run | Site | Frozen | Reviewed | Turns | Visible tokens | Provider input | Output | Wall s | Answers / 100k |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| [033](runs/033-jcommander-navigation.json) | javadoc | 5/8 | 5/8 | 33 | 8,889 | 501,278 | 1,517 | 148.8 | 0.99 |
| [033](runs/033-jcommander-navigation.json) | published | 4/8 | 7/8 | 28 | 12,395 | 400,587 | 1,104 | 116.5 | 1.74 |
| [033](runs/033-jcommander-navigation.json) | qualified | 3/8 | 3/8 | 23 | 7,703 | 273,930 | 1,038 | 89.5 | 1.09 |
| [034](runs/034-jcommander-linked.json) | qualified | 2/4 | 3/4 | 14 | 5,403 | 231,387 | 801 | 59.3 | 1.29 |
| [034](runs/034-jcommander-linked.json) | linked | 2/4 | 2/4 | 13 | 5,744 | 202,056 | 538 | 54.5 | 0.99 |
| [035](runs/035-commonmark-navigation.json) | javadoc | 2/5 | 3/5 | 22 | 8,820 | 351,057 | 1,450 | 102.7 | 0.85 |
| [035](runs/035-commonmark-navigation.json) | published | 2/5 | 3/5 | 22 | 9,722 | 313,893 | 853 | 93.0 | 0.95 |
| [035](runs/035-commonmark-navigation.json) | linked | 3/5 | 3/5 | 23 | 5,914 | 323,326 | 1,323 | 101.9 | 0.92 |
| [036](runs/036-jcommander-routing.json) | javadoc | 2/4 | 2/4 | 19 | 5,222 | 238,020 | 764 | 75.3 | 0.84 |
| [036](runs/036-jcommander-routing.json) | published | 4/4 | 4/4 | 20 | 8,048 | 299,909 | 907 | 80.5 | 1.33 |
| [036](runs/036-jcommander-routing.json) | linked | 4/4 | 4/4 | 10 | 3,856 | 119,689 | 703 | 53.1 | 3.32 |
| [036](runs/036-jcommander-routing.json) | balanced | 2/4 | 3/4 | 17 | 6,694 | 289,205 | 854 | 76.3 | 1.03 |
| [037](runs/037-commonmark-routing.json) | javadoc | 1/5 | 1/5 | 24 | 8,508 | 527,539 | 1,395 | 124.5 | 0.19 |
| [037](runs/037-commonmark-routing.json) | balanced | 3/5 | 3/5 | 21 | 9,395 | 397,855 | 1,161 | 101.7 | 0.75 |
| [038](runs/038-codec-indexed.json) | javadoc | 3/5 | 3/5 | 29 | 10,745 | 478,254 | 1,540 | 135.4 | 0.63 |
| [038](runs/038-codec-indexed.json) | fixed | 2/5 | 2/5 | 21 | 8,530 | 252,521 | 1,144 | 91.7 | 0.79 |
| [038](runs/038-codec-indexed.json) | indexed | 4/5 | 4/5 | 23 | 7,440 | 301,289 | 1,056 | 92.8 | 1.32 |
| [039](runs/039-codec-type-route.json) | indexed | 2/3 | 2/3 | 13 | 4,891 | 171,902 | 736 | 53.5 | 1.16 |
| [039](runs/039-codec-type-route.json) | typed | 2/3 | 2/3 | 14 | 5,843 | 272,883 | 741 | 64.4 | 0.73 |

## Reproduce the structural routing experiment

Run 040 uses the shipped Codec site from run 038. Both experimental sites start with a compact variant of the same renderer. Above 32 module types, the HTML overview lists direct type links and leaves full type descriptions on the package and type pages. The cutoff defines this experiment. On Codec, the compact HTML is 26,224 bytes versus 69,547 for the original, or 5,128 versus 8,727 at gzip level 6. The benchmark charges uncompressed file bytes. The compact overview retains all 110 type destinations, seven package links and HTML IDs. Runtime Kit and Gson are below the cutoff and produced byte-identical sites.

Build the experimental bundle in a scratch directory with the same dependencies as `scripts/update-renderer`. Set `components` to a local checkout containing the pinned renderer commit. Run from the Doclet repository root after generating the Codec fixture and the `indexed` control above.

```sh
trial=.dev/compact-renderer-040
mkdir -p "$trial"
git -C "$components" archive 84a7ea0c8aedf0fa3ee36a40be6c03afbe542671 | tar -x -C "$trial"
patch -s -d "$trial" -p1 < scripts/renderer-contracts.patch
patch -s -d "$trial" -p1 < docs/climb/experiments/compact-entry.patch
(
  cd "$trial"
  bun install --frozen-lockfile --ignore-scripts
  bun run --filter '@elide/tokens' build
  cd packages/apidocs && bun run build
)
renderer=doclet/src/main/resources/dev/elide/apidocs/renderer
node "$renderer/runner.mjs" --bundle "$trial/packages/apidocs/dist/render.js" \
  --assets "$renderer/assets" --model .dev/eval-codec/markdown/api.json --out .dev/discovery-lean/codec
python3 docs/climb/experiments/direct-type-links.py .dev/discovery-lean/codec .dev/discovery-lean-direct/codec
python3 docs/climb/experiments/package-contracts.py .dev/discovery-lean/codec .dev/discovery-lean-packages/codec
```

The direct variant adds links to Markdown type pages grouped by declaring package. The module head advertises the directory via a same-page fragment. The package variant publishes a Markdown list per package, with one row per existing structured declaration. Each row links to the canonical Markdown twin or member anchor and carries the documented summary, supplementary text, parameters, return, throws and deprecation fields when present. The module head exposes each list with the package's own description. Codec's seven lists cover all 1,007 declaration destinations. Their sizes range from 626 to 124,793 bytes. Both sites still expose the full global index and reference.

```sh
uv run --no-project --with tiktoken==0.12.0 python scripts/eval-browse.py \
  --questions samples/evals/codec/questions.json --cases 1 3 5 8 10 --offset 1 \
  --site indexed=.dev/discovery-indexed/codec --site direct=.dev/discovery-lean-direct/codec \
  --site packages=.dev/discovery-lean-packages/codec --output .dev/040.json
```

For reviewed grading, pass `--reviews docs/climb/reviews.json` to `scripts/report-browse.py` on the archived report. Reviews are bound to the exact file checksum.

### Isolate the compact entry and index evidence

Run 041 keeps the navigation links and their order identical to the shipped renderer. The `lean` arm changes just the module HTML. The `evidence` arm also appends complete documented contract fields to existing Markdown alphabetical rows, up to 320 characters of added text per row. It prioritizes deprecation, exceptions, returns, then supplementary prose. A link to the exact declaration already appears on every member row; a cue points readers there if a field exceeds the budget. Codec's aggregate reference has no copy of the alphabetical index. Relative to `lean`, only `index-all.md` changes. On Codec it grows from 194,887 to 298,916 bytes as 774 of 897 member rows gain evidence or a cue.

```sh
python3 docs/climb/experiments/index-evidence.py .dev/discovery-lean/codec .dev/discovery-lean-evidence/codec
uv run --no-project --with tiktoken==0.12.0 python scripts/eval-browse.py \
  --questions samples/evals/codec/questions.json --cases 1 3 5 8 10 --offset 2 \
  --site javadoc=.dev/eval-codec/javadoc --site indexed=.dev/discovery-indexed/codec \
  --site lean=.dev/discovery-lean/codec --site evidence=.dev/discovery-lean-evidence/codec \
  --output .dev/041.json
```

With the literal queries Luna used successfully in run 038 for Base64, Hex and the Soundex setter, the enriched index can expose all required evidence in the first 1,800-character view of that file for all three declarations. The original index required a follow-up read in those three cases. This offline check establishes what the new rows contain. Only a model-selected citation receives credit in run 041.

For a shipping implementation, the contract suffix belongs after the whole logical Markdown row. In the run 041 reconstruction, two Codec constructor summaries contain a code span across a physical newline. The experimental script inserted the suffix at the end of the first physical line for those rows. The upstream renderer prototype appends after the complete summary and preserves the original code span. Reproduce it by also applying [index-evidence.patch](experiments/index-evidence.patch) after the compact-entry patch, before building the experimental bundle. Its Codec site SHA-256 is `3a26f3bf172c76694571ef957c7783bb40dfc6a411247de121bab5ec8de2ac7b`. This candidate was built and frozen before any reserved-case run. The two affected overloads are `DecoderException(Throwable)` and `EncoderException(Throwable)`.
