# Agent documentation experiment ledger

Routine hosted comparisons use GPT-6 Luna at low effort. Keep GPT-6.2 Astra at ultra effort for a separate, explicitly selected full verification round. Each report records the model used.

## 2026-11-27 · Cold lookups from the module HTML

The reference and each control start at the module’s `index.html` on a loopback HTTP server. Each question starts cold. The ledger charges every document fetched, discovery included, and counts excerpt tokens separately from provider-reported tokens. Hosted turns use the local account and one isolated Luna low CLI call per fallback. All layouts share the excerpt budget, prompt, and extraction policy within a comparison. Wall time includes discovery, local extraction, CLI startup, and the hosted response. Source token-count instrumentation is excluded.

The first CommonMark trial used 13 untouched questions, a 600-character budget, and three routes fixed at `e933eb6`. Every route found all 13 exact member anchors. Both Javadoc controls answered 13/13. The generated compact route answered 12/13. Its excerpt omitted the unlimited nesting sentinel and Luna guessed zero. The updated generator marks dropped supplementary prose with `detailsOmitted`, and a scoped reader can open the referenced Markdown section directly. The frozen CommonMark result stands. [Blind CommonMark results](runs/020-commonmark-blind.json)

For the next blind comparison, freeze the updated readers before opening the Commons Codec results. Use 600 characters for each arm, the same cited-answer rules, and the ten frozen questions with exact member grading. Compare fully indexed Javadoc and a targeted reader using the native Javadoc type index. Rotate arm order by question. Repeat the two scoped readers with reversed order to check run-to-run variation. [Codec questions and provenance](../../samples/evals/codec/README.md)

### What the HTML links buy

On Gson, following the full `llms.txt` to the declaration records took 45 requests and 2,427,570 bytes. A short routing-only `llms.txt` used the same 45 requests and 2,200,635 bytes. Following the declaration link in the HTML head used 30 requests and 2,195,955 bytes. All three found evidence on 14/15 questions. Following inline type summaries used 799,507 bytes but found evidence on only 9/15. Keep the rich published index for browsing and let clients follow the HTML link when they need a declaration. [Offline discovery ablation](runs/018-gson-discovery.json)

With Luna, direct contracts answered 14/15 Gson questions versus 13/15 for full Javadoc in the first paired run. Adding selective reads of the exact Markdown member raised the generated score to 15/15, against 14/15 fully indexed Javadoc and 12/15 lazy alphabetical Javadoc. The 15/15 run still needed 14 hosted calls, used 96 output tokens versus the full control’s 94, and took 40.18 s versus 37.45 s. These follow-ups use a development corpus. [Direct](runs/019-gson-luna.json) · [Selective reads](runs/021-gson-adaptive.json)

A 240-character ceiling preserved generated evidence on Runtime Kit and Gson while dropping CommonMark from 13/13 to 10/13. With a 400-character limit CommonMark recovered to 12/13. We retained 600 for the held-out comparisons. Relevance-based packing can pick a different sentence at a larger budget, so scores need not increase monotonically. [Budget sweep](runs/022-budget-sweep.json)

### Two exact-type readers

The generated reader can use links in the entry HTML to read a named type’s Markdown directly. The comparable Javadoc reader follows package type links or its own published type search index. Without an unambiguous owner they fall back to the declaration records or alphabetical index. The separate eager Javadoc control indexes the text of every type before ranking.

| Corpus / reader | Grounded | Luna calls | Input | Output | Requests | Uncompressed bytes | Wall seconds |
|---|---:|---:|---:|---:|---:|---:|---:|
| CommonMark follow-up, generated | 13/13 | 12 | 142,319 | 104 | 26 | 871,410 | 39.08 |
| CommonMark follow-up, scoped Javadoc | 13/13 | 12 | 141,075 | 108 | 52 | 746,950 | 33.28 |
| Codec blind, generated | 6/10 | 7 | 83,022 | 73 | 20 | 1,830,191 | 18.38 |
| Codec blind, scoped Javadoc | 5/10 | 8 | 93,851 | 72 | 42 | 2,066,386 | 22.52 |
| Codec blind, eager Javadoc | 7/10 | 7 | 83,266 | 66 | 1,120 | 29,204,950 | 21.30 |

On CommonMark the repaired reader matched all 13 anchors and answers while using fewer requests and more cold bytes than scoped Javadoc. Counting distinct resources once changes the byte comparison to 110,904 generated versus 227,295 Javadoc. These are deduplicated byte counts. The timed measurements start cold for each question. [CommonMark follow-up](runs/023-commonmark-scoped.json)

