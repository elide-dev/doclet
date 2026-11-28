# Reproduce the discovery treatment

The three generated sites use the same saved API model, assets, renderer entry point and theme. The control uses the renderer at `d498661`. The qualified-symbol treatment at `90747f3` adds a searchable `symbol` to each declaration record and describes that lookup in the HTML head and `llms.txt`. The linked treatment also adds exact member fragment links to the API-at-a-glance summaries on type Markdown pages. Each library’s original Javadoc directory supplies the JDK comparison.

From the repository root, build the libraries with JDK 25 and Elide. The fixture READMEs identify the required source archives and checksums. [Gson fixture](../../samples/evals/holdout/README.md) · [JCommander fixture](../../samples/evals/jcommander/README.md) · [CommonMark fixture](../../samples/evals/commonmark/README.md)

```sh
scripts/build-eval-holdout
scripts/build-eval-jcommander
scripts/build-eval-commonmark

renderer=doclet/src/main/resources/dev/elide/apidocs/renderer
mkdir -p .dev/discovery-render
git show "d498661:$renderer/render.js" > .dev/discovery-render/baseline.js
git show "90747f3:$renderer/render.js" > .dev/discovery-render/qualified.js

render_variants() {
  local name="$1" model="$2" arm bundle
  shift 2
  for arm in control treated linked; do
    case "$arm" in
      control) bundle=.dev/discovery-render/baseline.js ;;
      treated) bundle=.dev/discovery-render/qualified.js ;;
      linked) bundle="$renderer/render.js" ;;
    esac
    node "$renderer/runner.mjs" --bundle "$bundle" --assets "$renderer/assets" \
      --model "$model" --out ".dev/discovery-$arm/$name" "$@"
  done
}

render_variants gson .dev/eval-holdout/markdown/api.json
render_variants jcommander .dev/eval-jcommander/markdown/api.json
render_variants commonmark .dev/eval-commonmark/markdown/api.json
```

These libraries use the default theme. To include the Runtime Kit development sample, run `scripts/build-samples`, then `render_variants runtime .dev/pages/signal/api.json --theme samples/themes/signal.json`. The control and qualified-symbol sites have identical Markdown twins and `llms-full.txt`. Between the qualified and linked sites, only type Markdown and its copy in `llms-full.txt` change. Every arm uses the same canonical member anchors, model, and assets.
