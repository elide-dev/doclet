/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.doclet;

import com.sun.source.doctree.AttributeTree;
import com.sun.source.doctree.DeprecatedTree;
import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.EndElementTree;
import com.sun.source.doctree.EntityTree;
import com.sun.source.doctree.ErroneousTree;
import com.sun.source.doctree.EscapeTree;
import com.sun.source.doctree.IndexTree;
import com.sun.source.doctree.LinkTree;
import com.sun.source.doctree.LiteralTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.doctree.RawTextTree;
import com.sun.source.doctree.ReferenceTree;
import com.sun.source.doctree.ReturnTree;
import com.sun.source.doctree.SeeTree;
import com.sun.source.doctree.SinceTree;
import com.sun.source.doctree.SnippetTree;
import com.sun.source.doctree.SpecTree;
import com.sun.source.doctree.StartElementTree;
import com.sun.source.doctree.SummaryTree;
import com.sun.source.doctree.SystemPropertyTree;
import com.sun.source.doctree.TextTree;
import com.sun.source.doctree.ThrowsTree;
import com.sun.source.doctree.UnknownBlockTagTree;
import com.sun.source.doctree.UnknownInlineTagTree;
import com.sun.source.doctree.ValueTree;
import com.sun.source.util.DocTreePath;
import com.sun.source.util.DocTrees;
import com.sun.source.util.TreePath;
import dev.elide.apidocs.model.Api;
import dev.elide.apidocs.model.Doc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Documentation comments as the model's documentation tree.
 *
 * <p>A comment's trees become a flat stream of events (text, start and end tags, converted inline
 * tags), the stream a small element tree with HTML's implicit closing rules ({@code <p>}, {@code
 * <li>}, table cells), and the element tree blocks and inlines. Javadoc's HTML subset maps to
 * nodes; other markup is kept as a raw node, never dropped. Tags and constructs the model cannot
 * represent are errors. Markdown comments ({@code ///}) become the same events through {@link
 * Markdown}.
 *
 * <p>{@code {@inheritDoc}}, and parts a method's comment leaves out (the description, a
 * {@code @param}, the {@code @return}, the {@code @throws} of a declared exception), come from the
 * methods it overrides: the superclass chain first, then superinterfaces, depth first, using the
 * first that documents the part.
 */
final class Comments {
  private static final Set<String> BLOCKS =
      Set.of(
          "p",
          "ul",
          "ol",
          "li",
          "pre",
          "table",
          "thead",
          "tbody",
          "tfoot",
          "tr",
          "td",
          "th",
          "caption",
          "h1",
          "h2",
          "h3",
          "h4",
          "h5",
          "h6",
          "blockquote",
          "dl",
          "dt",
          "dd",
          "div",
          "hr",
          "section",
          "article",
          "aside",
          "header",
          "footer",
          "main",
          "nav",
          "figure",
          "figcaption",
          "details",
          "summary");
  private static final Set<String> TRANSPARENT =
      Set.of(
          "div",
          "section",
          "article",
          "aside",
          "header",
          "footer",
          "main",
          "nav",
          "figure",
          "figcaption",
          "details",
          "summary");
  private static final Set<String> VOID =
      Set.of("br", "hr", "img", "wbr", "col", "area", "input", "source");
  private static final Set<String> CODE = Set.of("code", "tt", "kbd", "samp", "var");
  private static final Set<String> EMPHASIS = Set.of("em", "i", "cite", "dfn");
  private static final Set<String> STRONG = Set.of("strong", "b");
  private static final Set<String> NOTE_TAGS = Set.of("apiNote", "implSpec", "implNote");
  private static final Set<String> NOT_MODELED =
      Set.of("author", "version", "serial", "serialData", "serialField");
  static final Map<String, String> ENTITIES =
      Map.ofEntries(
          Map.entry("lt", "<"),
          Map.entry("gt", ">"),
          Map.entry("amp", "&"),
          Map.entry("quot", "\""),
          Map.entry("apos", "'"),
          Map.entry("nbsp", " "),
          Map.entry("mdash", "—"),
          Map.entry("ndash", "–"),
          Map.entry("hellip", "…"),
          Map.entry("copy", "©"),
          Map.entry("reg", "®"),
          Map.entry("trade", "™"),
          Map.entry("rarr", "→"),
          Map.entry("larr", "←"),
          Map.entry("harr", "↔"),
          Map.entry("rArr", "⇒"),
          Map.entry("times", "×"),
          Map.entry("le", "≤"),
          Map.entry("ge", "≥"),
          Map.entry("ne", "≠"),
          Map.entry("laquo", "«"),
          Map.entry("raquo", "»"),
          Map.entry("lsquo", "‘"),
          Map.entry("rsquo", "’"),
          Map.entry("ldquo", "“"),
          Map.entry("rdquo", "”"),
          Map.entry("middot", "·"),
          Map.entry("bull", "•"),
          Map.entry("sect", "§"),
          Map.entry("para", "¶"),
          Map.entry("deg", "°"),
          Map.entry("plusmn", "±"),
          Map.entry("micro", "µ"),
          Map.entry("divide", "÷"),
          Map.entry("infin", "∞"),
          Map.entry("sum", "∑"),
          Map.entry("lambda", "λ"));

  private final DocTrees trees;
  private final Elements elements;
  private final Types types;
  private final Linker linker;
  private final Report report;

  private final Map<String, CustomTag> customTags;

  Comments(DocTrees trees, Elements elements, Types types, Linker linker, Report report) {
    this(trees, elements, types, linker, report, Map.of());
  }

  Comments(
      DocTrees trees,
      Elements elements,
      Types types,
      Linker linker,
      Report report,
      Map<String, CustomTag> customTags) {
    this.trees = trees;
    this.elements = elements;
    this.types = types;
    this.linker = linker;
    this.report = report;
    this.customTags = customTags;
  }

  /**
   * A custom block tag from the standard doclet's {@code -tag name:locations:header}. Its content
   * follows the description under its header. A tag whose locations include {@code X} is disabled:
   * left out without a problem.
   */
  record CustomTag(String name, String header, boolean disabled) {
    /**
     * {@code spec} as a custom tag, or {@code null} for a bare name (which only orders standard
     * tags in the standard doclet). Colons in the name or header can be escaped as {@code \:}.
     */
    static CustomTag parse(String spec) {
      List<String> parts = new ArrayList<>();
      var part = new StringBuilder();
      for (int i = 0; i < spec.length(); i++) {
        var c = spec.charAt(i);
        if (c == '\\' && i + 1 < spec.length() && spec.charAt(i + 1) == ':') {
          part.append(':');
          i++;
        } else if (c == ':' && parts.size() < 2) {
          parts.add(part.toString());
          part.setLength(0);
        } else {
          part.append(c);
        }
      }
      parts.add(part.toString());
      var name = parts.get(0).strip();
      if (name.isEmpty()) {
        throw new IllegalArgumentException("-tag needs a tag name: " + spec);
      }
      if (parts.size() == 1) {
        return null;
      }
      var header = parts.size() > 2 && !parts.get(2).isBlank() ? parts.get(2).strip() : name;
      return new CustomTag(name, header, parts.get(1).contains("X"));
    }
  }

