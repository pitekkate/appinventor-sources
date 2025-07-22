// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2023 MIT, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

package com.google.appinventor.buildserver.tasks.android;

import com.google.appinventor.buildserver.BuildType;
import com.google.appinventor.buildserver.TaskResult;
import com.google.appinventor.buildserver.context.AndroidCompilerContext;
import com.google.appinventor.buildserver.interfaces.AndroidTask;
import com.google.appinventor.buildserver.util.Execution;
import com.google.appinventor.buildserver.util.ExecutorUtils;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes; // ADDED IMPORT
import java.util.*;

@BuildType(aab = true, apk = true)
public class RunR8 extends DexTask implements AndroidTask {

  private static final boolean USE_PROGUARD_MAIN_DEX_HINTS = true;

  @Override
  public TaskResult execute(AndroidCompilerContext context) {
    Set<String> mainDexClasses = new HashSet<>();
    final List<File> inputs = new ArrayList<>();

    try {
      // Collect user .class files
      recordForMainDex(context.getPaths().getClassesDir(), mainDexClasses);

      // Add runtime JARs
      inputs.add(preDexLibrary(context, recordForMainDex(
          new File(context.getResources().getSimpleAndroidRuntimeJar()), mainDexClasses)));
      inputs.add(preDexLibrary(context, recordForMainDex(
          new File(context.getResources().getKawaRuntime()), mainDexClasses)));

      final Set<String> criticalJars = getCriticalJars(context);
      for (String jar : criticalJars) {
        inputs.add(preDexLibrary(context, recordForMainDex(
            new File(context.getResource(jar)), mainDexClasses)));
      }

      if (context.isForCompanion()) {
        inputs.add(preDexLibrary(context, recordForMainDex(
            new File(context.getResources().getAcraRuntime()), mainDexClasses)));
      }

      for (String jar : context.getResources().getSupportJars()) {
        if (criticalJars.contains(jar)) continue;
        inputs.add(preDexLibrary(context, new File(context.getResource(jar))));
      }

      for (String lib : context.getComponentInfo().getUniqueLibsNeeded()) {
        inputs.add(preDexLibrary(context, new File(lib)));
      }

      // Extension libraries
      Set<String> addedExtJars = new HashSet<>();
      for (String type : context.getExtCompTypes()) {
        String sourcePath = ExecutorUtils.getExtCompDirPath(type, context.getProject(),
            context.getExtTypePathCache()) + context.getResources().getSimpleAndroidRuntimeJarPath();
        if (!addedExtJars.contains(sourcePath)) {
          inputs.add(new File(sourcePath));
          addedExtJars.add(sourcePath);
        }
      }

      // User compiled classes
      Files.walkFileTree(context.getPaths().getClassesDir().toPath(), new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (file.toString().endsWith(".class")) {
            inputs.add(file.toFile());
          }
          return FileVisitResult.CONTINUE;
        }
      });

      // Main dex rules only for min-api < 21
      int minSdk = AndroidBuildUtils.computeMinSdk(context);
      boolean useMainDexRules = minSdk < 21;

      if (USE_PROGUARD_MAIN_DEX_HINTS && useMainDexRules) {
        mainDexClasses.clear();
        mainDexClasses.add("com.google.appinventor.components.runtime.*");
        mainDexClasses.add("com.google.appinventor.components.runtime.**.*");
        mainDexClasses.add("kawa.**.*");
        mainDexClasses.add("androidx.core.content.FileProvider");
        mainDexClasses.add("androidx.appcompat.**.*");
        mainDexClasses.add("androidx.collection.*");
        mainDexClasses.add("androidx.vectordrawable.**.*");
        mainDexClasses.add(context.getProject().getMainClass());
      } else if (USE_PROGUARD_MAIN_DEX_HINTS) {
        context.getReporter().info("Skipping --main-dex-rules: min-api " + minSdk + " >= 21");
      }

      // Final R8 execution
      if (!runR8Final(context, inputs, useMainDexRules ? mainDexClasses : null)) {
        return TaskResult.generateError("R8 final pass failed.");
      }

      // Ensure classes.dex exists
      File outputDex = new File(context.getPaths().getTmpDir(), "classes.dex");
      if (!outputDex.exists()) {
        return TaskResult.generateError("R8 did not produce classes.dex");
      }

      // Add to resources for RunApkBuilder
      context.getResources().getDexFiles().add(outputDex);

