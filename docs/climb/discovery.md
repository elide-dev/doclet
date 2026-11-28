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
