/*
 * Copyright 2017 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.kythe.analyzers.java;

import com.google.common.collect.Lists;
import com.google.devtools.kythe.platform.java.helpers.SignatureGenerator;
import com.google.devtools.kythe.proto.Link;
import com.google.devtools.kythe.proto.MarkedSource;
import com.google.devtools.kythe.proto.Storage.VName;
import com.google.devtools.kythe.util.KytheURI;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTag;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javax.annotation.Nullable;
import javax.lang.model.element.ElementKind;

/** {@link MarkedSource} utility class. */
public final class MarkedSources {
  private MarkedSources() {}

  /** Returns a {@link MarkedSource} instance for a {@link Symbol}. */
  static MarkedSource construct(
      SignatureGenerator signatureGenerator,
      Symbol sym,
      @Nullable MarkedSource.Builder msBuilder,
      @Nullable Iterable<MarkedSource> postChildren,
      Function<Symbol, Optional<VName>> symNames) {
    MarkedSource markedType = markType(signatureGenerator, sym, symNames);
    return construct(signatureGenerator, sym, msBuilder, postChildren, markedType);
  }

  private static MarkedSource construct(
      SignatureGenerator signatureGenerator,
      Symbol sym,
      @Nullable MarkedSource.Builder msBuilder,
      @Nullable Iterable<MarkedSource> postChildren,
      @Nullable MarkedSource markedType) {
    MarkedSource.Builder markedSource = msBuilder == null ? MarkedSource.newBuilder() : msBuilder;
    if (markedType != null) {
      markedSource.addChild(markedType);
    }
    MarkedSource.Builder context = MarkedSource.newBuilder();
    String identToken = buildContext(context, sym, signatureGenerator);
    markedSource.addChild(context.build());
    switch (sym.getKind()) {
      case TYPE_PARAMETER:
        markedSource.addChild(
            MarkedSource.newBuilder()
                .setKind(MarkedSource.Kind.IDENTIFIER)
                .setPreText("<" + sym.getSimpleName() + ">")
                .build());
        break;
      case CONSTRUCTOR:
      case METHOD:
        ClassSymbol enclClass = sym.enclClass();
        String methodName;
        if (sym.getKind() == ElementKind.CONSTRUCTOR && enclClass != null) {
          methodName = enclClass.getSimpleName().toString();
        } else {
          methodName = sym.getSimpleName().toString();
        }
        markedSource.addChild(
            MarkedSource.newBuilder()
                .setKind(MarkedSource.Kind.IDENTIFIER)
                .setPreText(methodName)
                .build());
        markedSource.addChild(
            MarkedSource.newBuilder()
                .setKind(MarkedSource.Kind.PARAMETER_LOOKUP_BY_PARAM)
                .setPreText("(")
                .setPostChildText(", ")
                .setPostText(")")
                .build());
        break;
      default:
        markedSource.addChild(
            MarkedSource.newBuilder()
                .setKind(MarkedSource.Kind.IDENTIFIER)
                .setPreText(identToken)
                .build());
        break;
    }
    if (postChildren != null) {
      postChildren.forEach(markedSource::addChild);
    }
    return markedSource.build();
  }

  /**
   * Sets the provided {@link MarkedSource.Builder} to a CONTEXT node, populating it with the
   * fully-qualified parent scope for sym. Returns the identifier corresponding to sym.
   */
  private static String buildContext(
      MarkedSource.Builder context, Symbol sym, SignatureGenerator signatureGenerator) {
    context.setKind(MarkedSource.Kind.CONTEXT).setPostChildText(".").setAddFinalListToken(true);
    String identToken = getIdentToken(sym, signatureGenerator);
    Symbol parent = getQualifiedNameParent(sym);
    List<MarkedSource> parents = Lists.newArrayList();
    while (parent != null) {
      String parentName = getIdentToken(parent, signatureGenerator);
      if (!parentName.isEmpty()) {
        parents.add(
            MarkedSource.newBuilder()
                .setKind(MarkedSource.Kind.IDENTIFIER)
                .setPreText(parentName)
                .build());
      }
      parent = getQualifiedNameParent(parent);
    }
    for (int i = 0; i < parents.size(); ++i) {
      context.addChild(parents.get(parents.size() - i - 1));
    }
    return identToken;
  }