On Codec the frozen generated reader delivered 1.36× the grounded answers per total model token of scoped Javadoc and 0.86× that of eager Javadoc. A second paired run, rotating the starting reader, repeated 6/10 versus 5/10. The generated arm used 54 versus 74 output tokens in the repeat, compared with 73 versus 72 on the first pass. Provider cache state and output wording vary even at fixed model and effort. [Blind Codec](runs/024-codec-blind.json) · [Scoped repeat](runs/025-codec-repeat.json)

### Reducing the cost of local evaluation

Batching all fallbacks in a single Luna turn per reader preserved all three frozen Codec scores (6/5/7). Generated input fell from 83,022 to 12,561 tokens, a 6.61× reduction. Scoped Javadoc fell from 93,851 to 12,963 and eager Javadoc from 83,266 to 12,802. The generated batch took 5.16 s including replayed lookup time versus 18.38 s for independent turns. The batch shares context across questions. Use the independent turns for the primary quality measurement. An initial unstructured-array pilot produced the wrong answer count on the second arm, so run 026 uses a schema with a separate required field for each question. [Batched comparison](runs/026-codec-batched.json)

### One-turn selection among exact declarations

Runs 027–028 are Codec follow-ups after examining the blind misses. The reader keeps conditional continuations together, checks the declared return type when the question specifies one, and rejects an identifier match when its contract contradicts the requested behavior. It can send up to three numbered candidates sharing 600 characters. Both formats use the same policy. Credit requires the selected candidate’s anchor and supporting excerpt.

| Codec reader | Audited grounded | Provider input | Provider output | Requests | Uncompressed bytes | Wall seconds |
|---|---:|---:|---:|---:|---:|---:|
| Generated, cited answer | 10/10 | 107,058 | 104 | 22 | 2,849,981 | 24.33 |
| Eager Javadoc, cited answer | 8/10 | 107,137 | 108 | 1,120 | 29,204,950 | 25.85 |
| Scoped Javadoc, cited answer | 7/10 | 107,162 | 93 | 480 | 12,223,092 | 25.12 |
| Generated, ID-only when eligible | 10/10 | 107,046 | 80 | 22 | 2,849,981 | 25.24 |
| Eager Javadoc, ID-only when eligible | 8/10 | 105,792 | 83 | 1,120 | 29,204,950 | 33.05 |
| Scoped Javadoc, ID-only when eligible | 7/10 | 107,146 | 76 | 480 | 12,223,092 | 26.27 |

The first run favored the generated reader over eager Javadoc on all six displayed measures. It delivered **1.25×** the audited grounded answers per total model token, with **10.25× fewer bytes** and **50.91× fewer requests**. In the next run the eager control used fewer total input tokens. Sending only a candidate number on two eligible identifier questions reduced total generated output from 104 to 80 tokens. The other answers still vary between runs. Both generated runs used nine hosted turns, two more than the original six-answer reader. [Cited candidates](runs/027-codec-candidates.json) · [ID-only ablation](runs/028-codec-identifiers.json)

The frozen scorer returned 10/7/7 in both runs. Manual review awarded the eager control its URLCodec answer in both. The correct response used `+` for “plus” in one run and a typographic apostrophe in the other. Both Javadoc readers correctly named the URL-safe encoder but the cited passage omitted the no-chunking requirement. Both called the decoding table “default” without identifying `MIXED`. We have retained the original scoring rules and the exact transcripts.

Replaying the same run 027 packets in one Luna turn per arm gave audited scores of 10/10 generated, 8/10 eager Javadoc, and 8/10 scoped Javadoc. The generated batch used 13,123 input and 102 output tokens. This was **8.16× less provider input** than nine individual generated calls. Its model time plus replayed lookup time was 5.12 s versus 24.33 s. The automatic generated score was 9/10 because the URL answer used the ellipsis “space is marked safe and plus is not.” The batch transcript keeps each exact packet, selected citation and raw reply. [Batched candidates](runs/029-codec-batched-candidates.json)

### Generalization to JCommander

We froze the reader at `f222ca6` and tested on eight new questions from eight JCommander 1.78 types. Seeded sampling drew documented public declarations from the upstream source. Both sites expose the same 321 indexed declarations. Each answer needs the expected exact member, evidence in its selected excerpt, and the requested facts. Manual review agreed with every frozen grade. [Selection procedure and license](../../samples/evals/jcommander/README.md) · [First blind run](runs/030-jcommander-blind.json)

