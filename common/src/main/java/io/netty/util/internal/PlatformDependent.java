/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.internal;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;

/**
 * Utility that detects various properties specific to the current runtime
 * environment, such as Java version.
 */
public final class PlatformDependent {

    private PlatformDependent() {
        // Utility class
    }

    /**
     * Creates a temporary file with more secure default permissions than File.createTempFile.
     * On Java 7+, uses Files.createTempFile which creates files with owner-only permissions.
     * On Java 6, falls back to File.createTempFile and attempts to restrict permissions.
     *
     * @param prefix    the prefix string to be used in generating the file's name
     * @param suffix    the suffix string to be used in generating the file's name
     * @param directory the directory in which the file is to be created, or null to use the default temp directory
     * @return the created file
     * @throws IOException if a file could not be created
     */
    public static File createTempFile(String prefix, String suffix, File directory) throws IOException {
        if (DetectionUtil.javaVersion() >= 7) {
            // Use Java 7+ Files.createTempFile which has secure permissions by default
            try {
                Class<?> filesClass = Class.forName("java.nio.file.Files");
                Class<?> pathClass = Class.forName("java.nio.file.Path");

                if (directory == null) {
                    Method createTempFileMethod = filesClass.getMethod("createTempFile", String.class, String.class,
                        java.lang.reflect.Array.newInstance(Class.forName("java.nio.file.attribute.FileAttribute"), 0).getClass());
                    Object path = createTempFileMethod.invoke(null, prefix, suffix,
                        java.lang.reflect.Array.newInstance(Class.forName("java.nio.file.attribute.FileAttribute"), 0));
                    Method toFileMethod = pathClass.getMethod("toFile");
                    return (File) toFileMethod.invoke(path);
                } else {
                    Method toPathMethod = File.class.getMethod("toPath");
                    Object dirPath = toPathMethod.invoke(directory);
                    Method createTempFileMethod = filesClass.getMethod("createTempFile", pathClass, String.class, String.class,
                        java.lang.reflect.Array.newInstance(Class.forName("java.nio.file.attribute.FileAttribute"), 0).getClass());
                    Object path = createTempFileMethod.invoke(null, dirPath, prefix, suffix,
                        java.lang.reflect.Array.newInstance(Class.forName("java.nio.file.attribute.FileAttribute"), 0));
                    Method toFileMethod = pathClass.getMethod("toFile");
                    return (File) toFileMethod.invoke(path);
                }
            } catch (Exception e) {
                // Fall through to Java 6 code
            }
        }

        // Java 6 fallback with manual permission adjustment
        File file;
        if (directory == null) {
            file = File.createTempFile(prefix, suffix);
        } else {
            file = File.createTempFile(prefix, suffix, directory);
        }

        // Try to adjust the permissions to be more restrictive
        // Make file readable only by owner
        file.setReadable(false, false);  // Remove read for everyone
        file.setReadable(true, true);     // Add read for owner only

        return file;
    }
}
