// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2023 MIT, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

package com.google.appinventor.buildserver.util;

import com.google.appinventor.buildserver.context.AndroidCompilerContext;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Utility class for Android build-related operations.
 */
public class AndroidBuildUtils {

    /**
     * Menghitung nilai minSdkVersion berdasarkan konfigurasi proyek.
     *
     * @param context Konteks kompilasi Android
     * @return nilai minSdk (default: 15)
     */
    public static int computeMinSdk(AndroidCompilerContext context) {
        // Nilai default
        int minSdk = 15;

        // Cek di project.properties
        File projectProps = new File(context.getPaths().getYoungAndroidPath(), "project.properties");
        if (projectProps.exists()) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(projectProps)) {
                props.load(fis);
                String minSdkStr = props.getProperty("android.minSdkVersion");
                if (minSdkStr != null) {
                    try {
                        minSdk = Integer.parseInt(minSdkStr.trim());
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid minSdkVersion in project.properties: " + minSdkStr);
                    }
                }
            } catch (IOException e) {
                System.err.println("Failed to read project.properties: " + e.getMessage());
            }
        }

        // Override untuk Companion
        if (context.isForCompanion()) {
            return 20; // Companion membutuhkan API 20 untuk main-dex rules
        }

        // Pastikan tidak kurang dari 14
        return Math.max(minSdk, 14);
    }
}
