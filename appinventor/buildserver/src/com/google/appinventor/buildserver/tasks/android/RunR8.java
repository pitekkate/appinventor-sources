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

      Files.walkFileTree(context.getPaths().getClassesDir().toPath(), new FileVisitor<Path>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (file.toString().endsWith(".class")) {
            inputs.add(file.toFile());
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
          return FileVisitResult.CONTINUE;
        }
      });

      // Gunakan aturan main-dex hanya jika min-api < 21
      int minSdk = AndroidBuildUtils.computeMinSdk(context);
      if (USE_D8_PROGUARD_RULES) {
        mainDexClasses.clear();
        mainDexClasses.add("com.google.appinventor.components.runtime.*");
        mainDexClasses.add("com.google.appinventor.components.runtime.**.*");
        mainDexClasses.add("kawa.**.*");
        mainDexClasses.add("androidx.core.content.FileProvider");
        mainDexClasses.add("androidx.appcompat.**.*");
        mainDexClasses.add("androidx.collection.*");
        mainDexClasses.add("androidx.vectordrawable.**.*");
        mainDexClasses.add(context.getProject().getMainClass());
      }

      // Hanya gunakan --main-dex-rules jika min-api < 21
      boolean useMainDexRules = minSdk < 21 && !mainDexClasses.isEmpty();

      if (!runR8(context, inputs, useMainDexRules ? mainDexClasses : null)) {
        return TaskResult.generateError("R8 failed.");
      }

      // Cari classes.dex di tmp/
      File[] dexFiles = context.getPaths().getTmpDir().listFiles((dir, name) -> name.endsWith(".dex"));
      if (dexFiles == null || dexFiles.length == 0) {
        throw new FileNotFoundException("R8 did not produce any .dex files");
      }

      Collections.addAll(context.getResources().getDexFiles(), dexFiles);
      return TaskResult.generateSuccess();

    } catch (IOException e) {
      return TaskResult.generateError(e);
    }
  }

  private static boolean runR8(AndroidCompilerContext context, Collection<File> inputs,
                               Set<String> mainDexClasses) throws IOException {
    return runR8(context, inputs, mainDexClasses, context.getPaths().getTmpDir().getAbsolutePath(), null);
  }

  private static boolean runR8(AndroidCompilerContext context, Collection<File> inputs,
                               Set<String> mainDexClasses, String outputDir, String intermediateFileName)
      throws IOException {

    List<String> arguments = new ArrayList<>();
    List<String> javaArgs = new ArrayList<>();

    arguments.add("java");
    javaArgs.add("-Xmx" + context.getChildProcessRam() + "M");
    javaArgs.add("-Xss8m");
    javaArgs.add("-cp");
    javaArgs.add(context.getResources().getR8Jar());
    javaArgs.add("com.android.tools.r8.R8");

    javaArgs.add("--release");
    javaArgs.add("--lib");
    javaArgs.add(context.getResources().getAndroidRuntime());
    javaArgs.add("--output");
    javaArgs.add(outputDir);
    javaArgs.add("--min-api");
    javaArgs.add(Integer.toString(AndroidBuildUtils.computeMinSdk(context)));
    javaArgs.add("--no-desugaring");
    javaArgs.add("--no-minification");

    if (intermediateFileName == null) {
      javaArgs.add("--classpath");
      javaArgs.add(context.getPaths().getClassesDir().getAbsolutePath());
    }

    // 🔥 HANYA TAMBAHKAN --main-dex-rules JIKA mainDexClasses != null DAN min-api < 21
    if (mainDexClasses != null && !mainDexClasses.isEmpty()) {
      javaArgs.add("--main-dex-rules");
      String rulesFile = writeClassRules(context.getPaths().getClassesDir(), mainDexClasses);
      context.getReporter().info("Using main dex rules: " + rulesFile);
      javaArgs.add(rulesFile);
    }

    for (File input : inputs) {
      javaArgs.add(input.getAbsolutePath());
    }

    context.getReporter().info("Executing R8 with arguments: " + String.join(" ", javaArgs));

    File javaArgsFile = new File(context.getPaths().getTmpDir(), "r8arguments.txt");
    try (PrintStream ps = new PrintStream(new FileOutputStream(javaArgsFile))) {
      for (String arg : javaArgs) {
        ps.println(arg);
      }
    }

    arguments.add("@" + javaArgsFile.getAbsolutePath());

    ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
    ByteArrayOutputStream errorBuffer = new ByteArrayOutputStream();
    PrintStream outStream = new PrintStream(outputBuffer);
    PrintStream errStream = new PrintStream(errorBuffer);

    synchronized (context.getResources().getSyncKawaOrDx()) {
      boolean result = Execution.execute(
          context.getPaths().getTmpDir(),
          arguments.toArray(new String[0]),
          outStream, errStream, Execution.Timeout.LONG
      );

      String outputStr = outputBuffer.toString();
      String errorStr = errorBuffer.toString();

      if (!outputStr.isEmpty()) {
        context.getReporter().info("R8 Output:\n" + outputStr);
      }
      if (!errorStr.isEmpty()) {
        context.getReporter().error("R8 Error:\n" + errorStr);
      }

      if (!result) {
        return false;
      }
    }

    // Pindahkan classes.dex jika diperlukan (pre-dex)
    if (intermediateFileName != null) {
      Path source = FileSystems.getDefault().getPath(outputDir, "classes.dex");
      Path target = FileSystems.getDefault().getPath(outputDir, intermediateFileName);
      context.getReporter().info("Moving " + source + " to " + target);
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    return true;
  }

  private static File preDexLibrary(AndroidCompilerContext context, File input) throws IOException {
    synchronized (PREDEX_CACHE) {
      File cacheDir = new File(context.getDexCacheDir());
      File dexedLib = getDexFileName(input, cacheDir);

      if (dexedLib.isFile()) {
        context.getReporter().info(String.format("Using cached: %s <- %s", dexedLib.getName(), input));
        return dexedLib;
      }

      boolean success = runR8(context, Collections.singleton(input), null,
          context.getDexCacheDir(), dexedLib.getName());

      if (!success) {
        context.getReporter().warn("R8 pre-dex failed, falling back: " + input);
        return input;
      }

      return dexedLib;
    }
  }
}