  /** What an element's comment says, with tags split out and inherited parts filled in. */
  record Parts(
      Doc doc,
      Map<String, List<Doc.Block>> params,
      Map<String, List<Doc.Block>> typeParams,
      List<Doc.Block> returns,
      List<Thrown> exceptions,
      String since,
      List<Doc.Block> deprecated,
      boolean hidden) {}

  /**
   * One {@code @throws} tag: the exception's element (or {@code null} when unresolved) and its
   * description.
   */
  record Thrown(Element type, String name, List<Doc.Block> doc) {}

  private static final Parts NONE =
      new Parts(null, Map.of(), Map.of(), List.of(), List.of(), null, null, false);

  /** Whether {@code element} carries {@code @hidden}. */
  boolean hidden(Element element) {
    var dc = trees.getDocCommentTree(element);
    return dc != null
        && dc.getBlockTags().stream().anyMatch(t -> t.getKind() == DocTree.Kind.HIDDEN);
  }

  /** {@code element}'s documentation. */
  Parts parts(Element element) {
    var dc = trees.getDocCommentTree(element);
    if (dc == null) {
      if (element instanceof ExecutableElement method) {
        for (var overridden : overridden(method)) {
          if (trees.getDocCommentTree(overridden) != null) {
            var inherited = parts(overridden);
            var doc = inherited.doc();
            var from = linker.ref(overridden, null);
            var renamed = renameParams(inherited.params(), overridden, method);
            return new Parts(
                new Doc(doc.summary(), doc.body(), doc.notes(), doc.see(), from),
                renamed,
                inherited.typeParams(),
                inherited.returns(),
                inherited.exceptions(),
                null,
                null,
                false);
          }
        }
      }
      return NONE;
    }
    var ctx = new Ctx(element, dc, trees.getPath(element));

    List<Doc.Inline> summary;
    List<Doc.Block> body;
    if (dc.getFullBody().isEmpty() && element instanceof ExecutableElement method) {
      var inherited = inheritedDescription(method);
      summary = inherited == null ? List.of() : inherited.doc().summary();
      body = inherited == null ? List.of() : inherited.doc().body();
    } else {
      Supplier<List<Event>> description = () -> inheritedBodyEvents(ctx);
      summary = summary(ctx, dc, description);
      body = blocks(build(events(ctx, dc.getFullBody(), description)));
    }

    Map<String, List<Doc.Block>> params = new LinkedHashMap<>();
    Map<String, List<Doc.Block>> typeParams = new LinkedHashMap<>();
    List<Doc.Block> returns = inlineReturn(ctx, dc);
    List<Thrown> exceptions = new ArrayList<>();
    List<Doc.Note> notes = new ArrayList<>();
    List<List<Doc.Inline>> see = new ArrayList<>();
    String since = null;
    List<Doc.Block> deprecated = null;
    boolean hidden = false;
    List<Doc.Block> customBlocks = new ArrayList<>();
    for (var tag : dc.getBlockTags()) {
      switch (tag) {
        case ParamTree param -> {
          var name = param.getName().getName().toString();
          Supplier<List<Event>> inherit =
              () -> inheritedParamEvents(ctx, name, param.isTypeParameter());
          var blocks = blocks(build(events(ctx, param.getDescription(), inherit)));
          (param.isTypeParameter() ? typeParams : params).put(name, blocks);
        }
        case ReturnTree ret ->
            returns =
                blocks(build(events(ctx, ret.getDescription(), () -> inheritedReturnEvents(ctx))));
        case ThrowsTree thrown -> {
          var exception = resolve(ctx, thrown.getExceptionName());
          var name = thrown.getExceptionName().getSignature();
          Supplier<List<Event>> inherit = () -> inheritedThrowsEvents(ctx, exception);
          exceptions.add(
              new Thrown(
                  exception, name, blocks(build(events(ctx, thrown.getDescription(), inherit)))));
        }
        case SeeTree seeTree -> see.add(seeEntry(ctx, seeTree));
        case SpecTree spec when !safeUrl(spec.getURL().getBody()) ->
            problem(
                ctx,
                Report.Problem.URL,
                tag,
                "Unsafe URL in @spec: only http, https, mailto and relative URLs are allowed");
        case SpecTree spec ->
            see.add(
                List.of(
                    new Doc.Link(
                        null,
                        spec.getURL().getBody(),
                        false,
                        inlines(build(events(ctx, spec.getTitle(), null))))));
        case SinceTree sinceTree ->
            since = text(blocks(build(events(ctx, sinceTree.getBody(), null))));
        case DeprecatedTree dep -> deprecated = blocks(build(events(ctx, dep.getBody(), null)));
        case UnknownBlockTagTree unknown when NOTE_TAGS.contains(unknown.getTagName()) ->
            notes.add(
                new Doc.Note(
                    Doc.NoteKind.valueOf(constant(unknown.getTagName())),
                    blocks(build(events(ctx, unknown.getContent(), null)))));
        default -> {
          if (tag.getKind() == DocTree.Kind.HIDDEN) {
            hidden = true;
          } else if (tag instanceof ErroneousTree erroneous) {
            // A block tag javadoc could not parse, such as @throws without a type. It has no
            // tag name.
            problem(
                ctx,
                Report.Problem.MALFORMED,
                tag,
                "Malformed documentation: " + erroneous.getBody());
          } else if (tag instanceof UnknownBlockTagTree unknown
              && customTags.containsKey(unknown.getTagName())) {
            var custom = customTags.get(unknown.getTagName());
            if (!custom.disabled()) {
              customBlocks.add(
                  new Doc.Paragraph(
                      List.of(new Doc.Strong(List.of(new Doc.Text(custom.header()))))));
              customBlocks.addAll(blocks(build(events(ctx, unknown.getContent(), null))));
            }
          } else if (NOT_MODELED.contains(tagName(tag))) {
            if (!foreign(ctx)) {
              report.warning(
                  path(ctx, tag), "@" + tagName(tag) + " is not part of the API model; left out");
            }
          } else {
            problem(
                ctx,
                Report.Problem.BLOCK_TAG,
                tag,
                "The API model cannot represent the block tag @" + tagName(tag));
          }
        }
      }
    }
    if (element instanceof ExecutableElement method) {
      fillInherited(method, params, exceptions);
      if (returns.isEmpty()) {
        var inherited = inheritedPart(method, p -> !p.returns().isEmpty());
        if (inherited != null) {
          returns = inherited.returns();
        }
      }
    }
    if (!customBlocks.isEmpty()) {
      body = new ArrayList<>(body);
      body.addAll(customBlocks);
    }
    var doc = new Doc(summary, body, notes, see, null);
    return new Parts(doc, params, typeParams, returns, exceptions, since, deprecated, hidden);
  }

