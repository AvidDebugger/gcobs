package net.szumigaj.gcobs.cli.model.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SourceConfig(SourceType type, String module, String path, String projectDir, String buildTask,
                           String jarPattern, String gradleExecutable) {
}