      return TaskResult.generateSuccess();

    } catch (IOException e) {
      context.getReporter().error("Exception in RunR8: " + e.getMessage());
      return TaskResult.generateError(e);
    }
  }

  /**
   * Runs final R8 step.
   */
  private boolean runR8Final(AndroidCompilerContext context, Collection<File> inputs,
                             Set<String> mainDexClasses) throws IOException {
    List<String> cmd = new ArrayList<>();
    cmd.add("java");
    cmd.add("-Xmx" + context.getChildProcessRam() + "M");
    cmd.add("-Xss8m");
    cmd.add("-cp");
    cmd.add(context.getResources().getR8Jar());
    cmd.add("com.android.tools.r8.R8");

    // R8 Options
    cmd.add("--release");
    cmd.add("--lib");
    cmd.add(context.getResources().getAndroidRuntime());
    cmd.add("--output");
    cmd.add(context.getPaths().getTmpDir().getAbsolutePath());
    cmd.add("--min-api");
    cmd.add(String.valueOf(AndroidBuildUtils.computeMinSdk(context)));
    cmd.add("--no-desugaring");
    cmd.add("--no-minification");

    // Classpath
    cmd.add("--classpath");
    cmd.add(context.getPaths().getClassesDir().getAbsolutePath());

    // Only add --main-dex-rules if min-api < 21
    if (mainDexClasses != null && !mainDexClasses.isEmpty()) {
      File rulesFile = writeClassRulesToFile(context.getPaths().getTmpDir(), mainDexClasses);
      cmd.add("--main-dex-rules");
      cmd.add(rulesFile.getAbsolutePath());
      context.getReporter().info("Using main dex rules: " + rulesFile.getName());
    }

    // Input JARs/Dirs
    for (File input : inputs) {
      cmd.add(input.getAbsolutePath());
    }

    // Log command
    context.getReporter().info("Executing R8:");
    for (String arg : cmd) {
      context.getReporter().info("  " + arg);
    }

    ByteArrayOutputStream outStream = new ByteArrayOutputStream();
    ByteArrayOutputStream errStream = new ByteArrayOutputStream();

    synchronized (context.getResources().getSyncKawaOrDx()) {
      boolean success = Execution.execute(
          context.getPaths().getTmpDir(),
          cmd.toArray(new String[0]),
          new PrintStream(outStream),
          new PrintStream(errStream),
          Execution.Timeout.LONG
      );

      String output = outStream.toString();
      String error = errStream.toString();

      if (!output.isEmpty()) context.getReporter().info("R8 Output:\n" + output);
      if (!error.isEmpty()) context.getReporter().error("R8 Error:\n" + error);

      return success;
    }
  }

  /**
   * Pre-dex a library using R8.
   */
  private File preDexLibrary(AndroidCompilerContext context, File input) throws IOException {
    synchronized (PREDEX_CACHE) {
      File cacheDir = new File(context.getDexCacheDir());
      File cachedDex = getDexFileName(input, cacheDir);

      if (cachedDex.isFile()) {
        context.getReporter().info("Using cached: " + cachedDex.getName());
        return cachedDex;
      }

      List<String> cmd = Arrays.asList(
          "java",
          "-Xmx" + context.getChildProcessRam() + "M",
          "-Xss8m",
          "-cp", context.getResources().getR8Jar(),
          "com.android.tools.r8.R8",
          "--release",
          "--lib", context.getResources().getAndroidRuntime(),
          "--output", cacheDir.getAbsolutePath(),
          "--min-api", String.valueOf(AndroidBuildUtils.computeMinSdk(context)),
          "--no-desugaring",
          "--no-minification",
          input.getAbsolutePath()
      );

      context.getReporter().info("Pre-dexing: " + input.getName());

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ByteArrayOutputStream err = new ByteArrayOutputStream();

      boolean success = Execution.execute(context.getPaths().getTmpDir(),
          cmd.toArray(new String[0]),
          new PrintStream(out), new PrintStream(err),
          Execution.Timeout.LONG);

      if (success) {
        File result = new File(cacheDir, "classes.dex");
        if (result.exists()) {
          Files.move(result.toPath(), cachedDex.toPath(), StandardCopyOption.REPLACE_EXISTING);
          return cachedDex;
        }
      }

      context.getReporter().warn("R8 pre-dex failed, falling back: " + input);
      return input;
    }
  }

  /**
   * Write ProGuard-style rules to temp file.
   */
  private File writeClassRulesToFile(File dir, Set<String> classes) throws IOException {
    File rules = new File(dir, "main_dex.pro");
    try (PrintWriter w = new PrintWriter(new FileWriter(rules))) {
      for (String cls : classes) {
        w.println("-keep class " + cls + " { *; }");
      }
    }
    return rules;
  }
                                                            }