  /**
   * Missing {@code @param} and declared-exception {@code @throws} parts, from overridden methods.
   */
  private void fillInherited(
      ExecutableElement method, Map<String, List<Doc.Block>> params, List<Thrown> exceptions) {
    for (var param : method.getParameters()) {
      var name = param.getSimpleName().toString();
      if (params.containsKey(name)) {
        continue;
      }
      for (var overridden : overridden(method)) {
        if (trees.getDocCommentTree(overridden) == null) {
          continue;
        }
        var inherited = renameParams(parts(overridden).params(), overridden, method);
        if (inherited.containsKey(name)) {
          params.put(name, inherited.get(name));
          break;
        }
      }
    }
    for (var declared : method.getThrownTypes()) {
      var element = types.asElement(declared);
      if (exceptions.stream().anyMatch(t -> element.equals(t.type()))) {
        continue;
      }
      for (var overridden : overridden(method)) {
        if (trees.getDocCommentTree(overridden) == null) {
          continue;
        }
        var match =
            parts(overridden).exceptions().stream()
                .filter(t -> element.equals(t.type()))
                .findFirst();
        if (match.isPresent()) {
          exceptions.add(match.get());
          break;
        }
      }
    }
  }

  /**
   * Parameter documentation keyed by the overriding method's parameter names (matched by position).
   */
  private static Map<String, List<Doc.Block>> renameParams(
      Map<String, List<Doc.Block>> params, ExecutableElement from, ExecutableElement to) {
    Map<String, List<Doc.Block>> renamed = new LinkedHashMap<>();
    var fromParams = from.getParameters();
    var toParams = to.getParameters();
    for (int i = 0; i < fromParams.size() && i < toParams.size(); i++) {
      var doc = params.get(fromParams.get(i).getSimpleName().toString());
      if (doc != null) {
        renamed.put(toParams.get(i).getSimpleName().toString(), doc);
      }
    }
    return renamed;
  }

  private Parts inheritedDescription(ExecutableElement method) {
    return inheritedPart(method, p -> p.doc() != null && !p.doc().body().isEmpty());
  }

  private Parts inheritedPart(ExecutableElement method, java.util.function.Predicate<Parts> test) {
    for (var overridden : overridden(method)) {
      if (trees.getDocCommentTree(overridden) != null) {
        var parts = parts(overridden);
        if (test.test(parts)) {
          return parts;
        }
      }
    }
    return null;
  }

  /**
   * The methods {@code method} overrides or implements: superclass chain first, then interfaces,
   * depth first.
   */
  List<ExecutableElement> overridden(ExecutableElement method) {
    if (method.getKind() != ElementKind.METHOD
        || method.getModifiers().contains(javax.lang.model.element.Modifier.STATIC)) {
      return List.of();
    }
    var owner = (TypeElement) method.getEnclosingElement();
    Set<TypeElement> supertypes = new LinkedHashSet<>();
    collectSupertypes(owner, supertypes);
    if (owner.getKind().isInterface()) {
      // An interface implicitly declares Object's public methods (JLS 9.2).
      var object = elements.getTypeElement("java.lang.Object");
      if (object != null) {
        supertypes.add(object);
      }
    }
    List<ExecutableElement> found = new ArrayList<>();
    for (var supertype : supertypes) {
      for (var candidate :
          javax.lang.model.util.ElementFilter.methodsIn(supertype.getEnclosedElements())) {
        if (elements.overrides(method, candidate, owner)) {
          found.add(candidate);
        }
      }
    }
    return found;
  }

  private void collectSupertypes(TypeElement type, Set<TypeElement> into) {
    List<TypeElement> direct = new ArrayList<>();
    if (type.getSuperclass() instanceof DeclaredType superclass) {
      direct.add((TypeElement) superclass.asElement());
    }
    for (var iface : type.getInterfaces()) {
      direct.add((TypeElement) ((DeclaredType) iface).asElement());
    }
    for (var supertype : direct) {
      if (into.add(supertype)) {
        collectSupertypes(supertype, into);
      }
    }
  }

  // ---- {@inheritDoc} sources: the events of the overridden method's corresponding part.

  private List<Event> inheritedBodyEvents(Ctx ctx) {
    if (ctx.owner() instanceof ExecutableElement method) {
      for (var overridden : overridden(method)) {
        var dc = trees.getDocCommentTree(overridden);
        if (dc != null && !dc.getFullBody().isEmpty()) {
          var octx = new Ctx(overridden, dc, trees.getPath(overridden));
          return events(octx, dc.getFullBody(), () -> inheritedBodyEvents(octx));
        }
      }
    }
    return null;
  }

  private List<Event> inheritedParamEvents(Ctx ctx, String name, boolean typeParam) {
    if (ctx.owner() instanceof ExecutableElement method) {
      int index = -1;
      if (!typeParam) {
        for (int i = 0; i < method.getParameters().size(); i++) {
          if (method.getParameters().get(i).getSimpleName().contentEquals(name)) {
            index = i;
          }
        }
      }
      for (var overridden : overridden(method)) {
        var dc = trees.getDocCommentTree(overridden);
        if (dc == null) {
          continue;
        }
        var target =
            typeParam
                ? name
                : index < 0
                    ? null
                    : overridden.getParameters().get(index).getSimpleName().toString();
        for (var tag : dc.getBlockTags()) {
          if (tag instanceof ParamTree param
              && param.isTypeParameter() == typeParam
              && param.getName().getName().contentEquals(target == null ? "" : target)) {
            var octx = new Ctx(overridden, dc, trees.getPath(overridden));
            return events(
                octx, param.getDescription(), () -> inheritedParamEvents(octx, target, typeParam));
          }
        }
      }
    }
    return null;
  }

  private List<Event> inheritedReturnEvents(Ctx ctx) {
    if (ctx.owner() instanceof ExecutableElement method) {
      for (var overridden : overridden(method)) {
        var dc = trees.getDocCommentTree(overridden);
        if (dc == null) {
          continue;
        }
        for (var tag : dc.getBlockTags()) {
          if (tag instanceof ReturnTree ret) {
            var octx = new Ctx(overridden, dc, trees.getPath(overridden));
            return events(octx, ret.getDescription(), () -> inheritedReturnEvents(octx));
          }
        }
      }
    }
    return null;
  }

