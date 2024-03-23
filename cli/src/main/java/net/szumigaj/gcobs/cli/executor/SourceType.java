package net.szumigaj.gcobs.cli.executor;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SourceType {
    GRADLE("gradle"),
    INTERNAL("internal"),
    JAR("jar");

    private final String key;
}
