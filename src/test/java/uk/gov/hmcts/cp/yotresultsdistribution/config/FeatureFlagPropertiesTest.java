package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The flag settings as text.
 *
 * <p>A record's generated {@code toString()} prints every component, and one of these is the
 * estate's App Configuration connection string - a static key with write access to every stack's
 * flags. A settings object reaches a log or an exception message by accident more often than by
 * design, so the one thing its text must never carry is the secret (constitution Principle VII).
 */
class FeatureFlagPropertiesTest {

    /** Invented here, and distinctive enough to be looked for. */
    private static final String SECRET = "c2VjcmV0LXRoYXQtbXVzdC1uZXZlci1sZWFr";

    private static final String CONNECTION_STRING =
            "Endpoint=https://ste-store.azconfig.io;Id=ste-id;Secret=" + SECRET;

    private static final String KEY = ".appconfig.featureflag/YotResultsDistributionService";

    @Nested
    @DisplayName("as text")
    class AsText {

        @Test
        void rendering_with_a_connection_string_should_carry_none_of_it() {
            final FeatureFlagProperties properties =
                    new FeatureFlagProperties(CONNECTION_STRING, KEY, "STE41", Duration.ofSeconds(2));

            assertThat(properties.toString())
                    .as("the secret, and the string it travels in, stay out of every rendering")
                    .doesNotContain(SECRET)
                    .doesNotContain(CONNECTION_STRING)
                    .contains("connectionString=<set>");
        }

        @Test
        void rendering_without_a_connection_string_should_say_it_is_unset() {
            final FeatureFlagProperties properties =
                    new FeatureFlagProperties(null, KEY, "STE41", Duration.ofSeconds(2));

            assertThat(properties.toString())
                    .as("unset is the answer an operator needs, and it is still not a value")
                    .contains("connectionString=<unset>")
                    .contains("label=STE41");
        }
    }
}