  private List<Event> inheritedThrowsEvents(Ctx ctx, Element exception) {
    if (exception != null && ctx.owner() instanceof ExecutableElement method) {
      for (var overridden : overridden(method)) {
        var dc = trees.getDocCommentTree(overridden);
        if (dc == null) {
          continue;
        }
        var octx = new Ctx(overridden, dc, trees.getPath(overridden));
        for (var tag : dc.getBlockTags()) {
          if (tag instanceof ThrowsTree thrown
              && exception.equals(resolve(octx, thrown.getExceptionName()))) {
            return events(
                octx, thrown.getDescription(), () -> inheritedThrowsEvents(octx, exception));
          }
        }
      }
    }
    return null;
  }

  // ---- Summary, @return, @see.

  private List<Doc.Inline> summary(Ctx ctx, DocCommentTree dc, Supplier<List<Event>> inherit) {
    var first = dc.getFirstSentence();
    if (first.stream().anyMatch(t -> t.getKind() == DocTree.Kind.INHERIT_DOC)
        && ctx.owner() instanceof ExecutableElement method) {
      var inherited = inheritedDescription(method);
      return inherited == null ? List.of() : inherited.doc().summary();
    }
    return flatten(blocks(build(events(ctx, first, inherit))));
  }

  /** The description of an inline {@code {@return}} at the start of the body, or empty. */
  private List<Doc.Block> inlineReturn(Ctx ctx, DocCommentTree dc) {
    for (var tree : dc.getFullBody()) {
      if (tree instanceof ReturnTree ret && ret.isInline()) {
        return blocks(build(events(ctx, ret.getDescription(), () -> inheritedReturnEvents(ctx))));
      }
    }
    return List.of();
  }

  private List<Doc.Inline> seeEntry(Ctx ctx, SeeTree see) {
    var parts = see.getReference();
    if (!parts.isEmpty() && parts.getFirst() instanceof ReferenceTree ref) {
      return List.of(link(ctx, ref, parts.subList(1, parts.size()), true));
    }
    var first = parts.isEmpty() ? null : parts.getFirst();
    if (first instanceof TextTree text && text.getBody().startsWith("\"")) {
      return List.of(new Doc.Text(text.getBody()));
    }
    return flatten(blocks(build(events(ctx, parts, null))));
  }

  // ---- Events: the flat form of a list of doc trees.

  /** A comment's owner, its comment, and the owner's tree path (for resolving references). */
  private record Ctx(Element owner, DocCommentTree dc, TreePath path) {}

  /**
   * A comment the model cannot represent: an error in the documented code, so the run fails rather
   * than drop it; nothing for documentation inherited from outside the documented set (the JDK's
   * sources, for one), whose tags and references are another project's, so the part is left out.
   */
  private void problem(Ctx ctx, Report.Problem problem, DocTree tree, String message) {
    if (!foreign(ctx)) {
      report.problem(problem, path(ctx, tree), message);
    }
  }

  /** Whether the comment belongs to a declaration outside the documented set. */
  private boolean foreign(Ctx ctx) {
    Element element = ctx.owner();
    while (element != null
        && !(element instanceof TypeElement)
        && !(element instanceof javax.lang.model.element.PackageElement)) {
      element = element.getEnclosingElement();
    }
    return element != null && !linker.documented(element);
  }

  sealed interface Event permits TextEvent, StartEvent, EndEvent, InlineEvent, BlockEvent {}

  record TextEvent(String text) implements Event {}

  record StartEvent(String name, Map<String, String> attributes, boolean selfClosing)
      implements Event {}

  record EndEvent(String name) implements Event {}

  record InlineEvent(Doc.Inline inline) implements Event {}

  record BlockEvent(Doc.Block block) implements Event {}

  private List<Event> events(
      Ctx ctx, List<? extends DocTree> trees, Supplier<List<Event>> inherit) {
    if (trees.stream().anyMatch(t -> t.getKind() == DocTree.Kind.MARKDOWN)) {
      return markdown(ctx, trees, inherit);
    }
    List<Event> events = new ArrayList<>();
    for (var tree : trees) {
      switch (tree) {
        case ErroneousTree erroneous ->
            problem(
                ctx,
                Report.Problem.MALFORMED,
                tree,
                "Malformed documentation: " + erroneous.getBody());
        case EscapeTree escape -> events.add(new TextEvent(escape.getBody()));
        case TextTree text -> events.add(new TextEvent(text.getBody()));
        case EntityTree entity -> events.add(new TextEvent(entity(ctx, entity)));
        case StartElementTree start ->
            events.add(
                new StartEvent(
                    start.getName().toString().toLowerCase(Locale.ROOT),
                    attributes(ctx, start.getAttributes()),
                    start.isSelfClosing()));
        case EndElementTree end ->
            events.add(new EndEvent(end.getName().toString().toLowerCase(Locale.ROOT)));
        case LiteralTree literal ->
            events.add(
                new InlineEvent(
                    literal.getKind() == DocTree.Kind.CODE
                        ? new Doc.Code(literal.getBody().getBody())
                        : new Doc.Text(literal.getBody().getBody())));
        case LinkTree link ->
            events.add(
                new InlineEvent(
                    link(
                        ctx,
                        link.getReference(),
                        link.getLabel(),
                        link.getKind() == DocTree.Kind.LINK)));
        case ValueTree value -> events.add(new InlineEvent(value(ctx, value)));
        case SummaryTree summary -> events.addAll(events(ctx, summary.getSummary(), inherit));
        case ReturnTree ret when ret.isInline() -> {
          events.add(new TextEvent("Returns "));
          var description = events(ctx, ret.getDescription(), () -> inheritedReturnEvents(ctx));
          events.addAll(description);
          if (!endsWithPeriod(description)) {
            events.add(new TextEvent("."));
          }
        }
        case IndexTree index -> {
          var term = text(blocks(build(events(ctx, List.of(index.getSearchTerm()), null))));
          events.add(
              new TextEvent(
                  term.length() > 1 && term.startsWith("\"") && term.endsWith("\"")
                      ? term.substring(1, term.length() - 1)
                      : term));
        }
        case SystemPropertyTree property ->
            events.add(new InlineEvent(new Doc.Code(property.getPropertyName().toString())));
        case SnippetTree snippet -> {
          if (snippet.getBody() == null) {
            problem(
                ctx,
                Report.Problem.SNIPPET,
                tree,
                "The API model cannot represent an external {@snippet}; inline its body");
          } else {
            var lang = attributes(ctx, snippet.getAttributes()).get("lang");
            events.add(
                new BlockEvent(new Doc.CodeBlock(lang, dedent(snippet.getBody().getBody()))));
          }
        }
        case UnknownInlineTagTree unknown ->
            problem(
                ctx,
                Report.Problem.INLINE_TAG,
                tree,
                "The API model cannot represent the inline tag {@" + unknown.getTagName() + "}");
        default -> {
          switch (tree.getKind()) {
            case INHERIT_DOC -> {
              var inherited = inherit == null ? null : inherit.get();
              if (inherited != null) {
                events.addAll(inherited);
              } else if (inherit != null && ctx.owner() instanceof ExecutableElement method) {
                nothingToInherit(ctx, tree, method);
              } else {
                problem(
                    ctx,
                    Report.Problem.INHERIT_DOC,
                    tree,
                    "{@inheritDoc} finds nothing to inherit");
              }
            }
            case COMMENT, DOC_TYPE -> {
              // HTML comments and doctypes are not content.
            }
            default ->
                problem(
                    ctx,
                    Report.Problem.UNSUPPORTED,
                    tree,
                    "The API model cannot represent " + tree.getKind() + ": " + tree);
          }
        }
      }
    }
    return events;
  }

