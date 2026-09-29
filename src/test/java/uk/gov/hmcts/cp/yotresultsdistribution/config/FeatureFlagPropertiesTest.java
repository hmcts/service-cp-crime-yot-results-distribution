package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
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
                    .doesNotContain("ste-id")
                    .doesNotContain("ste-store.azconfig.io")
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

    /**
     * A part of the connection string, read exactly as the SDK reads it.
     *
     * <p>{@code ConfigurationClientCredentials} splits on {@code ;}, trims each segment, and takes a
     * part only where the trimmed segment begins with its name and {@code =}, ignoring case. The
     * validator asks its questions of these parts, so a part read any other way is a string the
     * validator admits and the SDK then refuses - on an exception that quotes the whole value.
     */
    @Nested
    @DisplayName("one part of the connection string")
    class Parts {

        @Test
        void a_part_name_should_match_without_regard_to_case() {
            assertThat(partOf("endpoint=https://x.azconfig.io;ID=ste-id;secret=" + SECRET,
                    FeatureFlagProperties.ID_PART))
                    .contains("ste-id");
        }

        @Test
        void a_value_carrying_an_equals_sign_should_be_read_whole() {
            assertThat(partOf("Endpoint=https://x.azconfig.io;Id=ste-id;Secret=YWJj==",
                    FeatureFlagProperties.SECRET_PART))
                    .contains("YWJj==");
        }

        @Test
        void a_part_given_twice_should_be_read_as_its_last_value() {
            assertThat(partOf("Id=first;Endpoint=https://x.azconfig.io;Id=second;Secret=" + SECRET,
                    FeatureFlagProperties.ID_PART))
                    .contains("second");
        }

        @Test
        void a_padded_segment_should_be_read_as_its_trimmed_value() {
            assertThat(partOf("Endpoint=https://x.azconfig.io; Id=ste-id ;Secret=" + SECRET,
                    FeatureFlagProperties.ID_PART))
                    .as("the SDK trims the segment, so the value it uses carries no trailing space")
                    .contains("ste-id");
        }

        @Test
        void a_name_separated_from_its_equals_sign_should_not_be_a_part() {
            assertThat(partOf("Endpoint =https://x.azconfig.io;Id=ste-id;Secret=" + SECRET,
                    FeatureFlagProperties.ENDPOINT_PART))
                    .as("the SDK needs the name immediately followed by '=', and reads no endpoint "
                            + "from this - so neither may the validator")
                    .isEmpty();
        }

        @Test
        void no_connection_string_should_have_no_parts() {
            assertThat(new FeatureFlagProperties(" ", KEY, "STE41", Duration.ofSeconds(2))
                    .connectionStringPart(FeatureFlagProperties.ENDPOINT_PART))
                    .isEmpty();
        }

        private Optional<String> partOf(final String connectionString, final String name) {
            return new FeatureFlagProperties(connectionString, KEY, "STE41", Duration.ofSeconds(2))
                    .connectionStringPart(name);
        }
    }
}
