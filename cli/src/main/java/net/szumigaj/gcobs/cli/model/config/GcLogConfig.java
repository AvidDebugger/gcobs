package net.szumigaj.gcobs.cli.model.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GcLogConfig(String tags) {
}
