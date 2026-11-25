/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.doclet;

import com.sun.source.util.DocTrees;
import dev.elide.apidocs.model.Api;
import dev.elide.apidocs.model.Api.MemberKind;
import dev.elide.apidocs.model.Api.Token;
import dev.elide.apidocs.model.Doc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import jdk.javadoc.doclet.DocletEnvironment;

/**
 * Builds the model from javadoc's view of the documented sources: the included packages, their
 * included types (nested ones too) and their included members, as javadoc's access options select
 * them. {@code @hidden} declarations are left out.
 */
final class Extractor {
  private static final Set<Modifier> NOT_SHOWN =
      EnumSet.of(Modifier.SYNCHRONIZED, Modifier.NATIVE, Modifier.STRICTFP);
  private static final String NULL_MARKED = "org.jspecify.annotations.NullMarked";
  private static final String NULL_UNMARKED = "org.jspecify.annotations.NullUnmarked";

  private final DocletEnvironment env;
  private final DocTrees trees;
  private final Elements elements;
  private final Types types;
  private final Linker linker;
  private final Comments comments;
  private final Report report;
  private boolean protectedMembers;

  Extractor(DocletEnvironment env, Linker linker, Report report) {
    this(env, linker, report, Map.of());
  }

  Extractor(
      DocletEnvironment env,
      Linker linker,
      Report report,
      Map<String, Comments.CustomTag> customTags) {
    this.env = env;
    this.trees = env.getDocTrees();
    this.elements = env.getElementUtils();
    this.types = env.getTypeUtils();
    this.linker = linker;
    this.report = report;
    this.comments = new Comments(trees, elements, types, linker, report, customTags);
  }

  private Tokens tokens() {
    return Tokens.builder(linker, elements, report);
  }

  Api.Model extract(String moduleName, String version, List<Api.External> externals) {
    Map<String, List<TypeElement>> byPackage = new TreeMap<>();
    Map<String, PackageElement> packages = new TreeMap<>();
    for (var type : ElementFilter.typesIn(env.getIncludedElements())) {
      if (type.getNestingKind().isNested() || !env.isIncluded(type) || comments.hidden(type)) {
        continue;
      }
      var pkg = elements.getPackageOf(type);
      packages.put(pkg.getQualifiedName().toString(), pkg);
      collect(
          type,
          byPackage.computeIfAbsent(pkg.getQualifiedName().toString(), k -> new ArrayList<>()));
    }
    for (var pkg : ElementFilter.packagesIn(env.getIncludedElements())) {
      if (!byPackage.containsKey(pkg.getQualifiedName().toString())
          && trees.getDocCommentTree(pkg) != null) {
        packages.put(pkg.getQualifiedName().toString(), pkg);
        byPackage.put(pkg.getQualifiedName().toString(), new ArrayList<>());
      }
    }
    // Register every documented id first, so references between them stay local.
    for (var pkg : packages.values()) {
      linker.document(linker.id(pkg));
    }
    for (var list : byPackage.values()) {
      for (var type : list) {
        linker.document(linker.id(type));
        for (var member : members(type)) {
          linker.document(linker.id(member));
          protectedMembers |= member.getModifiers().contains(Modifier.PROTECTED);
        }
      }
    }
    List<Api.Package> models = new ArrayList<>();
    for (var entry : byPackage.entrySet()) {
      var pkg = packages.get(entry.getKey());
      var list = new ArrayList<>(entry.getValue());
      list.sort(Comparator.comparing(linker::typeName));
      List<Api.Type> typeModels = new ArrayList<>();
      for (var type : list) {
        typeModels.add(type(type));
      }
      var parts = comments.parts(pkg);
      models.add(
          new Api.Package(
              entry.getKey(),
              entry.getKey(),
              nonEmpty(parts.doc()),
              parts.since(),
              deprecation(pkg, parts),
              annotated(pkg, NULL_MARKED),
              typeModels,
              List.of(),
              List.of(),
              List.of()));
    }
    return new Api.Model(
        Api.SCHEMA,
        new Api.Module(moduleName, version, javaModule(packages.values()), List.of("java"), null),
        externals,
        models);
  }