  /**
   * Returns a {@link MarkedSource} instance for sym's type (or its return type, if sym is a
   * method). If there is no appropriate type for sym, returns null. Generates links with
   * signatureGenerator.
   */
  @Nullable
  private static MarkedSource markType(
      SignatureGenerator signatureGenerator,
      Symbol sym,
      Function<Symbol, Optional<VName>> symNames) {
    // TODO(zarko): Mark up any annotations.
    Type type = sym.type;
    if (type == null || sym == type.tsym) {
      return null;
    }
    boolean wasArray = false;
    if (type.getReturnType() != null) {
      type = type.getReturnType();
    }
    if (type.hasTag(TypeTag.ARRAY) && ((Type.ArrayType) type).elemtype != null) {
      wasArray = true;
      type = ((Type.ArrayType) type).elemtype;
    }
    MarkedSource.Builder builder = MarkedSource.newBuilder().setKind(MarkedSource.Kind.TYPE);
    if (type.hasTag(TypeTag.CLASS)) {
      MarkedSource.Builder context = MarkedSource.newBuilder();
      String identToken = buildContext(context, type.tsym, signatureGenerator);
      builder.addChild(context.build());
      builder.addChild(
          MarkedSource.newBuilder()
              .setKind(MarkedSource.Kind.IDENTIFIER)
              .setPreText(identToken + (wasArray ? "[] " : " "))
              .build());
      symNames
          .apply(type.tsym)
          .map(v -> new KytheURI(v).toString())
          .ifPresent(ticket -> builder.addLink(Link.newBuilder().addDefinition(ticket)));
    } else {
      builder.addChild(
          MarkedSource.newBuilder()
              .setKind(MarkedSource.Kind.IDENTIFIER)
              .setPreText(type + (wasArray ? "[] " : " "))
              .build());
    }
    return builder.build();
  }

  /**
   * The only place the integer index for nested classes/anonymous classes is stored is in the
   * flatname of the symbol. (This index is determined at compile time using linear search; see
   * 'localClassName' in Check.java). The simple name can't be relied on; for nested classes it
   * drops the name of the parent class (so 'pkg.OuterClass$Inner' yields only 'Inner') and for
   * anonymous classes it's blank. For multiply-nested classes, we'll see tokens like
   * 'OuterClass$Inner$1$1'.
   */
  private static String getIdentToken(Symbol sym, SignatureGenerator signatureGenerator) {
    // If the symbol represents the generated `Array` class, replace it with the actual
    // array type, if we have it.
    if (SignatureGenerator.isArrayHelperClass(sym) && signatureGenerator != null) {
      return signatureGenerator.getArrayTypeName();
    }
    String flatName = sym.flatName().toString();
    int lastDot = flatName.lastIndexOf('.');
    // A$1 is a valid variable/method name, so make sure we only look at $ in class names.
    int lastCash = (sym instanceof ClassSymbol) ? flatName.lastIndexOf('$') : -1;
    int lastTok = Math.max(lastDot, lastCash);
    String identToken = lastTok < 0 ? flatName : flatName.substring(lastTok + 1);
    if (!identToken.isEmpty() && Character.isDigit(identToken.charAt(0))) {
      identToken = "(anon " + identToken + ")";
    }
    return identToken;
  }

  /**
   * Returns the Symbol for sym's parent in qualified names, assuming that we'll be using
   * getIdentToken() to print nodes.
   *
   * <p>We're going through this extra effort to try and give people unsurprising qualified names.
   * To do that we have to deal with javac's mangling (in {@link #getIdentToken} above), since for
   * anonymous classes javac only stores mangled symbols. The code as written will emit only dotted
   * fully-qualified names, even for inner or anonymous classes, and considers concrete type,
   * package, or method names to be appropriate dot points. (If we weren't careful here we might,
   * for example, observe nodes in a qualified name corresponding to variables that are initialized
   * to anonymous classes.) This reflects the nesting structure from the Java side, not the JVM
   * side.
   */
  @Nullable
  private static Symbol getQualifiedNameParent(Symbol sym) {
    sym = sym.owner;
    while (sym != null) {
      switch (sym.kind) {
        case TYP:
          if (!sym.type.hasTag(TypeTag.TYPEVAR)) {
            return sym;
          }
          break;
        case PCK:
        case MTH:
          return sym;
          // TODO(T227): resolve non-exhaustive switch statements w/o defaults
        default:
          break;
      }
      sym = sym.owner;
    }
    return null;
  }
}
