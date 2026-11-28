import * as fs from "node:fs";
import path from "node:path";
import http from "node:http";
import os from "node:os";
import { execFileSync } from "node:child_process";

const escape = (value) =>
  String(value).replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const link = (href, label) => `<a href="${escape(href)}">${escape(label)}</a>`;
const style =
  "body{max-inline-size:70rem;margin:3rem auto;padding:0 1rem;font:1rem/1.6 system-ui;color-scheme:light dark}nav{display:flex;gap:1rem;flex-wrap:wrap}input{font:inherit;padding:.4rem}pre{white-space:pre-wrap}";
const page = (title, content) =>
  `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escape(title)}</title><style>${style}</style><main><h1>${escape(title)}</h1>${content}</main></html>`;
const list = (entries) =>
  "<ul>" + entries.map((x) => "<li>" + x + "</li>").join("") + "</ul>";
const read = (file) => fs.readFileSync(file, "utf8");
const json = (file) => JSON.parse(read(file));

function declarations(model) {
  const result = new Map();
  function add(node) {
    const signature = (node.signature ?? [])
      .filter((t) => t.kind !== "punct" || t.text.trim())
      .map((t) => (t.kind === "punct" ? t.text.replace(/\s/g, "") : t.text));
    result.set(node.id, {
      signature: JSON.stringify(signature),
      text: (node.signature ?? []).map((t) => t.text).join(""),
      deprecated: !!node.deprecated,
    });
  }
  for (const pkg of model.packages ?? []) {
    for (const type of pkg.types ?? []) {
      add(type);
      for (const member of type.members ?? []) add(member);
    }
    for (const node of [
      ...(pkg.functions ?? []),
      ...(pkg.properties ?? []),
      ...(pkg.typeAliases ?? []),
    ])
      add(node);
  }
  return result;
}

function changes(before, after) {
  const entries = [];
  for (const name of new Set([
    ...Object.keys(before.modules),
    ...Object.keys(after.modules),
  ])) {
    const old = before.modules[name],
      current = after.modules[name];
    if (!old || !current) {
      entries.push(escape((old ? "Removed module " : "Added module ") + name));
      continue;
    }
    const a = declarations(old.model),
      b = declarations(current.model);
    for (const id of new Set([...a.keys(), ...b.keys()])) {
      const x = a.get(id),
        y = b.get(id);
      const label = escape(name + " / " + id);
      if (!x || !y) entries.push((x ? "Removed " : "Added ") + label);
      else {
        if (x.signature !== y.signature)
          entries.push(
            "Changed " +
              label +
              "<pre><del>" +
              escape(x.text) +
              "</del>\n<ins>" +
              escape(y.text) +
              "</ins></pre>",
          );
        if (!x.deprecated && y.deprecated) entries.push("Deprecated " + label);
      }
    }
  }
  return entries.length ? list(entries) : "<p>No API changes.</p>";
}

function scan(dir, visit, prefix = "") {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const name = prefix + entry.name;
    if (entry.isSymbolicLink())
      throw new Error(
        "Unexpected symbolic link: " + path.join(dir, entry.name),
      );
    if (entry.isDirectory())
      scan(path.join(dir, entry.name), visit, name + "/");
    else if (entry.isFile()) visit(name);
  }
}

function segment(id) {
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(id))
    throw new Error("Invalid catalog path segment: " + id);
  return id;
}