| JCommander blind reader | Grounded | Input | Output | Requests | Uncompressed bytes | Wall seconds |
|---|---:|---:|---:|---:|---:|---:|
| Generated | 8/8 | 94,810 | 50 | 17 | 612,324 | 22.01 |
| Eager Javadoc | 7/8 | 94,880 | 53 | 432 | 6,356,368 | 22.91 |
| Scoped Javadoc | 7/8 | 94,873 | 50 | 133 | 2,041,807 | 21.21 |

On this blind run the generated reader beat eager Javadoc on each displayed axis. It used **10.38× fewer bytes**, **25.41× fewer requests**, and achieved **1.14×** the grounded answers per provider token. Scoped Javadoc tied output tokens and finished 0.80 s faster. All three readers made eight Luna calls and received 86,016 cached input tokens. Excerpts totaled 467, 541, and 545 tokens respectively. With the eager control’s 7/8 and 94,933 total tokens, even a perfect generated reader needs at most 21,698 total tokens to reach 5×. Account CLI overhead makes avoiding fallback turns the largest remaining opportunity. A single-digit change in provider output or sub-second timing difference on eight items is sensitive to repetition.

A second independent pass compared just generated and eager Javadoc, starting with Javadoc. The frozen scorer again returned 8/8 and 7/8. This time Javadoc named the exact `expandAtSign(Boolean)` overload, and its excerpt documented the parameter as “whether to expand @file”. That supports the answer even though it misses the frozen wording “Disables expanding”. On semantic review the repeat is **8/8 each**. Generated used 94,792 input and 50 output tokens versus 94,864 and 52. Wall time reversed to 22.67 s generated versus 20.58 s Javadoc. Eight questions and two passes support a consistent transfer advantage and place limits on accuracy and latency claims. [Independent repeat](runs/031-jcommander-repeat.json)

### Reproduce the current comparison

Run from the checkout with JDK 25, Elide, the source archive described in the fixture, and a locally authenticated Codex CLI. The hosted path fixes Luna low. Add `--offline` for deterministic retrieval without account calls. The per-question records distinguish evidence available among candidates from the actual citation selected by Luna.

```sh
scripts/build-eval-jcommander
uv run --no-project --with tiktoken==0.12.0 python scripts/eval-html-entry.py \
  --cited --alternatives 3 --identifier-ids --routes scoped javadoc-adaptive javadoc-scoped \
  --questions samples/evals/jcommander/questions.json --javadoc .dev/eval-jcommander/javadoc \
  --markdown .dev/eval-jcommander/markdown --output .dev/jcommander-luna.json

python3 scripts/eval-batch.py docs/climb/runs/027-codec-candidates.json \
  --questions samples/evals/codec/questions.json --output .dev/codec-batch.json
```

## 2026-11-27 · Separating index discovery from Markdown layout

Run 017 crossed two independent changes. For discovery, each type entry in `llms.txt` retained either its published member cues or just its description. For reading, each Markdown page retained or omitted its opening member bullets. The same lexical ranker selected one type. The reader got the first 600 characters, including frontmatter. Results below count questions with matching evidence on the expected type. No model was called.

| Type entries | Markdown prefix | Runtime Kit | Gson |
|---|---|---:|---:|
| Description only | No contract bullets | 0/10 | 0/15 |
| Description only | Contract bullets | 6/10 | 3/15 |
| Description and member cues | No contract bullets | 1/10 | 0/15 |
| Description and member cues | Contract bullets | 10/10 | 3/15 |

Cues raised correct type selection from 6 to 10 on Runtime Kit and from 9 to 14 on Gson. They raised whole-index size from 623 to 1,048 tokens and from 1,292 to 3,201 tokens, respectively (o200k_base). Gson shows where early type-page summaries stop helping: finding the right type still leaves the reader far from most overloads. Test a short index pointing to the member index and `contracts.ndjson` against the current inline cues; count the discovery tokens and fetches actually consumed. Start a separate HTML discovery test with only the HTML URL, since these runs supply the index directly. [Runtime Kit cases](runs/017-runtime-prefix.json) · [Gson cases](runs/017-gson-prefix.json)

Reproduce with `uv run --no-project --with tiktoken==0.12.0 python scripts/eval-prefix.py .dev/eval-holdout/markdown samples/evals/holdout/questions.json`. Gson is a follow-up corpus after run 007.

## 2026-11-27 · Local lookups and complete contract fields

**Outcome.** On the ten development questions, the two-stage reader (select a type, then select a member) achieved **8.36× the grounded answers per total model token** with GPT-6.2 Astra at ultra effort as fallback. The stronger control that pre-indexes every Javadoc member closed the gap. On the external Gson corpus, the initial blind result was a tie. Use the control name and corpus when quoting a multiplier.

