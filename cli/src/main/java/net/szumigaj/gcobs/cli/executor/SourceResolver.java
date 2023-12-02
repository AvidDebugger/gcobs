package net.szumigaj.gcobs.cli.executor;

import jakarta.inject.Singleton;
import net.szumigaj.gcobs.cli.model.SourceConfig;

import java.nio.file.Files;
import java.nio.file.Path;

@Singleton
public class SourceResolver {

    public ResolvedSource resolve(SourceConfig source, Path projectRoot) {
        if (SourceType.INTERNAL.getKey().equalsIgnoreCase(source.type())) {
            return resolveInternal(source, projectRoot);
        }
        throw new SourceResolverException("Unknown source type: \"%s\"".formatted(source.type()));
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
}
