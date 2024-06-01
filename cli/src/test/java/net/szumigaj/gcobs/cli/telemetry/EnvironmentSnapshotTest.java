package net.szumigaj.gcobs.cli.telemetry;

import net.szumigaj.gcobs.cli.model.env.EnvironmentInfo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentSnapshotTest {

    @Test
    void returnsNullForNullInput() {
        assertThat(EnvironmentSnapshot.parseJdkMajorVersion(null)).isNull();
    }

    @Test
    void returnsNullForEmptyString() {
        assertThat(EnvironmentSnapshot.parseJdkMajorVersion("")).isNull();
    }

    @Test
    void parsesJdk8LegacyFormat() {
        assertThat(EnvironmentSnapshot.parseJdkMajorVersion("1.8.0_301")).isEqualTo(8);
    }

    @Test
    void parsesJdk5LegacyFormat() {
        assertThat(EnvironmentSnapshot.parseJdkMajorVersion("1.5.0")).isEqualTo(5);
    }

    @Test
    void parsesJdk9ExactMajor() {
        // JDK 9 was the first release to drop the "1.x" scheme
        assertThat(EnvironmentSnapshot.parseJdkMajorVersion("9.0.4")).isEqualTo(9);
    }

    @Test
    void parsesJdk11WithPatch() {
        assertThat(EnvironmentSnapshot.parseJdkMajorVersion("11.0.2")).isEqualTo(11);
    }

    @Test
    void parsesJdk17WithPatch() {
        assertThat(EnvironmentSnapshot.parseJdkMajorVersion("17.0.1")).isEqualTo(17);
    }

    @Test
    void parsesJdk17SingleToken() {
        assertThat(EnvironmentSnapshot.parseJdkMajorVersion("17")).isEqualTo(17);
    }

    @Test
    void returnsNullForNonNumericMajor() {
        assertThat(EnvironmentSnapshot.parseJdkMajorVersion("abc.0.1")).isNull();
    }

    @Test
    void returnsNullForGarbageString() {
        assertThat(EnvironmentSnapshot.parseJdkMajorVersion("not-a-version")).isNull();
    }

    @Test
    void captureReturnsPopulatedEnvironmentInfo() {
        EnvironmentInfo info = new EnvironmentSnapshot().capture();

        assertThat(info).isNotNull();
        assertThat(info.javaVersion()).isNotBlank();
        assertThat(info.jdkMajorVersion()).isNotNull().isGreaterThan(0);
        assertThat(info.jvmDistribution()).isNotNull().isNotBlank();
        assertThat(info.javaHomePath()).isNotNull().isNotBlank();
        assertThat(info.availableProcessors()).isGreaterThanOrEqualTo(1);
    }
}