  /**
   * {@code {@inheritDoc}} in {@code method} found no documentation. As with the standard doclet,
   * the tag expands to nothing when every method it overrides is outside the documented set (an
   * override of {@code Object.equals}, say, without the JDK's sources on the source path): that
   * documentation is another project's, and javadoc cannot always see it. It is a problem when the
   * method overrides nothing, or when an overridden method in the documented set leaves the part
   * out.
   */
  private void nothingToInherit(Ctx ctx, DocTree tree, ExecutableElement method) {
    var overridden = overridden(method);
    if (overridden.isEmpty()) {
      problem(
          ctx,
          Report.Problem.INHERIT_DOC,
          tree,
          "{@inheritDoc} used, but "
              + method.getSimpleName()
              + " does not override or implement any method");
    } else if (overridden.stream().anyMatch(o -> linker.documented(o.getEnclosingElement()))) {
      problem(ctx, Report.Problem.INHERIT_DOC, tree, "{@inheritDoc} finds nothing to inherit");
    }
  }

  /**
   * A Markdown comment's trees ({@code ///}) as events: its tags convert as in an HTML comment,
   * each leaving a slot in the Markdown text, which {@link Markdown} parses and fills.
   */
  private List<Event> markdown(
      Ctx ctx, List<? extends DocTree> trees, Supplier<List<Event>> inherit) {
    var text = new StringBuilder();
    List<List<Event>> slots = new ArrayList<>();
    DocTree first = null;
    for (var tree : trees) {
      if (tree instanceof RawTextTree raw && tree.getKind() == DocTree.Kind.MARKDOWN) {
        if (first == null) {
          first = tree;
        }
        text.append(raw.getContent());
      } else {
        text.append(Markdown.slot(slots.size()));
        slots.add(events(ctx, List.of(tree), inherit));
      }
    }
    var at = first;
    return new Markdown(
            slots,
            index -> textContent(build(slots.get(index))),
            message -> problem(ctx, Report.Problem.URL, at, message))
        .parse(text.toString());
  }

  private static boolean endsWithPeriod(List<Event> events) {
    for (int i = events.size() - 1; i >= 0; i--) {
      if (events.get(i) instanceof TextEvent text) {
        var stripped = text.text().strip();
        if (!stripped.isEmpty()) {
          return stripped.endsWith(".");
        }
      } else {
        return false;
      }
    }
    return false;
  }

  static final Set<String> URL_ATTRIBUTES =
      Set.of("href", "src", "action", "formaction", "srcdoc", "data", "poster", "xlink:href");
  private static final Set<String> SAFE_SCHEMES = Set.of("http", "https", "mailto");

  /**
   * Whether a URL from a comment may reach a page: http, https, mailto or relative. Comments of
   * third-party code are untrusted; the renderer enforces the same rule. Entities are already
   * decoded here.
   */
  static boolean safeUrl(String url) {
    var cleaned = url.replaceFirst("^[\\u0000-\\u0020]+", "").replaceAll("[\\t\\n\\r]", "");
    if (cleaned.chars().anyMatch(c -> c < 0x20 || c == 0x7f) || cleaned.startsWith("\\")) {
      return false;
    }
    var scheme = java.util.regex.Pattern.compile("^([^:/?#]*):").matcher(cleaned);
    return !scheme.find() || SAFE_SCHEMES.contains(scheme.group(1).toLowerCase(Locale.ROOT));
  }

  private Map<String, String> attributes(Ctx ctx, List<? extends DocTree> attributes) {
    Map<String, String> map = new LinkedHashMap<>();
    for (var tree : attributes) {
      if (tree instanceof AttributeTree attribute) {
        var value = new StringBuilder();
        if (attribute.getValue() != null) {
          for (var part : attribute.getValue()) {
            switch (part) {
              case TextTree text -> value.append(text.getBody());
              case EntityTree entity -> value.append(entity(ctx, entity));
              default ->
                  problem(
                      ctx,
                      Report.Problem.HTML,
                      part,
                      "The API model cannot represent " + part.getKind() + " in an attribute");
            }
          }
        }
        var name = attribute.getName().toString().toLowerCase(Locale.ROOT);
        if (URL_ATTRIBUTES.contains(name) && !safeUrl(value.toString())) {
          problem(
              ctx,
              Report.Problem.URL,
              tree,
              "Unsafe URL in " + name + ": only http, https, mailto and relative URLs are allowed");
          continue;
        }
        map.put(name, value.toString());
      }
    }
    return map;
  }

  private String entity(Ctx ctx, EntityTree entity) {
    var name = entity.getName().toString();
    if (name.startsWith("#x") || name.startsWith("#X")) {
      return Character.toString(Integer.parseInt(name.substring(2), 16));
    }
    if (name.startsWith("#")) {
      return Character.toString(Integer.parseInt(name.substring(1)));
    }
    var text = ENTITIES.get(name);
    if (text == null) {
      problem(ctx, Report.Problem.HTML, entity, "Unknown HTML entity &" + name + ";");
      return "&" + name + ";";
    }
    return text;
  }

  private Element resolve(Ctx ctx, ReferenceTree ref) {
    var path = path(ctx, ref);
    return path == null ? null : trees.getElement(path);
  }

