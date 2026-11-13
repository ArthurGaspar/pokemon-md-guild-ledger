package dev.arthurgaspar.expeditions.platform.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import dev.arthurgaspar.expeditions.platform.admission.AdmissionTokens.Admission;
import dev.arthurgaspar.expeditions.platform.admission.AdmissionTokens.InvalidAdmission;

class AdmissionTokensTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

    private final AdmissionTokens tokens = new AdmissionTokens("test-admission-secret-0123456789abcdef");

    @Test
    void roundTrip() {
        Admission admission = new Admission(7, "kc:mira@guild", NOW.plusSeconds(120));
        assertThat(tokens.verify(tokens.issue(admission), NOW)).isEqualTo(admission);
    }

    @Test
    void expiredAtTheDeadline() {
        String token = tokens.issue(new Admission(7, "mira", NOW.plusSeconds(120)));
        assertThat(tokens.verify(token, NOW.plusSeconds(119)).adventurerId()).isEqualTo("mira");
        assertThatThrownBy(() -> tokens.verify(token, NOW.plusSeconds(120))).isInstanceOf(InvalidAdmission.class)
                .hasMessageContaining("expired");
    }

    @Test
    void rejectsAnotherKeyAndTampering() {
        String token = tokens.issue(new Admission(7, "mira", NOW.plusSeconds(120)));
        AdmissionTokens other = new AdmissionTokens("another-admission-secret-0123456789ab");
        assertThatThrownBy(() -> other.verify(token, NOW)).isInstanceOf(InvalidAdmission.class);

        String forged = new AdmissionTokens("test-admission-secret-0123456789abcdef")
                .issue(new Admission(8, "mira", NOW.plusSeconds(120)));
        String spliced = forged.substring(0, forged.indexOf('.')) + token.substring(token.indexOf('.'));
        assertThatThrownBy(() -> tokens.verify(spliced, NOW)).isInstanceOf(InvalidAdmission.class)
                .hasMessageContaining("signature");
    }

    @Test
    void rejectsGarbage() {
        for (String garbage : new String[] {null, "", "abc", "a.b", "!!.??"}) {
            assertThatThrownBy(() -> tokens.verify(garbage, NOW)).isInstanceOf(InvalidAdmission.class);
        }
    }

    @Test
    void refusesShortSecrets() {
        assertThatThrownBy(() -> new AdmissionTokens("short")).isInstanceOf(IllegalArgumentException.class);
    }
}
