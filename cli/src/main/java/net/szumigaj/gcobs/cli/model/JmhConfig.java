package net.szumigaj.gcobs.cli.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record JmhConfig(String includes, Integer warmupIterations, Integer measurementIterations, Integer forks,
                        Integer threads) {
}
