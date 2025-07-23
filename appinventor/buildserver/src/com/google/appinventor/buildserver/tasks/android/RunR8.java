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
import com.google.appinventor.buildserver.util.AndroidBuildUtils;

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
            File classesDir = context.getPaths().getClassesDir();
            if (!classesDir.exists() || !classesDir.isDirectory()) {
                context.getReporter().error("❌ Classes directory not found: " + classesDir);
                return TaskResult.generateError("Classes directory missing");
            }

            // Validasi R8 JAR dan Android.jar
            File r8Jar = new File(context.getResources().getR8Jar());
            File androidJar = new File(context.getResources().getAndroidRuntime());
            if (!r8Jar.exists()) {
                context.getReporter().error("❌ R8 JAR not found: " + r8Jar);
                return TaskResult.generateError("R8 JAR missing");
            }
            if (!androidJar.exists()) {
                context.getReporter().error("❌ Android runtime JAR not found: " + androidJar);
                return TaskResult.generateError("Android runtime missing");
            }

            // Kumpulkan kelas utama untuk main-dex
            recordForMainDex(classesDir, mainDexClasses);

            // Tambahkan runtime dan library penting (dengan pre-dex)
            inputs.add(preDexLibrary(context, recordForMainDex(
                createTempCopy(new File(context.getResources().getSimpleAndroidRuntimeJar())), mainDexClasses)));
            inputs.add(preDexLibrary(context, recordForMainDex(
                createTempCopy(new File(context.getResources().getKawaRuntime())), mainDexClasses)));

            Set<String> criticalJars = getCriticalJars(context);
            for (String jar : criticalJars) {
                File file = new File(context.getResource(jar));
                if (file.exists()) {
                    inputs.add(preDexLibrary(context, recordForMainDex(createTempCopy(file), mainDexClasses)));
                } else {
                    context.getReporter().warn("Critical JAR not found: " + jar);
                }
            }

            if (context.isForCompanion()) {
                inputs.add(preDexLibrary(context, recordForMainDex(
                    createTempCopy(new File(context.getResources().getAcraRuntime())), mainDexClasses)));
            }

            // Tambahkan support JAR
            for (String jar : context.getResources().getSupportJars()) {
                if (criticalJars.contains(jar)) continue;
                File file = new File(context.getResource(jar));
                if (file.exists()) {
                    inputs.add(preDexLibrary(context, createTempCopy(file)));
                }
            }

            // Tambahkan library komponen eksternal
            for (String lib : context.getComponentInfo().getUniqueLibsNeeded()) {
                File file = new File(lib);
                if (file.exists()) {
                    inputs.add(preDexLibrary(context, createTempCopy(file)));
                } else {
                    context.getReporter().warn("Library not found: " + lib);
                }
            }

            // Tambahkan ekstensi komponen
            Set<String> addedExtJars = new HashSet<>();
            for (String type : context.getExtCompTypes()) {
                String sourcePath = ExecutorUtils.getExtCompDirPath(type, context.getProject(), context.getExtTypePathCache())
                    + context.getResources().getSimpleAndroidRuntimeJarPath();
                if (!addedExtJars.contains(sourcePath)) {
                    File extJar = new File(sourcePath);
                    if (extJar.exists()) {
                        inputs.add(createTempCopy(extJar));
                        addedExtJars.add(sourcePath);
                    }
                }
            }

            // Tambahkan semua file .class dari user
            Files.walkFileTree(classesDir.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.toString().endsWith(".class")) {
                        inputs.add(file.toFile());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            // Tentukan minSdk
            int minSdk = AndroidBuildUtils.computeMinSdk(context);
            if (context.isForCompanion()) {
                minSdk = 20;
                context.getReporter().info("⚠️ Forced min-api 20 for Companion to support main-dex rules");
            }

            boolean useMainDexRules = false;
            if (USE_D8_PROGUARD_RULES && minSdk < 21) {
                mainDexClasses.clear();
                mainDexClasses.add("com.google.appinventor.components.runtime.*");
                mainDexClasses.add("com.google.appinventor.components.runtime.**.*");
                mainDexClasses.add("kawa.**.*");
                mainDexClasses.add("androidx.core.content.FileProvider");
                mainDexClasses.add("androidx.appcompat.**.*");
                mainDexClasses.add("androidx.collection.*");
                mainDexClasses.add("androidx.vectordrawable.**.*");
                mainDexClasses.add(context.getProject().getMainClass());
                useMainDexRules = true;
            }

            // Jalankan R8
            if (!runR8Final(context, inputs, useMainDexRules ? mainDexClasses : null, minSdk)) {
                return TaskResult.generateError("R8 failed.");
            }

            // Verifikasi hasil
            File outputDir = context.getPaths().getTmpDir();
            File outputDex = new File(outputDir, "classes.dex");
            if (!outputDex.exists()) {
                context.getReporter().error("❌ FATAL: R8 did not produce classes.dex");
                File[] files = outputDir.listFiles();
                if (files != null && files.length > 0) {
                    context.getReporter().error("📁 Output directory contains:");
                    for (File f : files) {
                        context.getReporter().error("   " + f.getName() + " → " + f.length() + " bytes");
                    }
                } else {
                    context.getReporter().error("📁 Output directory is empty");
                }
                return TaskResult.generateError("No classes.dex generated");
            }

            context.getReporter().info("✅ Successfully generated classes.dex: " + outputDex.length() + " bytes");
            context.getResources().getDexFiles().add(outputDex);
            return TaskResult.generateSuccess();

        } catch (Exception e) {
            context.getReporter().error("Exception in RunR8: " + e.getMessage());
            e.printStackTrace();
            return TaskResult.generateError(e);
        }
    }

    /**
     * Salin file ke file sementara
     */
    private File createTempCopy(File src) throws IOException {
        if (src == null || !src.isFile()) return src;

        String prefix;
        if (src.getName().contains("kawa")) {
            prefix = "r8-kawa";
        } else if (src.getName().contains("AndroidRuntime")) {
            prefix = "r8-runtime";
        } else if (src.getName().contains("acra")) {
            prefix = "r8-acra";
        } else if (src.getName().contains("firebase")) {
            prefix = "r8-firebase";
        } else {
            prefix = "r8-input";
        }

        File tempFile = File.createTempFile(prefix + "-", ".jar");
        tempFile.deleteOnExit();
        Files.copy(src.toPath(), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return tempFile;
    }

    /**
     * Jalankan R8 untuk hasilkan classes.dex
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

        File finalOutputDir = new File(context.getPaths().getTmpDir(), "r8-output");
        deleteDirectory(finalOutputDir);
        if (!finalOutputDir.mkdirs()) {
            context.getReporter().error("Failed to create output directory: " + finalOutputDir);
            return false;
        }

        cmd.add("--release");
        cmd.add("--lib");
        cmd.add(context.getResources().getAndroidRuntime());
        cmd.add("--output");
        cmd.add(finalOutputDir.getAbsolutePath());
        cmd.add("--min-api");
        cmd.add(String.valueOf(minSdk));
        cmd.add("--no-desugaring");
        cmd.add("--no-minification");

        // ✅ HAPUS: --allow-duplicate-resource-values TIDAK VALID
        // Tidak ada cara langsung lewat R8 — atasi di level resource

        // Main-dex rules
        if (mainDexClasses != null && !mainDexClasses.isEmpty()) {
            File rulesFile = writeClassRulesToFile(context.getPaths().getTmpDir(), mainDexClasses);
            cmd.add("--main-dex-rules");
            cmd.add(rulesFile.getAbsolutePath());
            context.getReporter().info("Using main dex rules: " + rulesFile.getName());
        }

        // Kumpulkan semua input
        File inputsFile = new File(context.getPaths().getTmpDir(), "r8-inputs.txt");
        Set<String> uniqueInputs = new HashSet<>();

        Files.walk(context.getPaths().getClassesDir().toPath())
             .filter(path -> path.toString().endsWith(".class"))
             .map(Path::toAbsolutePath)
             .map(Path::toString)
             .forEach(uniqueInputs::add);

        for (File input : inputs) {
            if (input.exists()) {
                uniqueInputs.add(input.getAbsolutePath());
            } else {
                context.getReporter().warn("Input missing in R8: " + input);
            }
        }

        try (PrintWriter w = new PrintWriter(new FileWriter(inputsFile))) {
            for (String input : uniqueInputs) {
                w.println(input);
            }
        }

        cmd.add("@" + inputsFile.getAbsolutePath());

        // Log command
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
     * Hapus direktori rekursif
     */
    private void deleteDirectory(File dir) throws IOException {
        if (dir == null || !dir.exists()) return;
        if (dir.isDirectory()) {
            for (File child : Objects.requireNonNull(dir.listFiles())) {
                deleteDirectory(child);
            }
        }
        if (!dir.delete()) {
            throw new IOException("Failed to delete: " + dir);
        }
    }

    /**
     * Pre-dex library
     */
    private File preDexLibrary(AndroidCompilerContext context, File input) throws IOException {
        synchronized (PREDEX_CACHE) {
            File cacheDir = new File(context.getDexCacheDir());
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                context.getReporter().warn("Failed to create dex cache: " + cacheDir);
            }

            File cachedDex = getDexFileName(input, cacheDir);
            if (cachedDex.isFile()) {
                context.getReporter().info("Using cached dex: " + cachedDex.getName());
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

            boolean success = Execution.execute(
                context.getPaths().getTmpDir(),
                cmd.toArray(new String[0]),
                new PrintStream(out),
                new PrintStream(err),
                Execution.Timeout.LONG
            );

            if (!out.toString().isEmpty()) context.getReporter().info("R8 Output:\n" + out.toString());
            if (!err.toString().isEmpty()) context.getReporter().error("R8 Error:\n" + err.toString());

            if (success) {
                File result = new File(cacheDir, "classes.dex");
                if (result.exists()) {
                    Files.move(result.toPath(), cachedDex.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    return cachedDex;
                }
            }

            context.getReporter().warn("R8 pre-dex failed, falling back to JAR: " + input);
            return input;
        }
    }

    /**
     * Tulis aturan main-dex dalam format ProGuard
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
