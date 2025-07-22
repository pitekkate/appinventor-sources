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
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

@BuildType(aab = true, apk = true)
public class RunR8 extends DexTask implements AndroidTask {

  private static final boolean USE_D8_PROGUARD_RULES = true;

  @Override
  public TaskResult execute(AndroidCompilerContext context) {
    Set<String> mainDexClasses = new HashSet<>();
    final List<File> inputs = new ArrayList<>();

    try {
      // Kumpulkan semua .class user
      recordForMainDex(context.getPaths().getClassesDir(), mainDexClasses);

      // Pre-dex library utama
      inputs.add(preDexLibrary(context, recordForMainDex(
          new File(context.getResources().getSimpleAndroidRuntimeJar()), mainDexClasses)));
      inputs.add(preDexLibrary(context, recordForMainDex(
          new File(context.getResources().getKawaRuntime()), mainDexClasses)));

      // Library kritis
      final Set<String> criticalJars = getCriticalJars(context);
      for (String jar : criticalJars) {
        inputs.add(preDexLibrary(context, recordForMainDex(
            new File(context.getResource(jar)), mainDexClasses)));
      }

      if (context.isForCompanion()) {
        inputs.add(preDexLibrary(context, recordForMainDex(
            new File(context.getResources().getAcraRuntime()), mainDexClasses)));
      }

      // Support JARs
      for (String jar : context.getResources().getSupportJars()) {
        if (criticalJars.contains(jar)) continue;
        inputs.add(preDexLibrary(context, new File(context.getResource(jar))));
      }

      // Library tambahan
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

      // Walk semua .class file
      Files.walkFileTree(context.getPaths().getClassesDir().toPath(), new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          if (file.toString().endsWith(".class")) {
            inputs.add(file.toFile());
          }
          return FileVisitResult.CONTINUE;
        }
      });

      // Tentukan apakah pakai --main-dex-rules
      int minSdk = AndroidBuildUtils.computeMinSdk(context);
      boolean useMainDexRules = false;

      if (USE_D8_PROGUARD_RULES) {
        // Untuk Companion, turunkan ke API 20 agar bisa pakai --main-dex-rules
        if (context.isForCompanion()) {
          minSdk = 14;
          context.getReporter().info("⚠️ Forced min-api 14 for Companion to support main-dex rules");
        }

        mainDexClasses.clear();
        mainDexClasses.add("com.google.appinventor.components.runtime.*");
        mainDexClasses.add("com.google.appinventor.components.runtime.**.*");
        mainDexClasses.add("kawa.**.*");
        mainDexClasses.add("androidx.core.content.FileProvider");
        mainDexClasses.add("androidx.appcompat.**.*");
        mainDexClasses.add("androidx.collection.*");
        mainDexClasses.add("androidx.vectordrawable.**.*");
        mainDexClasses.add(context.getProject().getMainClass());

        useMainDexRules = minSdk < 21;
      }

      // Jalankan R8
      if (!runR8Final(context, inputs, useMainDexRules ? mainDexClasses : null, minSdk)) {
        return TaskResult.generateError("R8 failed.");
      }

      // Cek output: pastikan classes.dex ada
      File outputDir = context.getPaths().getTmpDir();
      File outputDex = new File(outputDir, "classes.dex");

      if (!outputDex.exists()) {
        context.getReporter().error("❌ FATAL: R8 did not produce classes.dex at " + outputDex.getAbsolutePath());

        // Tampilkan isi direktori
        File[] files = outputDir.listFiles();
        if (files != null && files.length > 0) {
          context.getReporter().error("📁 Output directory contains:");
          for (File f : files) {
            context.getReporter().error("   " + f.getName() + " → " + f.length() + " bytes");
          }
        } else {
          context.getReporter().error("📁 Output directory is empty or inaccessible");
        }

        return TaskResult.generateError("R8 did not generate classes.dex");
      }

      context.getReporter().info("✅ Successfully generated classes.dex: " + outputDex.length() + " bytes");
      context.getResources().getDexFiles().add(outputDex);

      return TaskResult.generateSuccess();

    } catch (IOException e) {
      context.getReporter().error("Exception in RunR8: " + e.getMessage());
      return TaskResult.generateError(e);
    }
  }

  /**
   * Jalankan R8 untuk menghasilkan classes.dex
   */
  private boolean runR8Final(AndroidCompilerContext context, Collection<File> inputs,
                             Set<String> mainDexClasses, int minSdk) throws IOException {
    List<String> cmd = new ArrayList<>();
    cmd.add("java");
    cmd.add("-Xmx" + context.getChildProcessRam() + "M");
    cmd.add("-Xss8m");
    cmd.add("-cp");
    cmd.add(context.getResources().getR8Jar());
    cmd.add("com.android.tools.r8.R8");

    // Pastikan output dir ada
    File outputDir = context.getPaths().getTmpDir();
    if (!outputDir.exists() && !outputDir.mkdirs()) {
      context.getReporter().error("Failed to create output directory: " + outputDir);
      return false;
    }

    cmd.add("--release");
    cmd.add("--lib");
    cmd.add(context.getResources().getAndroidRuntime());
    cmd.add("--output");
    cmd.add(outputDir.getAbsolutePath());
    cmd.add("--min-api");
    cmd.add(String.valueOf(minSdk));  // Bisa 20 jika Companion
    cmd.add("--no-desugaring");
    cmd.add("--no-minification");

    // Classpath
    cmd.add("--classpath");
    cmd.add(context.getPaths().getClassesDir().getAbsolutePath());

    // Hanya gunakan --main-dex-rules jika min-api < 21
    if (mainDexClasses != null && !mainDexClasses.isEmpty()) {
      File rulesFile = writeClassRulesToFile(context.getPaths().getTmpDir(), mainDexClasses);
      cmd.add("--main-dex-rules");
      cmd.add(rulesFile.getAbsolutePath());
      context.getReporter().info("Using main dex rules: " + rulesFile.getName());
    }

    // Input files
    for (File input : inputs) {
      cmd.add(input.getAbsolutePath());
    }

    // Logging
    context.getReporter().info("Executing R8 command:");
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
   * Pre-dex library menggunakan R8
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
   * Tulis aturan ProGuard-style ke file
   */
  private File writeClassRulesToFile(File dir, Set<String> classes) throws IOException {
    File rulesFile = new File(dir, "main_dex_rules.pro");
    try (PrintWriter w = new PrintWriter(new FileWriter(rulesFile))) {
      for (String cls : classes) {
        w.println("-keep class " + cls + " { *; }");
      }
    }
    return rulesFile;
  }
                                  }
