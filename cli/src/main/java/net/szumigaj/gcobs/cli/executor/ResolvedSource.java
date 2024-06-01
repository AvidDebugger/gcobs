package net.szumigaj.gcobs.cli.executor;

import net.szumigaj.gcobs.cli.model.config.SourceType;

import java.nio.file.Path;

public record ResolvedSource(SourceType type, String gradleTask, Path moduleDir, Path jarPath) {
    public ResolvedSource(SourceType type, String gradleTask, Path moduleDir) {
        this(type, gradleTask, moduleDir, null);
    }
}