// Build and validate symbol links before copying any catalog pages.
function guideSuites(version, manifest, work) {
  const backlinks = new Map();
  if (!Object.keys(version.suites ?? {}).length) return backlinks;
  const targets = new Map();
  for (const [id, module] of Object.entries(version.modules)) {
    for (const pkg of module.model.packages ?? []) {
      const prefix = (pkg.name || "default-package").replaceAll(".", "/") + "/";
      const register = (key, file, anchor) => {
        if (!module.files.has(file))
          throw new Error("Missing declaration page: " + file);
        targets.set(
          "api/" + id + "/" + key,
          "api/" +
            id +
            "/" +
            file +
            (anchor ? "#" + encodeURIComponent(anchor) : ""),
        );
      };
      for (const type of pkg.types ?? []) {
        const file = prefix + type.name + ".html";
        register(type.id, file);
        register([pkg.name, type.name].filter(Boolean).join("."), file);
        for (const member of type.members ?? [])
          register(member.id, file, member.anchor);
      }
      for (const item of [
        ...(pkg.functions ?? []),
        ...(pkg.properties ?? []),
        ...(pkg.typeAliases ?? []),
      ])
        register(item.id, prefix + "index.html", item.anchor ?? item.name);
    }
  }
  const mapping = path.join(work, "targets.tsv");
  fs.writeFileSync(
    mapping,
    [...targets].map((pair) => pair.join("\t")).join("\n"),
  );
  for (const [id, suite] of Object.entries(version.suites)) {
    segment(id);
    if (Object.hasOwn(version.modules, id))
      throw new Error("Suite and module share an ID: " + id);
    const src = fs.realpathSync(
      path.resolve(path.dirname(manifest), suite.directory),
    );
    const temp = fs.mkdtempSync(path.join(work, "suite-"));
    const input = path.join(temp, "input"),
      dir = path.join(temp, "site");
    fs.mkdirSync(input);
    scan(src, (file) => {
      if (
        !file.endsWith(".md") ||
        (!version.includeDrafts && file.split("/").includes("drafts"))
      )
        return;
      const dest = path.join(input, file);
      fs.mkdirSync(path.dirname(dest), { recursive: true });
      fs.copyFileSync(path.join(src, file), dest);
    });
    execFileSync(
      process.env.APIDOCS_JAVA || "java",
      [
        "-cp",
        process.env.APIDOCS_CLASSPATH,
        "dev.elide.apidocs.doclet.GuideSuite",
        suite.title || id,
        version.id,
        input,
        dir,
        mapping,
      ],
      { stdio: "inherit" },
    );
    const files = new Set();
    scan(dir, (file) => files.add(file));
    const model = json(path.join(dir, "api.json"));
    version.modules[id] = { dir, files, model, suite: true };
    function collect(node, label, page) {
      if (!node || typeof node !== "object") return;
      const key = node.target?.id;
      if (targets.has(key)) {
        const target = targets.get(key).split("#")[0];
        if (!backlinks.has(target)) backlinks.set(target, new Map());
        backlinks.get(target).set("guides/" + id + "/" + page, label);
      }
      for (const child of Object.values(node)) collect(child, label, page);
    }
    collect(model.module.doc, model.module.name, "index.html");
    for (const guide of model.module.guides ?? [])
      collect(guide, guide.title, "guides/" + guide.slug + ".html");
  }
  return backlinks;
}

const sitePath = (module, id) => (module.suite ? "guides/" : "api/") + id + "/";