  private Doc.Inline link(Ctx ctx, ReferenceTree ref, List<? extends DocTree> label, boolean code) {
    var target = resolve(ctx, ref);
    var signature = ref.getSignature();
    var text = signature.startsWith("#") ? signature.substring(1) : signature.replace('#', '.');
    List<Doc.Inline> children =
        label.isEmpty()
            ? List.of(new Doc.Text(text))
            : flatten(blocks(build(events(ctx, label, null))));
    if (target == null) {
      problem(ctx, Report.Problem.REFERENCE, ref, "Cannot resolve the reference " + signature);
      return code ? new Doc.Code(text) : new Doc.Text(text);
    }
    return switch (target.getKind()) {
      case PACKAGE,
          CLASS,
          INTERFACE,
          ENUM,
          RECORD,
          ANNOTATION_TYPE,
          METHOD,
          CONSTRUCTOR,
          FIELD,
          ENUM_CONSTANT ->
          new Doc.Link(linker.ref(target, null), null, code, children);
      default -> {
        problem(
            ctx,
            Report.Problem.REFERENCE,
            ref,
            "The API model cannot link to a " + target.getKind() + ": " + signature);
        yield new Doc.Code(text);
      }
    };
  }

  private Doc.Inline value(Ctx ctx, ValueTree value) {
    if (value.getFormat() != null) {
      problem(ctx, Report.Problem.VALUE, value, "The API model cannot represent a {@value} format");
    }
    Element target =
        value.getReference() == null ? ctx.owner() : resolve(ctx, value.getReference());
    if (target instanceof VariableElement field && field.getConstantValue() != null) {
      var text = elements.getConstantExpression(field.getConstantValue());
      if (value.getReference() == null) {
        return new Doc.Code(text);
      }
      return new Doc.Link(linker.ref(field, null), null, true, List.of(new Doc.Code(text)));
    }
    problem(ctx, Report.Problem.VALUE, value, "{@value} names no compile-time constant");
    return new Doc.Code(value.toString());
  }

  private DocTreePath path(Ctx ctx, DocTree tree) {
    return ctx.path() == null ? null : DocTreePath.getPath(ctx.path(), ctx.dc(), tree);
  }

  private static String tagName(DocTree tag) {
    return switch (tag) {
      case com.sun.source.doctree.BlockTagTree block -> block.getTagName();
      default -> tag.getKind().tagName;
    };
  }

  /** Parses authored Markdown with the same rules used for Markdown comments. */
  static List<Doc.Block> guideBody(
      String markdown, java.util.function.Function<String, Api.Ref> resolve) {
    var events =
        new Markdown(
                List.of(),
                i -> "",
                message -> {
                  throw new IllegalArgumentException(message);
                })
            .parse(markdown);
    var nodes = build(events);
    resolveGuideLinks(nodes, resolve);
    return blocks(nodes);
  }

  private static void resolveGuideLinks(
      List<Node> nodes, java.util.function.Function<String, Api.Ref> resolve) {
    for (var iterator = nodes.listIterator(); iterator.hasNext(); ) {
      if (iterator.next() instanceof ElementNode element) {
        resolveGuideLinks(element.children(), resolve);
        if (element.name().equals("a") && element.attributes().containsKey("href")) {
          var target = resolve.apply(element.attributes().get("href"));
          if (target != null)
            iterator.set(
                new InlineNode(new Doc.Link(target, null, false, inlines(element.children()))));
        }
      }
    }
  }

  // ---- The element tree.

  private sealed interface Node permits TextNode, ElementNode, InlineNode, BlockNode {}

  private record TextNode(String text) implements Node {}

  private record ElementNode(String name, Map<String, String> attributes, List<Node> children)
      implements Node {}

  private record InlineNode(Doc.Inline inline) implements Node {}

  private record BlockNode(Doc.Block block) implements Node {}

  /**
   * The events as an element tree, closing elements as HTML does implicitly. Returns the root's
   * children.
   */
  private static List<Node> build(List<Event> events) {
    var root = new ElementNode("#root", Map.of(), new ArrayList<>());
    List<ElementNode> stack = new ArrayList<>();
    stack.add(root);
    for (var event : events) {
      switch (event) {
        case TextEvent text -> stack.getLast().children().add(new TextNode(text.text()));
        case InlineEvent inline -> stack.getLast().children().add(new InlineNode(inline.inline()));
        case BlockEvent block -> {
          closeParagraph(stack);
          stack.getLast().children().add(new BlockNode(block.block()));
        }
        case StartEvent start -> {
          var name = start.name();
          if (BLOCKS.contains(name)) {
            closeParagraph(stack);
          }
          switch (name) {
            case "li" -> closeUntil(stack, Set.of("li"), Set.of("ul", "ol"));
            case "dt", "dd" -> closeUntil(stack, Set.of("dt", "dd"), Set.of("dl"));
            case "tr" ->
                closeUntil(
                    stack, Set.of("tr", "td", "th"), Set.of("table", "thead", "tbody", "tfoot"));
            case "td", "th" -> closeUntil(stack, Set.of("td", "th"), Set.of("tr", "table"));
            case "thead", "tbody", "tfoot" ->
                closeUntil(
                    stack, Set.of("thead", "tbody", "tfoot", "tr", "td", "th"), Set.of("table"));
            default -> {}
          }
          var element = new ElementNode(name, start.attributes(), new ArrayList<>());
          stack.getLast().children().add(element);
          if (!start.selfClosing() && !VOID.contains(name)) {
            stack.add(element);
          }
        }
        case EndEvent end -> {
          for (int i = stack.size() - 1; i > 0; i--) {
            if (stack.get(i).name().equals(end.name())) {
              while (stack.size() > i) {
                stack.removeLast();
              }
              break;
            }
          }
        }
      }
    }
    return root.children();
  }

  private static void closeParagraph(List<ElementNode> stack) {
    while (stack.size() > 1 && stack.getLast().name().equals("p")) {
      stack.removeLast();
    }
  }

  /** Pops open {@code closes} elements down to the nearest {@code scope} element. */
  private static void closeUntil(List<ElementNode> stack, Set<String> closes, Set<String> scope) {
    for (int i = stack.size() - 1; i > 0; i--) {
      var name = stack.get(i).name();
      if (scope.contains(name)) {
        return;
      }
      if (closes.contains(name)) {
        while (stack.size() > i) {
          stack.removeLast();
        }
        return;
      }
    }
  }

  // ---- Element tree to blocks and inlines.

  private static List<Doc.Block> blocks(List<Node> nodes) {
    List<Doc.Block> blocks = new ArrayList<>();
    List<Doc.Inline> pending = new ArrayList<>();
    for (var node : nodes) {
      switch (node) {
        case BlockNode block -> {
          flush(pending, blocks);
          blocks.add(block.block());
        }
        case ElementNode element when BLOCKS.contains(element.name()) -> {
          flush(pending, blocks);
          block(element, blocks);
        }
        default -> pending.addAll(inlines(List.of(node)));
      }
    }
    flush(pending, blocks);
    return blocks;
  }

