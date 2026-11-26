/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.format;

import dev.elide.apidocs.model.Api;
import dev.elide.apidocs.model.Api.Token;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lays out every signature of an {@code apidocs/1} model: types, their members, and a package's
 * top-level functions, properties and type aliases. One formatter run per language covers the whole
 * module; a type's language ({@code java} or {@code kotlin}) is its members' too, and top-level
 * declarations are Kotlin. The doclet and the Dokka plugin both call this once, on the finished
 * model.
 */
public final class ModelLayout {
  private ModelLayout() {}

  /**
   * The model with its signatures laid out.
   *
   * @param model the model, with one-line signatures
   * @param layout the layout to use
   * @return a copy of {@code model} whose signatures are laid out; {@code model} itself when {@code
   *     layout} has no formatter
   */
  public static Api.Model apply(Api.Model model, SignatureLayout layout) {
    if (!layout.enabled()) {
      return model;
    }
    Map<String, Api.TypeKind> kinds = new HashMap<>();
    for (var pkg : model.packages()) {
      for (var type : pkg.types()) {
        kinds.put(type.id(), type.kind());
      }
    }
    var batches = new Batches();
    for (var pkg : model.packages()) {
      for (var type : pkg.types()) {
        var language = language(type);
        var container =
            type.enclosing() == null
                ? null
                : kinds.getOrDefault(type.enclosing(), Api.TypeKind.CLASS);
        batches.add(language, Declaration.type(type.id(), container, type.signature()));
        for (var member : type.members()) {
          batches.add(
              language,
              Declaration.member(member.id(), member.kind(), type.kind(), member.signature()));
        }
      }
      for (var function : pkg.functions()) {
        batches.add(
            Language.KOTLIN,
            Declaration.member(function.id(), function.kind(), null, function.signature()));
      }
      for (var property : pkg.properties()) {
        batches.add(
            Language.KOTLIN,
            Declaration.member(property.id(), property.kind(), null, property.signature()));
      }
      for (var alias : pkg.typeAliases()) {
        batches.add(Language.KOTLIN, Declaration.typeAlias(alias.id(), alias.signature()));
      }
    }
    batches.layOut(layout);
    List<Api.Package> packages = new ArrayList<>();
    for (var pkg : model.packages()) {
      List<Api.Type> types = new ArrayList<>();
      for (var t : pkg.types()) {
        var language = language(t);
        var signature = batches.next(language);
        List<Api.Member> members = new ArrayList<>();
        for (var m : t.members()) {
          members.add(member(m, batches.next(language)));
        }
        types.add(
            new Api.Type(
                t.id(),
                t.name(),
                t.kind(),
                t.language(),
                t.modifiers(),
                t.annotations(),
                t.typeParams(),
                signature,
                t.superclass(),
                t.interfaces(),
                t.ancestors(),
                t.permits(),
                t.enclosing(),
                t.components(),
                members,
                t.inherited(),
                t.doc(),
                t.deprecated(),
                t.since(),
                t.nullMarked(),
                t.source()));
      }
      List<Api.Member> functions = new ArrayList<>();
      for (var f : pkg.functions()) {
        functions.add(member(f, batches.next(Language.KOTLIN)));
      }
      List<Api.Member> properties = new ArrayList<>();
      for (var p : pkg.properties()) {
        properties.add(member(p, batches.next(Language.KOTLIN)));
      }
      List<Api.TypeAlias> aliases = new ArrayList<>();
      for (var a : pkg.typeAliases()) {
        aliases.add(
            new Api.TypeAlias(
                a.id(),
                a.name(),
                a.typeParams(),
                batches.next(Language.KOTLIN),
                a.target(),
                a.doc(),
                a.deprecated(),
                a.since(),
                a.source()));
      }
      packages.add(
          new Api.Package(
              pkg.id(),
              pkg.name(),
              pkg.doc(),
              pkg.since(),
              pkg.deprecated(),
              pkg.nullMarked(),
              types,
              functions,
              properties,
              aliases));
    }
    return new Api.Model(model.schema(), model.module(), model.externals(), packages);
  }

  private static Language language(Api.Type type) {
    return "kotlin".equals(type.language()) ? Language.KOTLIN : Language.JAVA;
  }

  private static Api.Member member(Api.Member m, List<Token> signature) {
    return new Api.Member(
        m.id(),
        m.anchor(),
        m.kind(),
        m.name(),
        m.modifiers(),
        m.annotations(),
        m.typeParams(),
        m.receiver(),
        signature,
        m.params(),
        m.returns(),
        m.type(),
        m.nullness(),
        m.exceptions(),
        m.constant(),
        m.defaultValue(),
        m.overrides(),
        m.implicit(),
        m.doc(),
        m.deprecated(),
        m.since(),
        m.source());
  }

  /**
   * The declarations of each language in model order, then their laid-out signatures in the same
   * order.
   */
  private static final class Batches {
    private final Map<Language, List<Declaration>> declarations = new HashMap<>();
    private final Map<Language, List<List<Token>>> laidOut = new HashMap<>();
    private final Map<Language, Integer> read = new HashMap<>();

    void add(Language language, Declaration declaration) {
      declarations.computeIfAbsent(language, l -> new ArrayList<>()).add(declaration);
    }

    void layOut(SignatureLayout layout) {
      for (var language : Language.values()) {
        var batch = declarations.getOrDefault(language, List.of());
        laidOut.put(language, batch.isEmpty() ? List.of() : layout.layout(language, batch));
      }
    }

    List<Token> next(Language language) {
      int index = read.merge(language, 1, Integer::sum) - 1;
      return laidOut.get(language).get(index);
    }
  }
}