function build(manifest, output) {
  const spec = json(manifest);
  const ids = new Set();
  for (const version of spec.versions) {
    segment(version.id);
    if (
      ids.has(version.id) ||
      ["latest", "index.html", "versions.json"].includes(version.id)
    )
      throw new Error("Reserved or duplicate version: " + version.id);
    ids.add(version.id);
    if (!Object.keys(version.modules).length)
      throw new Error("No modules for " + version.id);
    for (const [id, source] of Object.entries(version.modules)) {
      segment(id);
      const dir = fs.realpathSync(path.resolve(path.dirname(manifest), source));
      const model = json(path.join(dir, "api.json"));
      if (model.schema !== "apidocs/1")
        throw new Error("Expected apidocs/1 in " + dir);
      const files = [];
      scan(dir, (name) => files.push(name));
      if (!files.includes("index.html"))
        throw new Error("Missing rendered index in " + dir);
      version.modules[id] = { dir, model, files: new Set(files) };
    }
  }
  if (!ids.has(spec.latest))
    throw new Error("latest must name a configured version");
  if (fs.existsSync(output))
    throw new Error("Choose a new output directory: " + output);
  fs.mkdirSync(path.dirname(output), { recursive: true });
  const staging = fs.mkdtempSync(output + ".tmp-");
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), "catalog-guides-"));
  try {
    const relations = new Map(
      spec.versions.map((v) => [v, guideSuites(v, manifest, temp)]),
    );
    const write = (name, data) => {
      const file = path.join(staging, name);
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fs.writeFileSync(file, data);
    };
    const versionLinks = spec.versions.map((v) =>
      link(v.id + "/index.html", v.id),
    );
    write(
      "index.html",
      page(
        spec.title ?? "API reference",
        list([link("latest/index.html", "Latest release"), ...versionLinks]),
      ),
    );
    write(
      "versions.json",
      JSON.stringify({
        latest: spec.latest,
        versions: spec.versions.map((v) => ({
          id: v.id,
          modules: Object.keys(v.modules).filter((id) => !v.modules[id].suite),
          suites: Object.keys(v.suites ?? {}),
        })),
      }),
    );
    for (const [index, v] of spec.versions.entries()) {
      const backlinks = relations.get(v);
      const moduleLinks = [],
        search = [];
      for (const [id, m] of Object.entries(v.modules)) {
        const prefix = sitePath(m, id);
        const base = v.id + "/" + prefix;
        moduleLinks.push(link(prefix + "index.html", m.model.module.name));
        const script = read(path.join(m.dir, "assets/search-index.js")).trim();
        const match = /^window\.apidocsSearch\s*=\s*([\s\S]*?);?$/.exec(script);
        if (!match) throw new Error("Unknown search index format: " + m.dir);
        for (const [label, kind, href, owner, summary] of JSON.parse(
          match[1],
        )) {
          if (!m.files.has(href.split("#")[0])) continue;
          search.push([label, kind, prefix + href, id, owner, summary]);
        }
        for (const file of m.files) {
          const src = path.join(m.dir, file);
          const owner = prefix + file.replace(/\.md$/, ".html");
          const related = [...(backlinks.get(owner) ?? [])].map(
            ([dest, label]) => [
              path.posix.relative(path.posix.dirname(owner), dest),
              label,
            ],
          );
          if (file.endsWith(".md") && related.length) {
            write(
              base + file,
              read(src) +
                "\n## Related guides\n\n" +
                related
                  .map(
                    ([href, label]) =>
                      "- [" +
                      label.replace(/[\[\]]/g, "\\$&") +
                      "](" +
                      href.replace(/\.html$/, ".md") +
                      ")",
                  )
                  .join("\n") +
                "\n",
            );
            continue;
          }
          if (!file.endsWith(".html")) {
            write(base + file, fs.readFileSync(src));
            continue;
          }
          const root = "../".repeat(3 + file.split("/").length - 1);
          const versions = spec.versions.map((other) => {
            const target = other.modules[id];
            const dest = target
              ? sitePath(target, id) +
                (target.files.has(file) ? file : "index.html")
              : "index.html";
            return link(root + other.id + "/" + dest, other.id);
          });
          const modules = Object.keys(v.modules).map((other) =>
            link(
              root +
                v.id +
                "/" +
                sitePath(v.modules[other], other) +
                "index.html",
              other,
            ),
          );
          const menu =
            '<details class="catalog-menu"><summary>Versions and modules</summary><nav aria-label="Reference catalog"><p>Versions · ' +
            versions.join(" · ") +
            "</p><p>Modules · " +
            modules.join(" · ") +
            "</p>" +
            link(root + v.id + "/index.html", "Search all modules") +
            "</nav></details>";
          write(
            base + file,
            read(src)
              .replace(
                '<a class="brand" ',
                () =>
                  '<a class="brand" aria-label="' +
                  escape(m.model.module.name + " overview") +
                  '" ',
              )
              .replace(
                "</main>",
                () =>
                  (related.length
                    ? "<section><h2>Related guides</h2>" +
                      list(related.map(([href, label]) => link(href, label))) +
                      "</section>"
                    : "") + "</main>",
              )
              .replace("</header>", () => menu + "</header>"),
          );
        }
      }
      // Every page uses its module's index. Keep local entries first and append the version's other sites.
      for (const [id, m] of Object.entries(v.modules)) {
        const prefix = sitePath(m, id);
        const ranked = [
          ...search.filter((row) => row[3] === id),
          ...search.filter((row) => row[3] !== id),
        ];
        const entries = ranked.map(
          ([label, kind, href, module, owner, summary]) => [
            label,
            kind,
            path.posix.relative(prefix, href),
            module === id ? owner : module + " · " + owner,
            summary,
          ],
        );
        write(
          v.id + "/" + prefix + "assets/search-index.js",
          "window.apidocsSearch=" + JSON.stringify(entries) + ";",
        );
      }
      const comparisons = [];
      for (const previous of spec.versions.slice(index + 1)) {
        const name = "changes/" + previous.id + ".html";
        comparisons.push(link(name, "Changes since " + previous.id));
        write(
          v.id + "/" + name,
          page(
            v.id + " compared with " + previous.id,
            link("../index.html", "Module index") + changes(previous, v),
          ),
        );
      }
      write(
        v.id + "/search.js",
        "window.catalogSearch=" + JSON.stringify(search) + ";",
      );
      write(
        v.id + "/index.html",
        page(
          v.id,
          link("../index.html", "All versions") +
            list(moduleLinks) +
            '<label>Search all modules <input type="search" id="query"></label><ul id="results"></ul>' +
            comparisons.join(" · ") +
            '<script src="search.js"></script><script>' +
            searchClient.toString() +
            ";searchClient();</script>",
        ),
      );
    }
    fs.cpSync(path.join(staging, spec.latest), path.join(staging, "latest"), {
      recursive: true,
    });
    fs.renameSync(staging, output);
  } finally {
    fs.rmSync(temp, { recursive: true, force: true });
    fs.rmSync(staging, { recursive: true, force: true });
  }
}

