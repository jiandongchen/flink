/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.client.program;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.client.deployment.application.EntryClassInformationProvider;
import org.apache.flink.client.deployment.application.FromClasspathEntryClassInformationProvider;
import org.apache.flink.client.deployment.application.FromJarEntryClassInformationProvider;
import org.apache.flink.util.FileUtils;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.function.FunctionUtils;

import javax.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * {@code PackageProgramRetrieverImpl} is the default implementation of {@link
 * PackagedProgramRetriever} that can either retrieve a {@link PackagedProgram} from a specific jar,
 * some provided user classpath or the system classpath.
 */
public class DefaultPackagedProgramRetriever implements PackagedProgramRetriever {

    private final EntryClassInformationProvider entryClassInformationProvider;
    private final String[] programArguments;
    private final List<URL> userClasspath;

    /**
     * Creates a {@code PackageProgramRetrieverImpl} with the given parameters.
     *
     * @param userLibDir The user library directory that is used for generating the user classpath
     *     if specified. The system classpath is used if not specified.
     * @param jobClassName The job class that will be used if specified. The classpath is used to
     *     detect any main class if not specified.
     * @param programArgs The program arguments.
     * @return The {@code PackageProgramRetrieverImpl} that can be used to create a {@link
     *     PackagedProgram} instance.
     * @throws FlinkException If something goes wrong during instantiation.
     */
    public static DefaultPackagedProgramRetriever create(
            @Nullable File userLibDir,
            @Nullable String jobClassName,
            String[] programArgs)
            throws FlinkException {
        return create(userLibDir, null, jobClassName, programArgs);
    }

    /**
     * Creates a {@code PackageProgramRetrieverImpl} with the given parameters.
     *
     * @param userLibDir The user library directory that is used for generating the user classpath
     *     if specified. The system classpath is used if not specified.
     * @param jarFile The jar archive expected to contain the job class included; {@code null} if
     *     the job class is on the system classpath.
     * @param jobClassName The job class to use; if {@code null} the user classpath (or, if not set,
     *     the system classpath) will be scanned for possible main class.
     * @param programArgs The program arguments.
     * @return The {@code PackageProgramRetrieverImpl} that can be used to create a {@link
     *     PackagedProgram} instance.
     * @throws FlinkException If something goes wrong during instantiation.
     */
    public static DefaultPackagedProgramRetriever create(
            @Nullable File userLibDir,
            @Nullable File jarFile,
            @Nullable String jobClassName,
            String[] programArgs)
            throws FlinkException {
        List<URL> userClasspath;
        try {
            userClasspath = extractUserClasspath(userLibDir);
        } catch (IOException e) {
            throw new FlinkException("An error occurred while extracting the user classpath.", e);
        }

        final EntryClassInformationProvider entryClassInformationProvider =
                createEntryClassInformationProvider(
                        userLibDir == null ? null : userClasspath,
                        jarFile,
                        jobClassName,
                        programArgs);
        return new DefaultPackagedProgramRetriever(
                entryClassInformationProvider, programArgs, userClasspath);
    }

    @VisibleForTesting
    static EntryClassInformationProvider createEntryClassInformationProvider(
            @Nullable Iterable<URL> userClasspath,
            @Nullable File jarFile,
            @Nullable String jobClassName,
            String[] programArgs)
            throws FlinkException {
        if (PackagedProgramUtils.isPython(jobClassName)
                || PackagedProgramUtils.isPython(programArgs)) {
            return FromJarEntryClassInformationProvider.createFromPythonJar();
        }

        if (jarFile != null) {
            return FromJarEntryClassInformationProvider.createFromCustomJar(jarFile, jobClassName);
        }

        if (userClasspath != null) {
            return fromUserClasspath(jobClassName, userClasspath);
        }

        return fromSystemClasspath(jobClassName);
    }

    private static EntryClassInformationProvider fromSystemClasspath(@Nullable String jobClassName)
            throws FlinkException {
        if (jobClassName != null) {
            return FromClasspathEntryClassInformationProvider
                    .createWithJobClassAssumingOnSystemClasspath(jobClassName);
        }

        try {
            return FromClasspathEntryClassInformationProvider.createFromSystemClasspath();
        } catch (IOException | NoSuchElementException | IllegalArgumentException t) {
            throw createGenericFlinkException(t);
        }
    }

    private static EntryClassInformationProvider fromUserClasspath(
            @Nullable String jobClassName, Iterable<URL> userClasspath) throws FlinkException {
        try {
            if (jobClassName != null) {
                return FromClasspathEntryClassInformationProvider.create(
                        jobClassName, userClasspath);
            }

            return FromClasspathEntryClassInformationProvider.createFromClasspath(userClasspath);
        } catch (IOException e) {
            throw createGenericFlinkException(e);
        }
    }

    private static FlinkException createGenericFlinkException(Throwable t) {
        return new FlinkException("An error occurred while access the provided classpath.", t);
    }

    private DefaultPackagedProgramRetriever(
            EntryClassInformationProvider entryClassInformationProvider,
            String[] programArguments,
            List<URL> userClasspath) {
        this.entryClassInformationProvider =
                Preconditions.checkNotNull(
                        entryClassInformationProvider, "No EntryClassInformationProvider passed.");
        this.programArguments =
                Preconditions.checkNotNull(programArguments, "No program parameter array passed.");
        this.userClasspath = Preconditions.checkNotNull(userClasspath, "No user classpath passed.");
    }

    @Override
    public PackagedProgram getPackagedProgram() throws FlinkException {
        try {
            final PackagedProgram.Builder packagedProgramBuilder =
                    PackagedProgram.newBuilder()
                            .setUserClassPaths(userClasspath)
                            .setArguments(programArguments);

            entryClassInformationProvider
                    .getJobClassName()
                    .ifPresent(packagedProgramBuilder::setEntryPointClassName);
            entryClassInformationProvider
                    .getJarFile()
                    .ifPresent(packagedProgramBuilder::setJarFile);

            return packagedProgramBuilder.build();
        } catch (ProgramInvocationException e) {
            throw new FlinkException("Could not load the provided entrypoint class.", e);
        }
    }

    private static List<URL> extractUserClasspath(@Nullable File userLibDir) throws IOException {
        if (userLibDir == null) {
            return Collections.emptyList();
        }

        final Path workingDirectory = FileUtils.getCurrentWorkingDirectory();
        final List<URL> relativeJarURLs =
                FileUtils.listFilesInDirectory(userLibDir.toPath(), FileUtils::isJarFile).stream()
                        .map(path -> FileUtils.relativizePath(workingDirectory, path))
                        .map(FunctionUtils.uncheckedFunction(FileUtils::toURL))
                        .collect(Collectors.toList());
        return Collections.unmodifiableList(relativeJarURLs);
    }
}
