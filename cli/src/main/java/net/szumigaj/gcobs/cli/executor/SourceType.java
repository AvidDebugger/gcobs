package net.szumigaj.gcobs.cli.executor;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SourceType {
    INTERNAL("internal");
    private final String key;
}
