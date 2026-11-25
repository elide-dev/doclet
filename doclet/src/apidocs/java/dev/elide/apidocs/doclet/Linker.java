/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.doclet;

import dev.elide.apidocs.model.Api;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Model ids, anchors and links for Java elements.
 *
 * <p>A type's id is its binary name ({@code p.Outer$Inner}); a member's is {@code <type
 * id>#<anchor>}; a package's is its qualified name. Anchors are the JDK doclet's: a method's name
 * and its parameter types (generic arguments erased, type variables by name, the last of a
 * variable-arity method as {@code T...}), {@code <init>} for constructors, a field's name. A target
 * outside the documented set links through the first {@link Api.External} whose prefix its
 * qualified name starts with, laid out as Javadoc lays out pages; {@code {module}} in the
 * external's URL stands for the target's module.
 */
final class Linker {
  private final Elements elements;
  private final Types types;
  private final List<Api.External> externals;
  private final Set<String> documented = new HashSet<>();

  Linker(Elements elements, Types types, List<Api.External> externals) {
    this.elements = elements;
    this.types = types;
    this.externals = externals;
  }

  /** Records {@code id} as part of the model, so references to it stay local. */
  void document(String id) {
    documented.add(id);
  }

  String typeId(TypeElement type) {
    return elements.getBinaryName(type).toString();
  }

  /** A type's name inside its package: nesting joined with dots. */
  String typeName(TypeElement type) {
    var pkg = elements.getPackageOf(type).getQualifiedName().toString();
    var qualified = type.getQualifiedName().toString();
    return pkg.isEmpty() ? qualified : qualified.substring(pkg.length() + 1);
  }

  String memberId(Element member) {
    return typeId((TypeElement) member.getEnclosingElement()) + "#" + anchor(member);
  }

  /** The JDK doclet's anchor for a member. */
  String anchor(Element member) {
    return switch (member.getKind()) {
      case FIELD, ENUM_CONSTANT -> member.getSimpleName().toString();
      case METHOD, CONSTRUCTOR -> {
        var method = (ExecutableElement) member;
        var name =
            method.getKind() == ElementKind.CONSTRUCTOR
                ? "<init>"
                : method.getSimpleName().toString();
        List<String> params = new ArrayList<>();
        var parameters = method.getParameters();
        for (int i = 0; i < parameters.size(); i++) {
          boolean varargs = method.isVarArgs() && i == parameters.size() - 1;
          params.add(anchorType(parameters.get(i).asType(), varargs));
        }
        yield name + "(" + String.join(",", params) + ")";
      }
      case RECORD_COMPONENT -> "param-" + member.getSimpleName();
      default ->
          throw new IllegalArgumentException("No anchor for a " + member.getKind() + ": " + member);
    };
  }

  private String anchorType(TypeMirror type, boolean varargs) {
    return switch (type.getKind()) {
      case ARRAY ->
          anchorType(((ArrayType) type).getComponentType(), false) + (varargs ? "..." : "[]");
      case DECLARED -> ((TypeElement) types.asElement(type)).getQualifiedName().toString();
      case TYPEVAR -> ((TypeVariable) type).asElement().getSimpleName().toString();
      default -> {
        if (type.getKind().isPrimitive()) {
          yield type.toString();
        }
        throw new IllegalArgumentException(
            "No anchor form for a " + type.getKind() + " parameter: " + type);
      }
    };
  }

  /**
   * A reference to {@code target} (a package, type or member), with its URL when it is external.
   */
  Api.Ref ref(Element target, String text) {
    var id = id(target);
    return new Api.Ref(id, text, documented.contains(id) ? null : url(target));
  }

  /** The id of a package, type or member. */
  String id(Element target) {
    return switch (target) {
      case PackageElement pkg -> pkg.getQualifiedName().toString();
      case TypeElement type -> typeId(type);
      case ExecutableElement method -> memberId(method);
      case VariableElement field when field.getEnclosingElement() instanceof TypeElement ->
          memberId(field);
      default ->
          throw new IllegalArgumentException("No id for a " + target.getKind() + ": " + target);
    };
  }

  boolean documented(Element target) {
    return documented.contains(id(target));
  }

  /** {@code target}'s URL through an external, or {@code null} when none covers it. */
  String url(Element target) {
    var pkg = elements.getPackageOf(target);
    var qualified =
        switch (target) {
          case PackageElement p -> p.getQualifiedName().toString();
          case TypeElement t -> t.getQualifiedName().toString();
          default -> ((TypeElement) target.getEnclosingElement()).getQualifiedName().toString();
        };
    for (var external : externals) {
      if (!qualified.startsWith(external.prefix())) {
        continue;
      }
      ModuleElement module = elements.getModuleOf(target);
      var moduleName =
          module == null || module.isUnnamed() ? "" : module.getQualifiedName().toString();
      var base = external.url().replace("{module}", moduleName);
      if (!base.endsWith("/")) {
        base += "/";
      }
      var dir = base + pkg.getQualifiedName().toString().replace('.', '/') + "/";
      return switch (target) {
        case PackageElement p -> dir + "package-summary.html";
        case TypeElement t -> dir + typeName(t) + ".html";
        default ->
            dir
                + typeName((TypeElement) target.getEnclosingElement())
                + ".html#"
                + fragment(anchor(target));
      };
    }
    return null;
  }

  /** An anchor as a URL fragment, escaped as the JDK doclet escapes it. */
  static String fragment(String anchor) {
    return anchor
        .chars()
        .mapToObj(c ->
            switch (c) {
              case '<' -> "%3C";
              case '>' -> "%3E";
              case ' ' -> "%20";
              default -> Character.toString(c);
            })
        .collect(Collectors.joining());
  }
}
