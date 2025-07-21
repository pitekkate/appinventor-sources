// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2023 MIT, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

package com.google.appinventor.buildserver.tasks.android;

import com.google.appinventor.buildserver.BuildType;
import com.google.appinventor.buildserver.Compiler;
import com.google.appinventor.buildserver.context.AndroidCompilerContext;
import com.google.appinventor.buildserver.context.AndroidPaths;
import com.google.appinventor.buildserver.tasks.common.BuildFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The AndroidBuildFactory is responsible for setting up the sequence of tasks
 * needed to compile an Android app from an App Inventor project. The factory
 * supports creating both Android Packages (APKs) and Android App Bundles (AABs).
 */
public class AndroidBuildFactory extends BuildFactory<AndroidPaths, AndroidCompilerContext> {
  private static final Logger LOG = Logger.getLogger(AndroidBuildFactory.class.getName());
  
  // Opsi konfigurasi (bisa diubah sesuai kebutuhan)
  private static final boolean FORCE_D8 = true; // Set true untuk paksa D8 di JDK 8
  private static final boolean USE_D8 = shouldUseD8();

  private static boolean shouldUseD8() {
    // 1. Jika dipaksa pakai D8, langsung return true
    if (FORCE_D8) {
      LOG.info("D8 dexer dipaksa aktif (FORCE_D8=true)");
      return true;
    }

    // 2. Ambil versi Java
    String versionStr = System.getProperty("java.specification.version");
    int majorVersion = 8; // Default ke Java 8
    
    try {
      if (versionStr.startsWith("1.")) {
        // Format lama: 1.x
        majorVersion = Integer.parseInt(versionStr.substring(2, 3));
      } else {
        // Format baru: x (angka bulat)
        majorVersion = Integer.parseInt(versionStr);
      }
    } catch (NumberFormatException | IndexOutOfBoundsException e) {
      LOG.log(Level.WARNING, "Gagal parse Java version: " + versionStr + ", asumsikan Java 8", e);
    }

    // 3. Logika utama: JDK 9+ gunakan D8
    boolean useD8 = majorVersion >= 9;
    LOG.info("Menggunakan D8 dexer: " + useD8 + " (Java major version: " + majorVersion + ")");
    return useD8;
  }

  private final boolean isAab;

  public static void install() {
    register(BuildType.APK_EXTENSION, new AndroidBuildFactory(false));
    register(BuildType.AAB_EXTENSION, new AndroidBuildFactory(true));
  }

  protected AndroidBuildFactory(boolean isAab) {
    super(isAab ? "aab" : "apk");
    this.isAab = isAab;
  }

  @Override
  protected void prepareAppIcon(Compiler<AndroidPaths, AndroidCompilerContext> compiler) {
    super.prepareAppIcon(compiler);
    compiler.add(PrepareAppIcon.class);
  }

  @Override
  protected void prepareMetadata(Compiler<AndroidPaths, AndroidCompilerContext> compiler) {
    super.prepareMetadata(compiler);
    compiler.add(XmlConfig.class);
    compiler.add(CreateManifest.class);
  }

  @Override
  protected void attachLibraries(Compiler<AndroidPaths, AndroidCompilerContext> compiler) {
    super.attachLibraries(compiler);
    compiler.add(AttachNativeLibs.class);
    compiler.add(AttachAarLibs.class);
    compiler.add(AttachCompAssets.class);
  }

  @Override
  protected void processAssets(Compiler<AndroidPaths, AndroidCompilerContext> compiler) {
    super.processAssets(compiler);
    compiler.add(MergeResources.class);
    compiler.add(SetupLibs.class);
    compiler.add(isAab ? RunAapt2.class : RunAapt.class);
  }

  @Override
  protected void compileSources(Compiler<AndroidPaths, AndroidCompilerContext> compiler) {
    super.compileSources(compiler);
    compiler.add(GenerateClasses.class);
    
    // Tambahkan log untuk debugging
    LOG.info("Memilih dexer: " + (USE_D8 ? "D8" : "DX Multidex"));
    compiler.add(USE_D8 ? RunD8.class : RunMultidex.class);
  }

  @Override
  protected void createAppPackage(Compiler<AndroidPaths, AndroidCompilerContext> compiler) {
    super.createAppPackage(compiler);
    compiler.add(isAab ? RunBundletool.class : RunApkBuilder.class);
  }

  @Override
  protected void signApp(Compiler<AndroidPaths, AndroidCompilerContext> compiler) {
    super.signApp(compiler);
    if (!isAab) {
      compiler.add(RunZipAlign.class);
      compiler.add(RunApkSigner.class);
    }
  }

  @Override
  protected void createOutputBundle(Compiler<AndroidPaths, AndroidCompilerContext> compiler) {
    super.createOutputBundle(compiler);
  }

  @Override
  public Class<AndroidCompilerContext> getContextClass() {
    return AndroidCompilerContext.class;
  }
  
  // Untuk debugging
  static {
    System.setProperty("com.android.tools.r8.allowTestProguardOptions", "true");
    if (FORCE_D8) {
      System.setProperty("com.android.tools.r8.disableDesugaring", "false");
    }
    LOG.config("Konfigurasi D8 - FORCE_D8: " + FORCE_D8 + ", USE_D8: " + USE_D8);
  }
}
