# Agent documentation experiment ledger

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
  --account codex --chars 600 --layout bullets --terse --count-tokens --output .dev/luna-members.json

python3 scripts/eval-batch.py .dev/bullets.json --output .dev/luna-batch.json
```