function searchClient() {
  const input = document.querySelector("#query"),
    results = document.querySelector("#results");
  input.addEventListener("input", () => {
    results.replaceChildren();
    const q = input.value.toLowerCase().trim();
    if (!q) return;
    for (const [
      label,
      kind,
      href,
      module,
      owner,
      summary,
    ] of window.catalogSearch) {
      if (![label, module, owner, summary].join(" ").toLowerCase().includes(q))
        continue;
      const item = document.createElement("li"),
        a = document.createElement("a");
      a.href = href;
      a.textContent = module + " · " + label + " (" + kind + ")";
      item.append(a);
      results.append(item);
      if (results.childElementCount === 100) break;
    }
  });
}

function serve(directory, port) {
  const root = fs.realpathSync(directory);
  const mime = {
    ".md": "text/markdown",
    ".html": "text/html",
    ".js": "application/javascript",
    ".css": "text/css",
    ".json": "application/json",
    ".ndjson": "application/x-ndjson",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".woff2": "font/woff2",
    ".txt": "text/plain",
    ".xml": "application/xml",
  };
  const server = http.createServer((req, res) => {
    if (!["GET", "HEAD"].includes(req.method)) {
      res.writeHead(405, { allow: "GET, HEAD" }).end();
      return;
    }
    try {
      const url = new URL(req.url, "http://localhost");
      let file = fs.realpathSync(
        path.join(root, decodeURIComponent(url.pathname)),
      );
      if (file !== root && !file.startsWith(root + path.sep)) throw new Error();
      if (fs.statSync(file).isDirectory()) {
        if (!url.pathname.endsWith("/")) {
          res
            .writeHead(308, {
              location: url.pathname.replace(/^\/+/, "/") + "/" + url.search,
            })
            .end();
          return;
        }
        file = fs.realpathSync(path.join(file, "index.html"));
      }
      if (!file.startsWith(root + path.sep)) throw new Error();
      if (file.endsWith(".html")) {
        res.setHeader("Vary", "Accept");
        const types = (req.headers.accept ?? "").split(",").map((part) => {
          const [type, ...params] = part.trim().toLowerCase().split(";");
          const q = params.map((x) => x.trim()).find((x) => x.startsWith("q="));
          return [type, q ? Number(q.slice(2)) : 1];
        });
        const quality = (t) => {
          for (const range of [t, "text/*", "*/*"]) {
            const match = types.find(([type]) => type.trim() === range);
            if (match) return match[1];
          }
          return 0;
        };
        const md = file.slice(0, -5) + ".md";
        if (
          (quality("text/markdown") ?? 0) > 0 &&
          quality("text/markdown") > (quality("text/html") ?? 0) &&
          fs.existsSync(md)
        )
          file = fs.realpathSync(md);
      }
      if (!file.startsWith(root + path.sep) || !fs.statSync(file).isFile())
        throw new Error();
      res.setHeader(
        "Content-Type",
        mime[path.extname(file)] ?? "application/octet-stream",
      );
      if (req.method === "HEAD") res.end();
      else
        fs.createReadStream(file)
          .on("error", () => res.destroy())
          .pipe(res);
    } catch {
      res.writeHead(404).end();
    }
  });
  server.listen(Number(port ?? 8080), "127.0.0.1", () =>
    console.log(
      "Serving " + root + " on http://127.0.0.1:" + server.address().port,
    ),
  );
}

const [command, a, b] = process.argv.slice(2);
if (command === "build" && a && b) build(path.resolve(a), path.resolve(b));
else if (command === "serve" && a) serve(a, b);
else
  throw new Error(
    "Usage: catalog.mjs build manifest.json new-directory | serve directory [port]",
  );