  private static void flush(List<Doc.Inline> pending, List<Doc.Block> blocks) {
    var inlines = normalize(pending);
    if (!inlines.isEmpty()) {
      blocks.add(new Doc.Paragraph(inlines));
    }
    pending.clear();
  }

  private static void block(ElementNode element, List<Doc.Block> into) {
    var kids = element.children();
    switch (element.name()) {
      case "p", "li", "dd", "dt", "td", "th", "tr", "thead", "tbody", "tfoot", "caption" ->
          into.addAll(blocks(kids));
      case "ul", "ol" -> {
        List<List<Doc.Block>> items = new ArrayList<>();
        for (var kid : kids) {
          if (kid instanceof ElementNode li && li.name().equals("li")) {
            items.add(blocks(li.children()));
          } else if (!(kid instanceof TextNode text && text.text().isBlank())) {
            items.add(blocks(List.of(kid)));
          }
        }
        into.add(new Doc.ListBlock(element.name().equals("ol"), items));
      }
      case "dl" -> {
        List<List<Doc.Block>> items = new ArrayList<>();
        for (var kid : kids) {
          if (kid instanceof ElementNode dt && dt.name().equals("dt")) {
            List<Doc.Block> item = new ArrayList<>();
            var term = normalize(inlines(dt.children()));
            item.add(new Doc.Paragraph(List.of(new Doc.Strong(term))));
            items.add(item);
          } else if (kid instanceof ElementNode dd && dd.name().equals("dd")) {
            if (items.isEmpty()) {
              items.add(new ArrayList<>());
            }
            items.getLast().addAll(blocks(dd.children()));
          } else if (!(kid instanceof TextNode text && text.text().isBlank())) {
            if (items.isEmpty()) {
              items.add(new ArrayList<>());
            }
            items.getLast().addAll(blocks(List.of(kid)));
          }
        }
        into.add(new Doc.ListBlock(false, items.stream().map(List::copyOf).toList()));
      }
      case "pre" -> {
        var language = language(element);
        into.add(new Doc.CodeBlock(language, dedent(textContent(kids))));
      }
      case "h1", "h2", "h3", "h4", "h5", "h6" ->
          into.add(new Doc.Heading(element.name().charAt(1) - '0', normalize(inlines(kids))));
      case "blockquote" -> into.add(new Doc.Quote(blocks(kids)));
      case "table" -> into.add(table(element));
      case "hr" -> into.add(new Doc.RawBlock("<hr>", ""));
      default -> {
        if (TRANSPARENT.contains(element.name())) {
          into.addAll(blocks(kids));
        } else {
          into.add(new Doc.RawBlock(html(element), textContent(kids)));
        }
      }
    }
  }

  private static String language(ElementNode pre) {
    var language = classLanguage(pre.attributes().get("class"));
    if (language == null
        && pre.children().size() == 1
        && pre.children().getFirst() instanceof ElementNode code
        && code.name().equals("code")) {
      language = classLanguage(code.attributes().get("class"));
    }
    if (language == null) {
      language = pre.attributes().get("lang");
    }
    return language;
  }

  private static String classLanguage(String classes) {
    if (classes != null) {
      for (var name : classes.split("\\s+")) {
        if (name.startsWith("language-")) {
          return name.substring("language-".length());
        }
      }
    }
    return null;
  }

  private static Doc.Table table(ElementNode table) {
    List<Doc.Inline> caption = List.of();
    List<List<List<Doc.Block>>> head = new ArrayList<>();
    List<List<List<Doc.Block>>> rows = new ArrayList<>();
    for (var kid : table.children()) {
      if (!(kid instanceof ElementNode element)) {
        continue;
      }
      switch (element.name()) {
        case "caption" -> caption = normalize(inlines(element.children()));
        case "thead" -> rows(element, head);
        case "tbody", "tfoot" -> rows(element, rows);
        case "tr" -> rows.add(row(element));
        default -> rows.add(List.of(blocks(List.of(element))));
      }
    }
    if (head.isEmpty() && !rows.isEmpty() && headerRow(table, rows)) {
      head.add(rows.removeFirst());
    }
    return new Doc.Table(caption, head, rows);
  }

  /**
   * Whether the table's first row is all {@code <th>} cells (a header row outside {@code <thead>}).
   */
  private static boolean headerRow(ElementNode table, List<List<List<Doc.Block>>> rows) {
    for (var kid : table.children()) {
      if (kid instanceof ElementNode element) {
        var row =
            element.name().equals("tr")
                ? element
                : element.name().equals("tbody")
                    ? element.children().stream()
                        .filter(n -> n instanceof ElementNode e && e.name().equals("tr"))
                        .map(ElementNode.class::cast)
                        .findFirst()
                        .orElse(null)
                    : null;
        if (row == null) {
          continue;
        }
        return row.children().stream()
            .filter(ElementNode.class::isInstance)
            .allMatch(n -> ((ElementNode) n).name().equals("th"));
      }
    }
    return false;
  }

  private static void rows(ElementNode section, List<List<List<Doc.Block>>> into) {
    for (var kid : section.children()) {
      if (kid instanceof ElementNode tr && tr.name().equals("tr")) {
        into.add(row(tr));
      }
    }
  }

  private static List<List<Doc.Block>> row(ElementNode tr) {
    List<List<Doc.Block>> cells = new ArrayList<>();
    for (var kid : tr.children()) {
      if (kid instanceof ElementNode cell
          && (cell.name().equals("td") || cell.name().equals("th"))) {
        cells.add(blocks(cell.children()));
      }
    }
    return cells;
  }

  private static List<Doc.Inline> inlines(List<Node> nodes) {
    List<Doc.Inline> inlines = new ArrayList<>();
    for (var node : nodes) {
      switch (node) {
        case TextNode text -> inlines.add(new Doc.Text(text.text()));
        case InlineNode inline -> inlines.add(inline.inline());
        case BlockNode block -> inlines.addAll(flatten(List.of(block.block())));
        case ElementNode element -> inline(element, inlines);
      }
    }
    return inlines;
  }

  private static void inline(ElementNode element, List<Doc.Inline> into) {
    var kids = element.children();
    var name = element.name();
    if (CODE.contains(name)) {
      if (kids.stream().allMatch(TextNode.class::isInstance)) {
        into.add(new Doc.Code(textContent(kids)));
      } else {
        for (var inline : inlines(kids)) {
          into.add(
              switch (inline) {
                case Doc.Text text -> new Doc.Code(text.text());
                case Doc.Link link ->
                    new Doc.Link(link.target(), link.url(), true, link.children());
                default -> inline;
              });
        }
      }
    } else if (EMPHASIS.contains(name)) {
      into.add(new Doc.Emphasis(inlines(kids)));
    } else if (STRONG.contains(name)) {
      into.add(new Doc.Strong(inlines(kids)));
    } else if (name.equals("br")) {
      into.add(new Doc.Break());
    } else if (name.equals("a") && element.attributes().containsKey("href")) {
      into.add(new Doc.Link(null, element.attributes().get("href"), false, inlines(kids)));
    } else if (BLOCKS.contains(name)) {
      into.addAll(inlines(kids));
    } else {
      into.add(new Doc.RawInline(html(element), textContent(kids)));
    }
  }