  /**
   * The named JPMS module every documented package belongs to, or {@code null} for the unnamed
   * module.
   */
  private String javaModule(java.util.Collection<PackageElement> packages) {
    Set<String> names = new java.util.TreeSet<>();
    for (var pkg : packages) {
      var module = elements.getModuleOf(pkg);
      names.add(module == null || module.isUnnamed() ? "" : module.getQualifiedName().toString());
    }
    if (names.size() > 1) {
      report.error(
          (Element) null,
          "The documented packages span several modules "
              + names
              + "; document one module per run");
    }
    var name = names.isEmpty() ? "" : names.iterator().next();
    return name.isEmpty() ? null : name;
  }

  private void collect(TypeElement type, List<TypeElement> into) {
    into.add(type);
    for (var nested : ElementFilter.typesIn(type.getEnclosedElements())) {
      if (env.isIncluded(nested) && !comments.hidden(nested)) {
        collect(nested, into);
      }
    }
  }

  /** A type's documented members, in declaration order. */
  private List<Element> members(TypeElement type) {
    List<Element> members = new ArrayList<>();
    for (var member : type.getEnclosedElements()) {
      switch (member.getKind()) {
        case CONSTRUCTOR, METHOD, FIELD, ENUM_CONSTANT -> {
          if (env.isIncluded(member) && !comments.hidden(member)) {
            members.add(member);
          }
        }
        default -> {}
      }
    }
    return members;
  }

  private Api.Type type(TypeElement type) {
    var parts = comments.parts(type);
    var kind =
        switch (type.getKind()) {
          case CLASS -> Api.TypeKind.CLASS;
          case INTERFACE -> Api.TypeKind.INTERFACE;
          case ENUM -> Api.TypeKind.ENUM;
          case RECORD -> Api.TypeKind.RECORD;
          case ANNOTATION_TYPE -> Api.TypeKind.ANNOTATION;
          default -> throw new IllegalStateException("Not a type: " + type);
        };
    var modifiers = modifiers(type);

    var signature = tokens();
    modifiers.forEach(m -> signature.keyword(m).punct(" "));
    signature
        .keyword(
            switch (kind) {
              case ANNOTATION -> "@interface";
              default -> kind.name().toLowerCase(java.util.Locale.ROOT);
            })
        .punct(" ");
    signature.name(linker.typeName(type)).typeParams(type.getTypeParameters());

    List<Api.Param> components = new ArrayList<>();
    if (kind == Api.TypeKind.RECORD) {
      signature.punct("(");
      var recordComponents = type.getRecordComponents();
      for (int i = 0; i < recordComponents.size(); i++) {
        var component = recordComponents.get(i);
        if (i > 0) {
          signature.punct(", ");
        }
        var name = component.getSimpleName().toString();
        var componentType = tokens().type(component.asType()).build();
        signature
            .annotations(component.getAnnotationMirrors(), " ")
            .all(componentType)
            .punct(" ")
            .param(name);
        components.add(
            new Api.Param(
                name,
                componentType,
                false,
                null,
                nullness(component.asType(), type),
                tokens().annotationList(component),
                parts.params().getOrDefault(name, List.of())));
      }
      signature.punct(")");
      for (var name : parts.params().keySet()) {
        if (recordComponents.stream().noneMatch(c -> c.getSimpleName().contentEquals(name))) {
          report.problem(
              Report.Problem.PARAM, type, "@param " + name + " names no record component");
        }
      }
    }

    List<Token> superclass = List.of();
    List<Api.Ref> ancestors = new ArrayList<>();
    if (type.getSuperclass() instanceof DeclaredType declared) {
      superclass = tokens().type(declared).build();
      if (kind == Api.TypeKind.CLASS && !isObject(declared)) {
        signature.punct(" ").keyword("extends").punct(" ").all(superclass);
      }
      for (TypeMirror t = declared;
          t instanceof DeclaredType d;
          t = ((TypeElement) d.asElement()).getSuperclass()) {
        ancestors.add(linker.ref(d.asElement(), null));
      }
    }
    List<List<Token>> interfaces = new ArrayList<>();
    if (kind != Api.TypeKind.ANNOTATION) {
      // An annotation interface's java.lang.annotation.Annotation supertype is implicit.
      for (var iface : type.getInterfaces()) {
        interfaces.add(tokens().type(iface).build());
      }
    }
    if (!interfaces.isEmpty()) {
      var keyword = kind == Api.TypeKind.INTERFACE ? "extends" : "implements";
      signature.punct(" ").keyword(keyword).punct(" ");
      for (int i = 0; i < interfaces.size(); i++) {
        if (i > 0) {
          signature.punct(", ");
        }
        signature.all(interfaces.get(i));
      }
    }
    List<Api.Ref> permits = new ArrayList<>();
    if (!type.getPermittedSubclasses().isEmpty() && kind != Api.TypeKind.ENUM) {
      signature.punct(" ").keyword("permits").punct(" ");
      var permitted = type.getPermittedSubclasses();
      for (int i = 0; i < permitted.size(); i++) {
        if (i > 0) {
          signature.punct(", ");
        }
        var element = (TypeElement) types.asElement(permitted.get(i));
        signature.typeName(element);
        permits.add(linker.ref(element, null));
      }
    }

    List<Api.Member> members = new ArrayList<>();
    for (var member : members(type)) {
      members.add(member(type, member, parts));
    }
    return new Api.Type(
        linker.typeId(type),
        linker.typeName(type),
        kind,
        "java",
        modifiers,
        tokens().annotationList(type),
        typeParams(type.getTypeParameters(), parts),
        signature.build(),
        superclass,
        interfaces,
        ancestors,
        permits,
        type.getEnclosingElement() instanceof TypeElement outer ? linker.typeId(outer) : null,
        components,
        members,
        kind == Api.TypeKind.ANNOTATION ? List.of() : inherited(type),
        nonEmpty(parts.doc()),
        deprecation(type, parts),
        parts.since(),
        nullMarked(type),
        source(type));
  }