The local reader can return a quoted answer and citation directly from the selected documentation. Both arms apply the same rules and use the same fallback model. Local extraction consumes zero hosted tokens. The report charges cached input at its full token count. Complete `Returns:` and `Throws:` fields let the reader distinguish contractual answers from prose, and abstain when an excerpt cuts off a condition. This matters for return values that have exceptions or alternatives separated by a semicolon.

| Corpus and control | Fallback | Javadoc correct | Generated correct | Model calls (JDK / generated) | Total model tokens (JDK / generated) | Efficiency ratio |
|---|---|---:|---:|---:|---:|---:|
| [Development, two-stage lookup](runs/015-astra-final.json) | Astra ultra | 6/10 | 10/10 | 5 / 1 | 60,289 / 12,020 | **8.36×** |
| [Development, full member indexes](runs/016-astra-full-index.json) | Astra ultra | 10/10 | 10/10 | 1 / 1 | 12,030 / 12,024 | **1.00×** |
| [Blind Gson, alphabetical member indexes](runs/007-gson.json) | Luna low | 9/15 | 9/15 | 13 / 13 | 152,807 / 154,019 | **0.99×** |
| [Gson follow-up, full indexes and overload matching](runs/012-gson-overloads.json) | Luna low | 13/15 | 14/15 | 14 / 14 | 166,018 / 163,086 | **1.10×** |

In the first row the ten lookups took 19.95 s for Javadoc and 3.83 s for Markdown, summed across independent tasks. The full-index development control took 3.05 s and 3.02 s. These are individual runs. The difference in fallback count dominates latency. Nine generated answers in each development run were local extractions, so the figures measure the combined reader and documentation format.

**Holdout audit.** We froze the 15 questions and evidence patterns before first retrieval against Gson 2.8.9. In run 007 both readers correctly identified `shouldSkipClass(Class)` from class-level prose that the member-wording regex rejected. Manual review found 10/15 grounded answers in each arm. In run 012 Javadoc again supplied that supported answer and the generated arm abstained, bringing manual scores to 14/15 each. Run 012 followed inspection of Gson results and uses the original frozen grader. Its remaining shared difficulty was choosing between a type description and a member description. [Fixture and attribution](../../samples/evals/holdout/README.md)

**Indexing cost.** The generated `contracts.ndjson` publishes directly addressable type and member records. The stronger Javadoc reader gathers full member text by parsing each type page. With 250 indexed declarations per arm, median local index construction took **2.57 ms versus 37.79 ms (14.72× faster)**. This measures rebuilding an in-memory index on a warm filesystem. It excludes documentation generation and hosted inference. We discarded one warm-up and alternated order for 20 measured trials. [Raw timings](runs/013-final-index-timing.json)

**Experiment history.** The first hybrid two-stage reader reached [4.18×](runs/005-hybrid-bullets.json); discovery via both alphabetical indexes gave [8/10 per arm](runs/006-member-index.json). Labeling returns reached [8.35× with Luna](runs/008-labeled-returns.json) and [8.36× with Astra](runs/011-astra-ultra.json). The stricter full-field reader initially reduced that to [4.18×](runs/014-astra-complete-fields.json). Explicit exception labels and condition delimiters restored the direct lookup in run 015. The earlier full-text Gson follow-up scored [11/15 JDK and 10/15 generated](runs/009-gson-full-index.json); its [index timings](runs/010-gson-index-timing.json) preceded adding type records and exact overload lookup.

### Reproduce locally

Use JDK 25, build the shared sample, and use a locally signed-in CLI. The original ten-question runs use the same per-question 600-character maximum, one selected member, and local lexical retrieval on both sides. To run the stronger control, compile the sample with `javadoc -public` and pass the resulting directory to `scripts/eval-lookup.py`. For the external library, the holdout builder produces both directories from one verified source archive.

```sh
uv run --no-project --with tiktoken==0.12.0 python scripts/eval-docs.py \
  --account codex --model gpt-6-luna --effort low --terse --extract-first \
  --layout bullets --chars 600 --count-tokens --output .dev/luna-development.json

scripts/build-eval-holdout
python3 scripts/eval-lookup.py --full-index --questions samples/evals/holdout/questions.json \
  --javadoc .dev/eval-holdout/javadoc --markdown .dev/eval-holdout/markdown \
  --output .dev/gson-lookup.json
python3 scripts/eval-index.py .dev/eval-holdout/javadoc .dev/eval-holdout/markdown \
  --output .dev/gson-index-time.json
```

Runs 005–007 used the first contract implementation at `98297b4`. Revision `84d1f66` preserves the first full-text control and `3b6e9aa` the overload-aware control. Keep future library-level claims paired with a fully indexed Javadoc baseline and a fresh holdout.

