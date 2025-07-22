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
import java.util.*;

@BuildType(aab = true, apk = true)
public class RunR8 extends DexTask implements AndroidTask {

  private static final boolean USE_D8_PROGUARD_RULES = true;

  @Override
  public TaskResult execute(AndroidCompilerContext context) {
    Set<String> mainDexClasses = new HashSet<>();
    final List<File> inputs = new ArrayList<>();

    try {
      // Collect user .class files
      recordForMainDex(context.getPaths().getClassesDir(), mainDexClasses);

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

      Set<String> addedExtJars = new HashSet<>();
      for (String type : context.getExtCompTypes()) {
        String sourcePath = ExecutorUtils.getExtCompDirPath(type, context.getProject(),
            context.getExtTypePathCache()) + context.getResources().getSimpleAndroidRuntimeJarPath();
        if (!addedExtJars.contains(sourcePath)) {
          inputs.add(new File(sourcePath));
          addedExtJars.add(sourcePath);
        }
      }

      // Walk all .class files
      Files.walkFileTree(context.getPaths().getClassesDir().toPath(), new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          if (file.toString().endsWith(".class")) {
            inputs.add(file.toFile());
          }
          return FileVisitResult.CONTINUE;
        }
      });

      // Only use --main-dex-rules if min-api < 21
      int minSdk = AndroidBuildUtils.computeMinSdk(context);
      boolean useMainDexRules = minSdk < 21;

      if (USE_D8_PROGUARD_RULES && useMainDexRules) {
        mainDexClasses.clear();
        mainDexClasses.add("com.google.appinventor.components.runtime.*");
        mainDexClasses.add("com.google.appinventor.components.runtime.**.*");
        mainDexClasses.add("kawa.**.*");
        mainDexClasses.add("androidx.core.content.FileProvider");
        mainDexClasses.add("androidx.appcompat.**.*");
        mainDexClasses.add("androidx.collection.*");
        mainDexClasses.add("androidx.vectordrawable.**.*");
        mainDexClasses.add(context.getProject().getMainClass());
      } else if (USE_D8_PROGUARD_RULES) {
        context.getReporter().info("Skipping --main-dex-rules: min-api " + minSdk + " >= 21");
      }

      // Run R8
      if (!runR8Final(context, inputs, useMainDexRules ? mainDexClasses : null)) {
        return TaskResult.generateError("R8 failed.");
      }

      // Check output
      File outputDex = new File(context.getPaths().getTmpDir(), "classes.dex");
      if (!outputDex.exists()) {
        context.getReporter().error("R8 did not produce classes.dex");
        return TaskResult.generateError("R8 did not generate classes.dex");
      }

      context.getResources().getDexFiles().add(outputDex);
      return TaskResult.generateSuccess();

    } catch (IOException e) {
      // ✅ Perbaikan: jangan lempar IOException ke .error()
      context.getReporter().error("Exception in RunR8: " + e.getMessage());
      return TaskResult.generateError(e); // ini benar: generateError(IOException)
    }
  }

  /**
   * Jalankan R8 untuk hasilkan classes.dex
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

    File outputDir = context.getPaths().getTmpDir();
    if (!outputDir.exists() && !outputDir.mkdirs()) {
      context.getReporter().error("Failed to create output dir: " + outputDir);
      return false;
    }

    cmd.add("--release");
    cmd.add("--lib");
    cmd.add(context.getResources().getAndroidRuntime());
    cmd.add("--output");
    cmd.add(outputDir.getAbsolutePath());
    cmd.add("--min-api");
    cmd.add(String.valueOf(AndroidBuildUtils.computeMinSdk(context)));
    cmd.add("--no-desugaring");
    cmd.add("--no-minification");

    cmd.add("--classpath");
    cmd.add(context.getPaths().getClassesDir().getAbsolutePath());

    // Hanya tambahkan aturan main-dex jika min-api < 21
    if (mainDexClasses != null && !mainDexClasses.isEmpty()) {
      File rulesFile = writeClassRulesToFile(context.getPaths().getTmpDir(), mainDexClasses);
      cmd.add("--main-dex-rules");
      cmd.add(rulesFile.getAbsolutePath());
      context.getReporter().info("Using main dex rules: " + rulesFile.getName());
    }

    for (File input : inputs) {
      cmd.add(input.getAbsolutePath());
    }

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
   * Pre-dex library
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
          return cachedD