  private static boolean isObject(DeclaredType type) {
    return ((TypeElement) type.asElement()).getQualifiedName().contentEquals("java.lang.Object");
  }

  private Api.Member member(TypeElement owner, Element member, Comments.Parts ownerParts) {
    var implicit = elements.getOrigin(member) != Elements.Origin.EXPLICIT;
    var parts = comments.parts(member);
    var modifiers = modifiers(member);
    var annotations = tokens().annotationList(member);
    var signature = tokens();
    modifiers.forEach(m -> signature.keyword(m).punct(" "));
    if (member instanceof VariableElement field) {
      var kind =
          member.getKind() == ElementKind.ENUM_CONSTANT
              ? MemberKind.ENUM_CONSTANT
              : MemberKind.FIELD;
      var type = tokens().type(field.asType()).build();
      if (kind == MemberKind.FIELD) {
        signature.all(type).punct(" ");
      }
      signature.name(field.getSimpleName().toString());
      return new Api.Member(
          linker.id(member),
          linker.anchor(member),
          kind,
          field.getSimpleName().toString(),
          modifiers,
          annotations,
          List.of(),
          null,
          signature.build(),
          List.of(),
          null,
          type,
          kind == MemberKind.FIELD ? nullness(field.asType(), field) : null,
          List.of(),
          kind == MemberKind.FIELD ? tokens().constant(field) : null,
          null,
          null,
          implicit,
          nonEmpty(parts.doc()),
          deprecation(member, parts),
          parts.since(),
          implicit ? null : source(member));
    }
    var method = (ExecutableElement) member;
    var description = nonEmpty(parts.doc());
    var constructor = method.getKind() == ElementKind.CONSTRUCTOR;
    var annotationElement = owner.getKind() == ElementKind.ANNOTATION_TYPE;
    var name = constructor ? owner.getSimpleName().toString() : method.getSimpleName().toString();
    signature.typeParams(method.getTypeParameters());
    if (!method.getTypeParameters().isEmpty()) {
      signature.punct(" ");
    }
    Api.Returns returns = null;
    List<Doc.Block> returnDoc = parts.returns();
    if (returnDoc.isEmpty() && accessor(owner, method)) {
      // A record's accessor, implicit or explicit without @return, returns its component,
      // documented by the
      // record's @param (the strictness scan accepts that in place of @return).
      returnDoc = ownerParts.params().getOrDefault(name, List.of());
    }
    Map<String, List<Doc.Block>> paramDocs = new LinkedHashMap<>(parts.params());
    if (description == null) {
      // javac exposes mandated record and enum methods without their standard Javadoc comments.
      // A record's component comment also describes an explicitly implemented accessor.
      if (accessor(owner, method) && !returnDoc.isEmpty()) {
        description = description(returnDoc);
      } else if (constructor
          && (implicit || (owner.getKind() == ElementKind.RECORD && canonical(owner, method)))) {
        description =
            description(sentence("Creates a new " + owner.getSimpleName() + " instance."));
      } else if (!constructor && source(method) == null) {
        var contract = generatedContract(owner, name);
        if (contract != null) {
          returnDoc = sentence(contract);
          description = description(returnDoc);
          if (!method.getParameters().isEmpty()) {
            var parameter = method.getParameters().getFirst().getSimpleName().toString();
            paramDocs.put(
                parameter,
                sentence(
                    owner.getKind() == ElementKind.ENUM
                        ? "The exact name of the enum constant."
                        : "The object to compare with this record."));
          }
        }
      }
    }
    if (!constructor) {
      var returnType = tokens().type(method.getReturnType()).build();
      signature.all(returnType).punct(" ");
      if (method.getReturnType().getKind() != TypeKind.VOID) {
        returns = new Api.Returns(returnType, nullness(method.getReturnType(), method), returnDoc);
      }
    }
    signature.name(name).punct("(");
    List<Api.Param> params = new ArrayList<>();
    var parameters = method.getParameters();
    boolean canonical =
        owner.getKind() == ElementKind.RECORD && constructor && canonical(owner, method);
    for (int i = 0; i < parameters.size(); i++) {
      var parameter = parameters.get(i);
      var varargs = method.isVarArgs() && i == parameters.size() - 1;
      var paramName = parameter.getSimpleName().toString();
      var type = tokens().type(parameter.asType(), varargs).build();
      if (i > 0) {
        signature.punct(", ");
      }
      signature
          .annotations(parameter.getAnnotationMirrors(), " ")
          .all(type)
          .punct(" ")
          .param(paramName);
      var doc = paramDocs.get(paramName);
      if (doc == null && canonical) {
        doc = ownerParts.params().get(paramName);
      }
      params.add(
          new Api.Param(
              paramName,
              type,
              varargs,
              null,
              nullness(parameter.asType(), method),
              tokens().annotationList(parameter),
              doc == null ? List.of() : doc));
    }
    for (var documented : parts.params().keySet()) {
      if (parameters.stream().noneMatch(p -> p.getSimpleName().contentEquals(documented))) {
        report.problem(
            Report.Problem.PARAM, member, "@param " + documented + " names no parameter");
      }
    }
    signature.punct(")");
    String defaultValue = null;
    if (annotationElement && method.getDefaultValue() != null) {
      defaultValue = method.getDefaultValue().toString();
      signature.punct(" ").keyword("default").punct(" ").literal(defaultValue);
    }
    var exceptions = exceptions(method, parts);
    var declared = method.getThrownTypes();
    if (!declared.isEmpty()) {
      signature.punct(" ").keyword("throws").punct(" ");
      for (int i = 0; i < declared.size(); i++) {
        if (i > 0) {
          signature.punct(", ");
        }
        signature.type(declared.get(i));
      }
    }
    var overridden = comments.overridden(method);
    return new Api.Member(
        linker.id(member),
        linker.anchor(member),
        constructor
            ? MemberKind.CONSTRUCTOR
            : annotationElement ? MemberKind.ANNOTATION_ELEMENT : MemberKind.METHOD,
        name,
        modifiers,
        annotations,
        typeParams(method.getTypeParameters(), parts),
        null,
        signature.build(),
        params,
        returns,
        List.of(),
        null,
        exceptions,
        null,
        defaultValue,
        overridden.isEmpty() ? null : linker.ref(overridden.getFirst(), null),
        implicit,
        description,
        deprecation(member, parts),
        parts.since(),
        implicit ? null : source(member));
  }

