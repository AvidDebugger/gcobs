package net.szumigaj.gcobs.cli.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;


@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EnvironmentInfo(String javaVersion, String javaVendor, String javaRuntimeBuild, String javaVmName,
                              String javaVmVersion, String javaHome, String osName, String osVersion,
                              String kernelVersion, String cpuModel, String cpuGovernor, int availableProcessors,
                              int physicalMemoryMb, String cgroupCpuQuota, Integer cgroupMemoryLimitMb,
                              Integer parallelGcThreads, Integer concGcThreads) {
}
