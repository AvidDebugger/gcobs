package net.szumigaj.gcobs.cli.executor;

import net.szumigaj.gcobs.cli.model.SourceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class SourceResolverTest {

    private static final String SOURCE_TYPE_INTERNAL = "internal";

    private final Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();

    private final SourceResolver resolver = new SourceResolver();

    @TempDir
    private Path tempDir;

    @Test
    void resolveInternalExistingModule() {
        SourceConfig source = SourceConfig.builder()
                .type(SOURCE_TYPE_INTERNAL)
                .module("benchmark-ephemeral-jmh")
                .build();

        ResolvedSource resolved = resolver.resolve(source, projectRoot);

        assertThat(resolved.type().getKey()).isEqualTo("internal");
        assertThat(resolved.gradleTask()).isEqualTo(":benchmarks:benchmark-ephemeral-jmh:runJmh");
        assertThat(resolved.moduleDir()).isEqualTo(
                projectRoot.resolve("benchmarks/benchmark-ephemeral-jmh"));
    }

    @Test
    void resolveInternalNonexistentModule() {
        SourceConfig source = SourceConfig.builder()
                .type(SOURCE_TYPE_INTERNAL)
                .module("nonexistent-module")
                .build();

        assertThatThrownBy(() -> resolver.resolve(source, projectRoot))
                .isInstanceOf(SourceResolverException.class)
                .hasMessageContaining("nonexistent-module");
    }

    @Test
    void resolveInternalNullModule() {
        SourceConfig source = SourceConfig.builder()
                .type(SOURCE_TYPE_INTERNAL)
                .module(null)
                .build();

        assertThatThrownBy(() -> resolver.resolve(source, projectRoot))
                .isInstanceOf(SourceResolverException.class)
                .hasMessageContaining("source.module is required");
    }

    @Test
    void resolveInvalidTypeThrowsUnsupported() {
        SourceConfig source = SourceConfig.builder()
                .type("invalid")
                .path("/tmp/test.jar")
                .build();

        assertThatThrownBy(() -> resolver.resolve(source, projectRoot))
                .isInstanceOf(SourceResolverException.class)
                .hasMessageContaining("Unknown source type: \"invalid\"");
    }

    @Test
    void resolveNullTypeThrowsUnsupported() {
        SourceConfig source = SourceConfig.builder()
                .type(null)
                .build();

        assertThatThrownBy(() -> resolver.resolve(source, projectRoot))
                .isInstanceOf(SourceResolverException.class)
                .hasMessageContaining("source.type is required");
    }

    @Test
    void resolveBlankTypeThrowsRequired() {
        SourceConfig source = SourceConfig.builder()
                .type("  ")
                .build();

        assertThatThrownBy(() -> resolver.resolve(source, projectRoot))
                .isInstanceOf(SourceResolverException.class)
                .hasMessageContaining("source.type is required");
    }

    @Test
    void resolveJarNullPathThrows() {
        SourceConfig source = SourceConfig.builder()
                .type("jar")
                .path(null)
                .build();

        assertThatThrownBy(() -> resolver.resolve(source, projectRoot))
                .isInstanceOf(SourceResolverException.class)
                .hasMessageContaining("source.path is required");
    }

    @Test
    void resolveJarBlankPathThrows() {
        SourceConfig source = SourceConfig.builder()
                .type("jar")
                .path("   ")
                .build();

        assertThatThrownBy(() -> resolver.resolve(source, projectRoot))
                .isInstanceOf(SourceResolverException.class)
                .hasMessageContaining("source.path is required");
    }

    @Test
    void resolveJarNonExistentFileThrows() {
        SourceConfig source = SourceConfig.builder()
                .type("jar")
                .path(tempDir.resolve("does-not-exist.jar").toString())
                .build();

        assertThatThrownBy(() -> resolver.resolve(source, projectRoot))
                .isInstanceOf(SourceResolverException.class)
                .hasMessageContaining("JAR file not found");
    }

    @Test
    void resolveJarNonJarExtensionThrows() throws IOException {
        Path txtFile = tempDir.resolve("bench.txt");
        Files.createFile(txtFile);

        SourceConfig source = SourceConfig.builder()
                .type("jar")
                .path(txtFile.toString())
                .build();

        assertThatThrownBy(() -> resolver.resolve(source, projectRoot))
                .isInstanceOf(SourceResolverException.class)
                .hasMessageContaining("source.path must point to a .jar file");
    }

    @Test
    void resolveJarSuccessAbsolutePath() throws IOException {
        Path jarFile = tempDir.resolve("bench.jar");
        Files.createFile(jarFile);

        SourceConfig source = SourceConfig.builder()
                .type("jar")
                .path(jarFile.toString())
                .build();

        ResolvedSource resolved = resolver.resolve(source, projectRoot);

        assertThat(resolved.type()).isEqualTo(SourceType.JAR);
        assertThat(resolved.jarPath()).isEqualTo(jarFile);
        assertThat(resolved.gradleTask()).isNull();
        assertThat(resolved.moduleDir()).isNull();
    }

    @Test
    void resolveJarSuccessRelativePath() throws IOException {
        Path subDir = tempDir.resolve("libs");
        Files.createDirectories(subDir);
        Files.createFile(subDir.resolve("bench.jar"));

        // relative to tempDir (used as projectRoot here)
        SourceConfig source = SourceConfig.builder()
                .type("jar")
                .path("libs/bench.jar")
                .build();

        ResolvedSource resolved = resolver.resolve(source, tempDir);

        assertThat(resolved.type()).isEqualTo(SourceType.JAR);
        assertThat(resolved.jarPath()).isEqualTo(tempDir.resolve("libs/bench.jar"));
    }

    @Test
    void resolveGradleNullProjectDirThrows() {
        SourceConfig source = SourceConfig.builder()
                .type("gradle")
                .projectDir(null)
                .build();

        assertThatThrownBy(() -> resolver.resolve(source, projectRoot))
                .isInstanceOf(SourceResolverException.class)
                .hasMessageContaining("source.projectDir is required");
    }

    @Test
    void resolveGradleBlankProjectDirThrows() {
        SourceConfig source = SourceConfig.builder()
                .type("gradle")
                .projectDir("  ")
                .build();

        assertThatThrownBy(() -> resolver.resolve(source, projectRoot))
                .isInstanceOf(SourceResolverException.class)
                .hasMessageContaining("source.projectDir is required");
    }

    @Test
    void resolveGradleNonExistentDirThrows() {
        SourceConfig source = SourceConfig.builder()
                .type("gradle")
                .projectDir(tempDir.resolve("no-such-dir").toString())
                .build();

        assertThatThrownBy(() -> resolver.resolve(source, projectRoot))
                .isInstanceOf(SourceResolverException.class)
                .hasMessageContaining("source.projectDir not found");
    }

    @Test
    void resolveGradleNoBuildFileThrows() {
        SourceConfig source = SourceConfig.builder()
                .type("gradle")
                .projectDir(tempDir.toString())
                .build();

        assertThatThrownBy(() -> resolver.resolve(source, projectRoot))
                .isInstanceOf(SourceResolverException.class)
                .hasMessageContaining("No build.gradle or build.gradle.kts");
    }

    @Test
    void resolveGradleSuccessWithBuildGradle() throws IOException {
        Files.createFile(tempDir.resolve("build.gradle"));
        Files.createFile(tempDir.resolve("bench-all.jar"));

        SourceConfig source = SourceConfig.builder()
                .type("gradle")
                .projectDir(tempDir.toString())
                .gradleExecutable("true")
                .buildTask("shadowJar")
                .jarPattern("*-all.jar")
                .build();

        ResolvedSource resolved = resolver.resolve(source, projectRoot);

        assertThat(resolved.type()).isEqualTo(SourceType.GRADLE);
        assertThat(resolved.moduleDir()).isEqualTo(tempDir);
        assertThat(resolved.jarPath()).isEqualTo(tempDir.resolve("bench-all.jar"));
        assertThat(resolved.gradleTask()).isNull();
    }

    @Test
    void resolveGradleSuccessWithBuildGradleKts() throws IOException {
        Files.createFile(tempDir.resolve("build.gradle.kts"));
        Files.createFile(tempDir.resolve("bench-all.jar"));

        SourceConfig source = SourceConfig.builder()
                .type("gradle")
                .projectDir(tempDir.toString())
                .gradleExecutable("true")
                .buildTask("shadowJar")
                .jarPattern("*-all.jar")
                .build();

        ResolvedSource resolved = resolver.resolve(source, projectRoot);

        assertThat(resolved.type()).isEqualTo(SourceType.GRADLE);
        assertThat(resolved.jarPath()).isEqualTo(tempDir.resolve("bench-all.jar"));
    }

    @Test
    void resolveGradleNoJarAfterBuildThrows() throws IOException {
        Files.createFile(tempDir.resolve("build.gradle"));

        SourceConfig source = SourceConfig.builder()
                .type("gradle")
                .projectDir(tempDir.toString())
                .gradleExecutable("true")
                .jarPattern("*-all.jar")
                .build();

        assertThatThrownBy(() -> resolver.resolve(source, projectRoot))
                .isInstanceOf(SourceResolverException.class)
                .hasMessageContaining("No JAR matching pattern");
    }

    @Test
    void resolveGradleMultipleJarsPicksFirstAlphabetically() throws IOException {
        Files.createFile(tempDir.resolve("build.gradle"));
        Files.createFile(tempDir.resolve("a-bench.jar"));
        Files.createFile(tempDir.resolve("z-bench.jar"));

        SourceConfig source = SourceConfig.builder()
                .type("gradle")
                .projectDir(tempDir.toString())
                .gradleExecutable("true")
                .jarPattern("*-bench.jar")
                .build();

        ResolvedSource resolved = resolver.resolve(source, projectRoot);

        assertThat(resolved.jarPath().getFileName()).hasToString("a-bench.jar");
    }
}
