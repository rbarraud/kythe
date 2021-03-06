/*
 * Copyright 2014 Google Inc. All rights reserved.
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

import com.google.common.base.Preconditions;
import com.google.devtools.kythe.analyzers.base.FactEmitter;
import com.google.devtools.kythe.common.FormattingLogger;
import com.google.devtools.kythe.platform.java.JavaCompilationDetails;
import com.google.devtools.kythe.platform.java.JavacAnalyzer;
import com.google.devtools.kythe.platform.java.helpers.SignatureGenerator;
import com.google.devtools.kythe.platform.shared.AnalysisException;
import com.google.devtools.kythe.platform.shared.MetadataLoaders;
import com.google.devtools.kythe.platform.shared.StatisticsCollector;
import com.google.devtools.kythe.proto.Analysis.CompilationUnit;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;

/** {@link JavacAnalyzer} to emit Kythe nodes and edges. */
public class KytheJavacAnalyzer extends JavacAnalyzer {
  private static final long serialVersionUID = 7458181626939870354L;

  private static final FormattingLogger logger =
      FormattingLogger.getLogger(KytheJavacAnalyzer.class);

  private final FactEmitter emitter;
  private final JavaIndexerConfig config;
  private final MetadataLoaders metadataLoaders;
  private final List<Plugin> plugins = new ArrayList<>();

  // should be set in analyzeCompilationUnit before any call to analyzeFile
  private JavaEntrySets entrySets;

  public KytheJavacAnalyzer(
      JavaIndexerConfig config,
      FactEmitter emitter,
      StatisticsCollector statistics,
      MetadataLoaders metadataLoaders) {
    super(statistics);
    Preconditions.checkArgument(emitter != null, "FactEmitter must be non-null");
    Preconditions.checkArgument(config != null, "IndexerConfig must be non-null");
    this.emitter = emitter;
    this.config = config;
    this.metadataLoaders = metadataLoaders;
  }

  /**
   * Register a {@link Plugin} to be run for each {@link JCCompilationUnit} analysis. Plugins are
   * executed in the same order they are registered.
   */
  public KytheJavacAnalyzer registerPlugin(Plugin plugin) {
    plugins.add(plugin);
    return this;
  }

  @Override
  public void analyzeCompilationUnit(JavaCompilationDetails details) throws AnalysisException {
    Preconditions.checkState(
        entrySets == null,
        "JavaEntrySets is non-null (analyzeCompilationUnit was called concurrently?)");
    if (config.getVerboseLogging()) {
      for (Diagnostic<?> err : details.getCompileErrors()) {
        logger.warningfmt("javac compilation error: %s", err);
      }
    }
    CompilationUnit compilation = details.getCompilationUnit();
    entrySets =
        new JavaEntrySets(
            getStatisticsCollector(),
            emitter,
            compilation.getVName(),
            compilation.getRequiredInputList(),
            config.getIgnoreVNamePaths(),
            config.getIgnoreVNameRoots(),
            config.getOverrideJdkCorpus());
    try {
      super.analyzeCompilationUnit(details);
    } finally {
      entrySets = null; // Ensure entrySets is cleared for error-checking
    }
  }

  @Override
  public void analyzeFile(JavaCompilationDetails details, CompilationUnitTree ast)
      throws AnalysisException {
    Preconditions.checkState(
        entrySets != null, "analyzeCompilationUnit must be called to analyze each file");
    Context context = ((JavacTaskImpl) details.getJavac()).getContext();
    JCCompilationUnit compilation = (JCCompilationUnit) ast;
    Map<JCTree, Plugin.KytheNode> nodes = null;
    if (!plugins.isEmpty()) {
      nodes = new HashMap<>();
    }
    try {
      SignatureGenerator signatureGenerator =
          new SignatureGenerator(ast, context, config.getEmitJvmSignatures());
      KytheTreeScanner.emitEntries(
          context,
          getStatisticsCollector(),
          entrySets,
          signatureGenerator,
          compilation,
          nodes == null ? null : nodes::put,
          details.getEncoding(),
          config.getVerboseLogging(),
          details.getFileManager(),
          metadataLoaders);
    } catch (Throwable e) {
      throw new AnalysisException("Exception analyzing file: " + ast.getSourceFile().getName(), e);
    }
    if (!plugins.isEmpty()) {
      Plugin.KytheGraph graph = new Plugin.KytheGraph(Collections.unmodifiableMap(nodes));
      for (Plugin plugin : plugins) {
        try {
          plugin.run(compilation, entrySets, graph);
        } catch (Throwable e) {
          logger.warningfmt(e, "Error running plugin %s: %s", plugin.getClass(), e.getMessage());
        }
      }
    }
  }
}