  /** The language-defined result of a compiler-generated method. */
  private static String generatedContract(TypeElement owner, String name) {
    if (owner.getKind() == ElementKind.ENUM) {
      return switch (name) {
        case "values" -> "Returns the enum constants in declaration order.";
        case "valueOf" -> "Returns the enum constant with the given name.";
        default -> null;
      };
    }
    if (owner.getKind() == ElementKind.RECORD) {
      return switch (name) {
        case "equals" ->
            "Returns whether the other object is a record of the same class with equal components.";
        case "hashCode" -> "Returns a hash code computed from the record components.";
        case "toString" ->
            "Returns a string containing the record class name and its component names and values.";
        default -> null;
      };
    }
    return null;
  }

  private static List<Doc.Block> sentence(String text) {
    return List.of(new Doc.Paragraph(List.of(new Doc.Text(text))));
  }

  private static Doc description(List<Doc.Block> body) {
    var summary =
        body.getFirst() instanceof Doc.Paragraph paragraph
            ? paragraph.children()
            : List.<Doc.Inline>of();
    return new Doc(summary, body, List.of(), List.of(), null);
  }

  /**
   * Whether {@code method} is the accessor of one of {@code owner}'s record components: the
   * component's own accessor element, never a method that only shares its name.
   */
  private static boolean accessor(TypeElement owner, ExecutableElement method) {
    if (owner.getKind() != ElementKind.RECORD) {
      return false;
    }
    for (var component : owner.getRecordComponents()) {
      if (method.equals(component.getAccessor())) {
        return true;
      }
    }
    return false;
  }

