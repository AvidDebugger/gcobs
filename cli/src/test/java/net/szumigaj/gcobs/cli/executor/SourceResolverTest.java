package net.szumigaj.gcobs.cli.executor;

import net.szumigaj.gcobs.cli.model.SourceConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class SourceResolverTest {

    private static final String SOURCE_TYPE_INTERNAL = "internal";
    
    private final Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();

    private final SourceResolver resolver = new SourceResolver();

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
                .hasMessageContaining("Unknown source type: \"null\"");
    }
}
