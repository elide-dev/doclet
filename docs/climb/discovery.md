# Reproduce the discovery treatment

The generated control and treatment use the same saved API model, assets, renderer entry point and theme. The control uses the renderer at `d498661`. The treatment adds a searchable `symbol` to each declaration record and describes that lookup in the HTML head and `llms.txt`. The original Javadoc directories are the third arm.

From the repository root, build the two libraries with JDK 25 and Elide. The fixture READMEs identify the required source archives and checksums. [Gson fixture](../../samples/evals/holdout/README.md) · [JCommander fixture](../../samples/evals/jcommander/README.md)

```sh
scripts/build-eval-holdout
scripts/build-eval-jcommander

renderer=doclet/src/main/resources/dev/elide/apidocs/renderer
mkdir -p .dev/discovery-render
git show "d498661:$renderer/render.js" > .dev/discovery-render/baseline.js

render_pair() {
  local name="$1" model="$2" arm bundle
  shift 2
  for arm in control treated; do
    bundle="$renderer/render.js"
    if [ "$arm" = control ]; then bundle=.dev/discovery-render/baseline.js; fi
    node "$renderer/runner.mjs" --bundle "$bundle" --assets "$renderer/assets" \
      --model "$model" --out ".dev/discovery-$arm/$name" "$@"
  done
}

render_pair gson .dev/eval-holdout/markdown/api.json
render_pair jcommander .dev/eval-jcommander/markdown/api.json
```

Both libraries use the default theme. To include the Runtime Kit development sample, run `scripts/build-samples`, then `render_pair runtime .dev/pages/signal/api.json --theme samples/themes/signal.json`. On each model, both arms should have identical Markdown twins, `llms-full.txt`, assets and API JSON. Each treated declaration retains its original contract fields and exact overload URL. URLs with similar short type names remain distinguishable by the canonical fragment.