  private boolean canonical(TypeElement record, ExecutableElement constructor) {
    var components = record.getRecordComponents();
    var params = constructor.getParameters();
    if (components.size() != params.size()) {
      return false;
    }
    for (int i = 0; i < params.size(); i++) {
      if (!types.isSameType(
          types.erasure(components.get(i).asType()), types.erasure(params.get(i).asType()))) {
        return false;
      }
    }
    return true;
  }

  /** {@code @throws} tags in order, then declared exceptions they leave undocumented. */
  private List<Api.Thrown> exceptions(ExecutableElement method, Comments.Parts parts) {
    List<Api.Thrown> exceptions = new ArrayList<>();
    Set<Element> declared = new LinkedHashSet<>();
    for (var thrown : method.getThrownTypes()) {
      declared.add(types.asElement(thrown));
    }
    Set<Element> documented = new LinkedHashSet<>();
    for (var thrown : parts.exceptions()) {
      List<Token> type;
      if (thrown.type() instanceof TypeElement element) {
        type = tokens().typeName(element).build();
        documented.add(element);
      } else {
        type = List.of(Token.of(Api.TokenKind.TYPE, thrown.name()));
      }
      exceptions.add(
          new Api.Thrown(
              type, thrown.type() != null && declared.contains(thrown.type()), thrown.doc()));
    }
    for (var thrown : method.getThrownTypes()) {
      if (!documented.contains(types.asElement(thrown))) {
        exceptions.add(new Api.Thrown(tokens().type(thrown).build(), true, List.of()));
      }
    }
    return exceptions;
  }

  private List<Api.TypeParam> typeParams(
      List<? extends TypeParameterElement> params, Comments.Parts parts) {
    List<Api.TypeParam> list = new ArrayList<>();
    for (var param : params) {
      List<List<Token>> bounds = new ArrayList<>();
      for (var bound : Tokens.bounds(param)) {
        bounds.add(tokens().type(bound).build());
      }
      var name = param.getSimpleName().toString();
      list.add(new Api.TypeParam(name, bounds, parts.typeParams().getOrDefault(name, List.of())));
    }
    return list;
  }