## 2026-11-27 · Short API answers

**Target.** Deliver at least five times as many grounded answers per 1,000 total model tokens as standard Javadoc on the same tasks and call policy. Also track absolute accuracy, excerpt tokens, output tokens, cache reads, and wall time. Total model tokens here means provider-reported input plus output, including cached input. This metric measures token efficiency; billing and account limits can weight cached input differently.

**Setup.** Renderer revision `f006491`. Ten fixed Java questions, JDK-generated Javadoc from identical sources, deterministic lexical type lookup, and a maximum of 600 excerpt characters per question. The initial offline experiment at revision `6d1a562` found evidence on 6/10 for both formats at 4,000 characters. These questions have since been used for tuning. A claim about other libraries needs a held-out library and unseen questions.

| Run | Reader input | Javadoc correct | Markdown correct | Javadoc input / output | Markdown input / output | Javadoc / Markdown wall time |
|---|---|---:|---:|---:|---:|---:|
| [001](runs/001-luna-prefix.json) | Page prefix, short sentence | 2/10 | 10/10 | 118,890 / 137 | 119,252 / 130 | 2.69 / 2.54 s median per question |
| [003](runs/003-luna-bullets.json) | Highest-ranked member, shortest correct phrase | 6/10 | 10/10 | 118,186 / 57 | 117,971 / 68 | 2.62 / 2.51 s median per question |
| [004](runs/004-luna-batch.json) | Same member excerpts, ten questions per turn | 6/10 | 10/10 | 12,360 / 32 | 12,142 / 42 | 3.23 / 3.09 s for the whole batch |

All answer runs used the local Codex CLI with GPT-6 Luna at low effort. The scorer requires the expected page, supporting text in the excerpt, and an answer matching the fixture. The lexical index is processed locally, outside the model context. The historical prefix experiment reached **5× raw accuracy**. The stronger member-lookup control yields **1.67× grounded answers per total token** in independent turns and **1.70×** in batches. A fivefold advantage over this control remains open.

### 002 · Which layout earns its tokens?

We ranked member-detail sections for Javadoc and contract rows for Markdown. Both paths prioritize explicit qualified member names. Layout was applied after selection. Counts below are totals across the ten excerpts, using the pinned tiktoken package and its o200k_base encoding as a document-only proxy.

| Excerpt | Document tokens | Questions with evidence |
|---|---:|---:|
| [Javadoc page prefix](runs/002-prefix.json) | 1,072 | 2 |
| [Markdown page prefix](runs/002-prefix.json) | 1,434 | 10 |
| [One Javadoc member](runs/002-bullets.json) | 380 | 6 |
| [One Markdown bullet](runs/002-bullets.json) | **169** | **10** |
| [One Markdown bullet with →, ⚠️, ⛔ substitutions](runs/002-symbols.json) | 176 | 10 |
| [One two-column Markdown table with header](runs/002-table.json) | 277 | 10 |

**Decision.** Keep bullets for isolated member contracts. Extracting the relevant bullet used **8.5× fewer document tokens** than reading our Markdown prefix. The symbol substitutions cost seven extra tokens even without a legend. Test tables separately on multi-row comparison questions, where several answers can share the header. The table and symbol results measure evidence availability only.

**Output and call overhead.** The member excerpt plus terse prompt reduced Markdown output from 130 to 68 tokens across ten independently answered questions. Both changes were introduced together. Batching used **9.7× fewer total input tokens** than independent Markdown turns and returned ten grounded answers again. Javadoc also benefited from batching. All batch items share a context and can influence one another; the independent-turn run remains the accuracy benchmark. Wall times are single observations, and the batched Javadoc call ran first with zero cached input versus 10,752 cached tokens for Markdown.

### Reproduce and append

Build the shared sample, put JDK 25 on PATH, and run from the repository root. Archive each completed run under a new filename in this directory. Include the renderer revision, fixture or grader changes, model, budget, call policy, and matching Javadoc control in its ledger entry. Keep intermediate reports under `.dev`.

```sh
for layout in prefix bullets table symbols; do
  uv run --no-project --with tiktoken==0.12.0 python scripts/eval-docs.py \
    --paired --chars 600 --layout "$layout" --count-tokens --output ".dev/$layout.json"
done

uv run --no-project --with tiktoken==0.12.0 python scripts/eval-docs.py \
  --account codex --model gpt-6-luna --effort low --chars 600 --layout bullets \
  --terse --count-tokens --output .dev/luna-members.json

python3 scripts/eval-batch.py .dev/bullets.json --output .dev/luna-batch.json
```