  /** Merges adjacent text, collapses whitespace, and trims the run's ends. */
  private static List<Doc.Inline> normalize(List<Doc.Inline> inlines) {
    List<Doc.Inline> merged = new ArrayList<>();
    for (var inline : inlines) {
      var next =
          switch (inline) {
            case Doc.Text text -> new Doc.Text(text.text().replaceAll("\\s+", " "));
            case Doc.Emphasis em -> new Doc.Emphasis(collapse(em.children()));
            case Doc.Strong strong -> new Doc.Strong(collapse(strong.children()));
            case Doc.Link link ->
                new Doc.Link(link.target(), link.url(), link.code(), collapse(link.children()));
            default -> inline;
          };
      if (next instanceof Doc.Text text
          && !merged.isEmpty()
          && merged.getLast() instanceof Doc.Text last) {
        merged.set(
            merged.size() - 1, new Doc.Text((last.text() + text.text()).replaceAll("\\s+", " ")));
      } else {
        merged.add(next);
      }
    }
    if (!merged.isEmpty() && merged.getFirst() instanceof Doc.Text first) {
      merged.set(0, new Doc.Text(first.text().stripLeading()));
    }
    if (!merged.isEmpty() && merged.getLast() instanceof Doc.Text last) {
      merged.set(merged.size() - 1, new Doc.Text(last.text().stripTrailing()));
    }
    merged.removeIf(i -> i instanceof Doc.Text text && text.text().isEmpty());
    return merged;
  }

  /** Whitespace collapsed inside nested content, without trimming. */
  private static List<Doc.Inline> collapse(List<Doc.Inline> inlines) {
    var normalized = normalize(inlines);
    // normalize trims the run's ends; restore a single space where the source had one.
    if (!inlines.isEmpty()
        && inlines.getFirst() instanceof Doc.Text t
        && !t.text().isEmpty()
        && Character.isWhitespace(t.text().charAt(0))
        && !normalized.isEmpty()
        && normalized.getFirst() instanceof Doc.Text n) {
      normalized = new ArrayList<>(normalized);
      normalized.set(0, new Doc.Text(" " + n.text()));
    }
    return normalized;
  }

  /** Blocks as one run of inlines (a summary, a label), paragraphs joined by a space. */
  private static List<Doc.Inline> flatten(List<Doc.Block> blocks) {
    List<Doc.Inline> inlines = new ArrayList<>();
    for (var block : blocks) {
      if (!inlines.isEmpty()) {
        inlines.add(new Doc.Text(" "));
      }
      switch (block) {
        case Doc.Paragraph p -> inlines.addAll(p.children());
        case Doc.Heading h -> inlines.addAll(h.children());
        case Doc.CodeBlock c -> inlines.add(new Doc.Code(c.text()));
        case Doc.RawBlock r -> inlines.add(new Doc.RawInline(r.html(), r.text()));
        case Doc.ListBlock l -> l.items().forEach(item -> inlines.addAll(flatten(item)));
        case Doc.Quote q -> inlines.addAll(flatten(q.children()));
        case Doc.Table t -> inlines.add(new Doc.Text(text(List.of(t))));
      }
    }
    return normalize(inlines);
  }

  /** Plain text of blocks ({@code @since}). */
  private static String text(List<Doc.Block> blocks) {
    var text = new StringBuilder();
    for (var inline : flatten(blocks)) {
      text.append(inlineText(inline));
    }
    return text.toString().strip();
  }

  private static String inlineText(Doc.Inline inline) {
    return switch (inline) {
      case Doc.Text t -> t.text();
      case Doc.Code c -> c.text();
      case Doc.Emphasis e ->
          e.children().stream().map(Comments::inlineText).reduce("", String::concat);
      case Doc.Strong s ->
          s.children().stream().map(Comments::inlineText).reduce("", String::concat);
      case Doc.Link l -> l.children().stream().map(Comments::inlineText).reduce("", String::concat);
      case Doc.Break b -> "\n";
      case Doc.RawInline r -> r.text();
    };
  }

  private static String textContent(List<Node> nodes) {
    var text = new StringBuilder();
    for (var node : nodes) {
      switch (node) {
        case TextNode t -> text.append(t.text());
        case InlineNode i -> text.append(inlineText(i.inline()));
        case BlockNode b -> text.append(text(List.of(b.block())));
        case ElementNode e -> text.append(e.name().equals("br") ? "\n" : textContent(e.children()));
      }
    }
    return text.toString();
  }

  /** An element as markup, for a raw node. */
  private static String html(ElementNode element) {
    var html = new StringBuilder("<").append(element.name());
    element
        .attributes()
        .forEach((k, v) -> html.append(' ').append(k).append("=\"").append(escape(v)).append('"'));
    html.append('>');
    if (VOID.contains(element.name())) {
      return html.toString();
    }
    for (var kid : element.children()) {
      switch (kid) {
        case TextNode t -> html.append(escape(t.text()));
        case InlineNode i when i.inline() instanceof Doc.Code c ->
            html.append("<code>").append(escape(c.text())).append("</code>");
        case InlineNode i -> html.append(escape(inlineText(i.inline())));
        case BlockNode b -> html.append(escape(text(List.of(b.block()))));
        case ElementNode e -> html.append(html(e));
      }
    }
    return html.append("</").append(element.name()).append('>').toString();
  }

  private static String escape(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  /** Code text without its first newline, trailing whitespace, or common indentation. */
  static String dedent(String code) {
    var text =
        code.startsWith("\n")
            ? code.substring(1)
            : code.startsWith("\r\n") ? code.substring(2) : code;
    var lines = text.stripTrailing().split("\n", -1);
    int indent = Integer.MAX_VALUE;
    for (var line : lines) {
      if (!line.isBlank()) {
        indent = Math.min(indent, line.length() - line.stripLeading().length());
      }
    }
    if (indent == Integer.MAX_VALUE) {
      indent = 0;
    }
    var out = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) {
        out.append('\n');
      }
      var line = lines[i];
      out.append(line.length() >= indent ? line.substring(indent).stripTrailing() : line.strip());
    }
    return out.toString();
  }

  /** {@code apiNote} as {@code API_NOTE}. */
  private static String constant(String camel) {
    return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
  }
}