  /**
   * Members inherited from supertypes, grouped by declaring type: superclasses first, then
   * interfaces.
   */
  private List<Api.Inherited> inherited(TypeElement type) {
    Map<TypeElement, List<Element>> groups = new LinkedHashMap<>();
    for (var supertype : supertypes(type)) {
      groups.put(supertype, new ArrayList<>());
    }
    for (var member : elements.getAllMembers(type)) {
      if (member.getEnclosingElement().equals(type)
          || !(member.getKind() == ElementKind.METHOD || member.getKind() == ElementKind.FIELD)
          || !visible(member)
          || comments.hidden(member)) {
        continue;
      }
      var group = groups.get((TypeElement) member.getEnclosingElement());
      if (group != null) {
        group.add(member);
      }
    }
    List<Api.Inherited> inherited = new ArrayList<>();
    for (var entry : groups.entrySet()) {
      if (entry.getValue().isEmpty()) {
        continue;
      }
      var members = new ArrayList<>(entry.getValue());
      members.sort(
          Comparator.comparing((Element e) -> e.getSimpleName().toString())
              .thenComparing(linker::anchor));
      List<Api.Ref> refs = new ArrayList<>();
      for (var member : members) {
        refs.add(linker.ref(member, member.getSimpleName().toString()));
      }
      var from = entry.getKey();
      inherited.add(
          new Api.Inherited(
              linker.ref(from, linker.typeName(from)),
              from.getKind() == ElementKind.INTERFACE ? "interface" : "class",
              refs));
    }
    return inherited;
  }

  private boolean visible(Element member) {
    var modifiers = member.getModifiers();
    if (env.isIncluded(member.getEnclosingElement())) {
      return env.isIncluded(member);
    }
    return modifiers.contains(Modifier.PUBLIC)
        || (protectedMembers && modifiers.contains(Modifier.PROTECTED));
  }

  private List<TypeElement> supertypes(TypeElement type) {
    Set<TypeElement> seen = new LinkedHashSet<>();
    List<TypeElement> classes = new ArrayList<>();
    for (TypeMirror t = type.getSuperclass();
        t instanceof DeclaredType d;
        t = ((TypeElement) d.asElement()).getSuperclass()) {
      classes.add((TypeElement) d.asElement());
    }
    seen.addAll(classes);
    List<TypeElement> interfaces = new ArrayList<>();
    List<TypeElement> queue = new ArrayList<>();
    queue.add(type);
    queue.addAll(classes);
    for (int i = 0; i < queue.size(); i++) {
      for (var iface : queue.get(i).getInterfaces()) {
        var element = (TypeElement) ((DeclaredType) iface).asElement();
        if (seen.add(element)) {
          interfaces.add(element);
          queue.add(element);
        }
      }
    }
    List<TypeElement> all = new ArrayList<>(classes);
    all.addAll(interfaces);
    return all;
  }

  /** Modifiers as the declaration would show them: without the ones its kind implies. */
  private List<String> modifiers(Element element) {
    var modifiers = EnumSet.noneOf(Modifier.class);
    modifiers.addAll(element.getModifiers());
    modifiers.removeAll(NOT_SHOWN);
    var enclosing = element.getEnclosingElement();
    var inInterface =
        enclosing != null
            && (enclosing.getKind() == ElementKind.INTERFACE
                || enclosing.getKind() == ElementKind.ANNOTATION_TYPE);
    switch (element.getKind()) {
      case INTERFACE, ANNOTATION_TYPE ->
          modifiers.removeAll(EnumSet.of(Modifier.ABSTRACT, Modifier.STATIC));
      case ENUM ->
          modifiers.removeAll(
              EnumSet.of(Modifier.FINAL, Modifier.STATIC, Modifier.SEALED, Modifier.NON_SEALED));
      case RECORD -> modifiers.removeAll(EnumSet.of(Modifier.FINAL, Modifier.STATIC));
      case ENUM_CONSTANT -> modifiers.clear();
      case METHOD -> {
        if (inInterface) {
          modifiers.removeAll(EnumSet.of(Modifier.PUBLIC, Modifier.ABSTRACT));
        }
      }
      case FIELD -> {
        if (inInterface) {
          modifiers.removeAll(EnumSet.of(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL));
        }
      }
      default -> {}
    }
    if (element instanceof TypeElement && inInterface) {
      modifiers.remove(Modifier.STATIC);
    }
    return modifiers.stream().map(Modifier::toString).toList();
  }

