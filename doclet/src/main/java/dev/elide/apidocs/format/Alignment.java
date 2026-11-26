/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.format;

import dev.elide.apidocs.model.Api;
import dev.elide.apidocs.model.Api.Token;
import dev.elide.apidocs.model.Api.TokenKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a declaration's laid-out text back onto its signature tokens. The two are walked side by
 * side, character by character with whitespace skipped: every other character must match. Each run
 * of whitespace the formatter wrote replaces the run between the same two characters of the
 * signature, inside the punctuation token that holds it; where the signature had no whitespace, the
 * run joins the punctuation token on either side, or becomes a whitespace-only punctuation token
 * between two other tokens. Whitespace inside any other token (a string literal, say) must come
 * back unchanged. What follows the last token must be the stub's body. The walk reads each token as
 * the stub wrote it ({@link Stub#texts}) and returns it as the signature had it.
 *
 * <p>Kotlin only: a comma ktfmt adds before a closing bracket that ends a broken list (a trailing
 * comma) is kept with the whitespace around it, as in ktfmt's own output.
 */
final class Alignment {
  private Alignment() {}

  /** Why a declaration keeps its one-line form. */
  static final class Mismatch extends Exception {
    private static final long serialVersionUID = 1L;

    Mismatch(String message) {
      super(message);
    }
  }

  /**
   * The signature's tokens with the whitespace of {@code laidOut}.
   *
   * @param tokens the one-line signature
   * @param laidOut the declaration as the formatter wrote it, stub indentation removed
   * @param body the stub's body after the signature
   * @param trailingCommas whether a trailing comma may be added
   * @throws Mismatch if the texts differ in anything but whitespace
   */
  static List<Token> apply(List<Token> tokens, String laidOut, String body, boolean trailingCommas)
      throws Mismatch {
    var stubTexts = Stub.texts(tokens);
    var original = String.join("", stubTexts);
    int[] owner = new int[original.length()];
    for (int t = 0, c = 0; t < tokens.size(); t++) {
      for (int k = 0; k < stubTexts.get(t).length(); k++) {
        owner[c++] = t;
      }
    }
    var texts = new StringBuilder[tokens.size()];
    for (int t = 0; t < texts.length; t++) {
      texts[t] = new StringBuilder();
    }
    Map<Integer, String> insertedBefore = new HashMap<>();
    int i = 0;
    int j = 0;
    while (i < original.length()) {
      int gapEnd = i;
      while (gapEnd < original.length() && Character.isWhitespace(original.charAt(gapEnd))) {
        gapEnd++;
      }
      if (gapEnd == original.length()) {
        // Whitespace that ends the signature stays as it was.
        for (int c = i; c < gapEnd; c++) {
          texts[owner[c]].append(original.charAt(c));
        }
        i = gapEnd;
        break;
      }
      int laidGapEnd = gapEnd(laidOut, j, original.charAt(gapEnd), trailingCommas);
      var gap = laidOut.substring(j, laidGapEnd);
      if (gapEnd > i) {
        replace(tokens, owner, texts, original, i, gapEnd, gap);
      } else if (!gap.isEmpty() && i > 0) {
        insert(tokens, owner, texts, insertedBefore, i, gap);
      }
      if (laidGapEnd >= laidOut.length() || laidOut.charAt(laidGapEnd) != original.charAt(gapEnd)) {
        throw new Mismatch(
            "the formatter changed the text at \""
                + excerpt(original, gapEnd)
                + "\" (it wrote \""
                + excerpt(laidOut, laidGapEnd)
                + "\")");
      }
      texts[owner[gapEnd]].append(original.charAt(gapEnd));
      i = gapEnd + 1;
      j = laidGapEnd + 1;
    }
    var rest = strip(laidOut.substring(j), false);
    if (!rest.equals(strip(body, false))) {
      throw new Mismatch(
          "the formatter wrote \""
              + excerpt(laidOut, j)
              + "\" after the declaration, not its stub body");
    }
    List<Token> out = new ArrayList<>();
    for (int t = 0; t < tokens.size(); t++) {
      var inserted = insertedBefore.get(t);
      if (inserted != null) {
        out.add(Token.of(TokenKind.PUNCT, inserted));
      }
      var token = tokens.get(t);
      var text = texts[t].toString();
      if (token.kind() != TokenKind.PUNCT && text.equals(stubTexts.get(t))) {
        // As the signature wrote it, not as the stub did.
        text = token.text();
      }
      out.add(new Token(token.kind(), text, token.ref(), token.url()));
    }
    verify(tokens, out, trailingCommas);
    return out;
  }

  /**
   * Where the laid-out whitespace starting at {@code j} ends: at the next character that is not
   * whitespace, or not a trailing comma before {@code next}.
   */
  private static int gapEnd(String laidOut, int j, char next, boolean trailingCommas) {
    int k = j;
    while (k < laidOut.length()) {
      char c = laidOut.charAt(k);
      if (Character.isWhitespace(c)) {
        k++;
      } else if (trailingCommas
          && c == ','
          && closer(next)
          && nextNonSpace(laidOut, k + 1) == next) {
        k++;
      } else {
        break;
      }
    }
    return k;
  }

  private static boolean closer(char c) {
    return c == ')' || c == '>' || c == ']';
  }

  private static char nextNonSpace(String text, int from) {
    for (int k = from; k < text.length(); k++) {
      if (!Character.isWhitespace(text.charAt(k))) {
        return text.charAt(k);
      }
    }
    return 0;
  }

  /**
   * The signature had whitespace at {@code [from, to)}: the formatter's {@code gap} replaces it.
   */
  private static void replace(
      List<Token> tokens,
      int[] owner,
      StringBuilder[] texts,
      String original,
      int from,
      int to,
      String gap)
      throws Mismatch {
    int t = owner[from];
    boolean single = true;
    for (int c = from; c < to; c++) {
      single &= owner[c] == t;
    }
    if (single && tokens.get(t).kind() == TokenKind.PUNCT) {
      texts[t].append(gap);
      return;
    }
    if (!gap.equals(original.substring(from, to))) {
      throw new Mismatch("the formatter changed whitespace inside " + describe(tokens.get(t)));
    }
    for (int c = from; c < to; c++) {
      texts[owner[c]].append(original.charAt(c));
    }
  }

  /**
   * The signature had no whitespace before position {@code at}: the formatter's {@code gap} goes
   * in.
   */
  private static void insert(
      List<Token> tokens,
      int[] owner,
      StringBuilder[] texts,
      Map<Integer, String> insertedBefore,
      int at,
      String gap)
      throws Mismatch {
    int before = owner[at - 1];
    int after = owner[at];
    if (tokens.get(before).kind() == TokenKind.PUNCT) {
      texts[before].append(gap);
    } else if (tokens.get(after).kind() == TokenKind.PUNCT) {
      texts[after].append(gap);
    } else if (before != after) {
      insertedBefore.merge(after, gap, String::concat);
    } else {
      throw new Mismatch("the formatter split " + describe(tokens.get(before)));
    }
  }

  /**
   * The final check: each token's text is unchanged but for whitespace (and, in Kotlin, trailing
   * commas), and every added token is whitespace.
   */
  private static void verify(List<Token> before, List<Token> after, boolean trailingCommas)
      throws Mismatch {
    int t = 0;
    for (var token : after) {
      var expected = t < before.size() ? before.get(t) : null;
      if (expected != null
          && token.kind() == expected.kind()
          && strip(token.text(), trailingCommas).equals(strip(expected.text(), trailingCommas))
          && (token.kind() == TokenKind.PUNCT || token.text().equals(expected.text()))) {
        t++;
      } else if (token.kind() != TokenKind.PUNCT
          || !strip(token.text(), trailingCommas).isEmpty()) {
        throw new Mismatch("a token changed: " + describe(token));
      }
    }
    if (t != before.size()) {
      throw new Mismatch("tokens were lost");
    }
  }

  /** {@code text} without whitespace and, when allowed, without commas before a closing bracket. */
  static String strip(String text, boolean trailingCommas) {
    var out = new StringBuilder();
    for (int k = 0; k < text.length(); k++) {
      char c = text.charAt(k);
      if (Character.isWhitespace(c)) {
        continue;
      }
      if (trailingCommas && c == ',' && closer(nextNonSpace(text, k + 1))) {
        continue;
      }
      out.append(c);
    }
    return out.toString();
  }

  private static String describe(Token token) {
    return "a "
        + token.kind().name().toLowerCase(java.util.Locale.ROOT)
        + " token (\""
        + token.text()
        + "\")";
  }

  private static String excerpt(String text, int at) {
    var end = Math.min(text.length(), at + 24);
    return text.substring(Math.min(at, end), end).replace("\n", "\\n");
  }

  /** Whether any token holds a line break. */
  static boolean multiline(List<Api.Token> tokens) {
    for (var token : tokens) {
      if (token.text().indexOf('\n') >= 0) {
        return true;
      }
    }
    return false;
  }
}
