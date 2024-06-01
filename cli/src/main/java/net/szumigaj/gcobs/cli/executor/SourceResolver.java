package net.szumigaj.gcobs.cli.executor;

import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.szumigaj.gcobs.cli.model.config.SourceConfig;
import net.szumigaj.gcobs.cli.model.config.SourceType;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;

@Slf4j
@Singleton
public class SourceResolver {

    public ResolvedSource resolve(SourceConfig source, Path projectRoot) {
        if (source.type() == null) {
            throw new SourceResolverException("source.type is required");
        }
        return switch (source.type()) {
            case INTERNAL -> resolveInternal(source, projectRoot);
            case JAR -> resolveJar(source, projectRoot);
            case GRADLE -> resolveGradle(source, projectRoot);
        };
    }

    private ResolvedSource resolveInternal(SourceConfig source, Path projectRoot) {
        if (source.module() == null || source.module().isBlank()) {
            throw new SourceResolverException("source.module is required for type: internal");
        }

        Path moduleDir = projectRoot.resolve("benchmarks").resolve(source.module());
        if (!Files.exists(moduleDir.resolve("build.gradle"))) {
            throw new SourceResolverException("Internal module \"%s\" not found at %s".formatted(source.module(), moduleDir));
        }

        String gradleTask = ":benchmarks:" + source.module() + ":runJmh";
        return new ResolvedSource(SourceType.INTERNAL, gradleTask, moduleDir);
    }

    private ResolvedSource resolveJar(SourceConfig source, Path projectRoot) {
        if (source.path() == null || source.path().isBlank()) {
            throw new SourceResolverException("source.path is required for type: jar");
        }

        Path jarPath = Path.of(source.path());
        if (!jarPath.isAbsolute()) {
            jarPath = projectRoot.resolve(jarPath);
        }

        if (!Files.exists(jarPath)) {
            throw new SourceResolverException("JAR file not found: " + jarPath);
        }
        if (!Files.isReadable(jarPath)) {
            throw new SourceResolverException("JAR file not readable: " + jarPath);
        }
        if (!jarPath.getFileName().toString().endsWith(".jar")) {
            throw new SourceResolverException("source.path must point to a .jar file: " + jarPath);
        }

        return new ResolvedSource(SourceType.JAR, null, null, jarPath);
    }

    private ResolvedSource resolveGradle(SourceConfig source, Path projectRoot) {
        if (source.projectDir() == null || source.projectDir().isBlank()) {
            throw new SourceResolverException("source.projectDir is required for type: gradle");
        }

        Path projectDir = Path.of(source.projectDir());
        if (!projectDir.isAbsolute()) {
            projectDir = projectRoot.resolve(projectDir);
        }

        if (!Files.isDirectory(projectDir)) {
            throw new SourceResolverException("source.projectDir not found: " + projectDir);
        }

        boolean hasBuildFile = Files.exists(projectDir.resolve("build.gradle"))
                || Files.exists(projectDir.resolve("build.gradle.kts"));
        if (!hasBuildFile) {
            throw new SourceResolverException("No build.gradle or build.gradle.kts in projectDir: " + projectDir);
        }

        // Defaults
        String gradleExec = source.gradleExecutable() != null ? source.gradleExecutable() : "./gradlew";
        String buildTask = source.buildTask() != null ? source.buildTask() : "shadowJar";
        String jarPattern = source.jarPattern() != null ? source.jarPattern() : "build/libs/*-all.jar";

        // Build phase
        executeBuild(projectDir, gradleExec, buildTask);

        // JAR discovery
        Path jarPath = discoverJar(projectDir, jarPattern);

        return new ResolvedSource(SourceType.GRADLE, null, projectDir, jarPath);
    }

    private void executeBuild(Path projectDir, String gradleExec, String buildTask) {
        log.info("[gcobs] Building external Gradle project: %s %s%n", projectDir, buildTask);
        try {
            ProcessBuilder pb = new ProcessBuilder(gradleExec, buildTask)
                    .directory(projectDir.toFile())
                    .inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new SourceResolverException("Gradle build failed with exit code " + exitCode
                                + " in " + projectDir);
            }
        } catch (IOException e) {
            throw new SourceResolverException("Failed to execute Gradle build in " + projectDir + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceResolverException("Gradle build interrupted in " + projectDir);
        }
    }

    private Path discoverJar(Path projectDir, String jarPattern) {
        // Convert glob pattern like "build/libs/*-all.jar" to a PathMatcher
        Path searchDir;
        String globPart;

        int lastSlash = jarPattern.lastIndexOf('/');
        if (lastSlash >= 0) {
            searchDir = projectDir.resolve(jarPattern.substring(0, lastSlash));
            globPart = jarPattern.substring(lastSlash + 1);
        } else {
            searchDir = projectDir;
            globPart = jarPattern;
        }

        if (!Files.isDirectory(searchDir)) {
            throw new SourceResolverException("JAR search directory not found after build: " + searchDir);
        }

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPart);
        try {
            List<Path> matches = Files.list(searchDir)
                    .filter(p -> matcher.matches(p.getFileName()))
                    .sorted()
                    .toList();

            if (matches.isEmpty()) {
                throw new SourceResolverException("No JAR matching pattern \"" + jarPattern + "\" found in " + searchDir);
            }
            if (matches.size() > 1) {
                log.warn("WARNING: Multiple JARs match pattern \"{}\", using: {}", jarPattern, matches.get(0).getFileName());
            }

            return matches.get(0); // Latest (alphabetically last)
        } catch (IOException e) {
            throw new SourceResolverException("Failed to search for JAR in " + searchDir + ": " + e.getMessage());
        }
    }
}