  /** JSpecify nullness of a type use: annotated, or non-null in a {@code @NullMarked} scope. */
  private Api.Nullness nullness(TypeMirror type, Element scope) {
    var kind = type.getKind();
    if (kind.isPrimitive() || kind == TypeKind.VOID) {
      return null;
    }
    for (var mirror : type.getAnnotationMirrors()) {
      var name = mirror.getAnnotationType().asElement().getSimpleName().toString();
      if (name.equals("Nullable")) {
        return Api.Nullness.NULLABLE;
      }
      if (name.equals("NonNull") || name.equals("NotNull")) {
        return Api.Nullness.NON_NULL;
      }
    }
    if (kind == TypeKind.TYPEVAR || kind == TypeKind.WILDCARD) {
      return null;
    }
    return nullMarked(scope) ? Api.Nullness.NON_NULL : null;
  }

  /**
   * Whether the innermost {@code @NullMarked} or {@code @NullUnmarked} around {@code element} is
   * marked.
   */
  private boolean nullMarked(Element element) {
    for (var e = element; e != null; e = e.getEnclosingElement()) {
      if (annotated(e, NULL_UNMARKED)) {
        return false;
      }
      if (annotated(e, NULL_MARKED)) {
        return true;
      }
    }
    return false;
  }

  private static boolean annotated(Element element, String annotation) {
    for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
      if (((TypeElement) mirror.getAnnotationType().asElement())
          .getQualifiedName()
          .contentEquals(annotation)) {
        return true;
      }
    }
    return false;
  }

  private Api.Deprecation deprecation(Element element, Comments.Parts parts) {
    AnnotationMirror deprecated = null;
    for (var mirror : element.getAnnotationMirrors()) {
      if (((TypeElement) mirror.getAnnotationType().asElement())
          .getQualifiedName()
          .contentEquals("java.lang.Deprecated")) {
        deprecated = mirror;
      }
    }
    if (deprecated == null && parts.deprecated() == null) {
      return null;
    }
    String since = null;
    boolean forRemoval = false;
    if (deprecated != null) {
      for (var entry : deprecated.getElementValues().entrySet()) {
        switch (entry.getKey().getSimpleName().toString()) {
          case "since" -> since = (String) entry.getValue().getValue();
          case "forRemoval" -> forRemoval = (Boolean) entry.getValue().getValue();
          default -> {}
        }
      }
    }
    return new Api.Deprecation(
        parts.deprecated() == null ? List.of() : parts.deprecated(), since, forRemoval);
  }

  private Api.Source source(Element element) {
    var path = trees.getPath(element);
    if (path == null) {
      return null;
    }
    var unit = path.getCompilationUnit();
    long position = trees.getSourcePositions().getStartPosition(unit, path.getLeaf());
    if (position < 0) {
      return null;
    }
    var file = unit.getSourceFile().getName().replace('\\', '/');
    file = file.substring(file.lastIndexOf('/') + 1);
    var pkg =
        unit.getPackageName() == null
            ? ""
            : unit.getPackageName().toString().replace('.', '/') + "/";
    return new Api.Source(pkg + file, (int) unit.getLineMap().getLineNumber(position));
  }

  private static Doc nonEmpty(Doc doc) {
    if (doc == null
        || (doc.summary().isEmpty()
            && doc.body().isEmpty()
            && doc.notes().isEmpty()
            && doc.see().isEmpty()
            && doc.inheritedFrom() == null)) {
      return null;
    }
    return doc;
  }
}
