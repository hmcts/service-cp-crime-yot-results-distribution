package uk.gov.hmcts.cp.yotresultsdistribution.config;

import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the one lever is read from.
 *
 * <p>Bound from the {@code yotresultsdistribution.feature} keys. The connection string and the label
 * are bindings rather than values - they arrive from the deployment, and a service that invented
 * either would read a different stack's flag or none at all - so neither has a default worth having
 * and both become required once generation is enabled.
 *
 * <p><strong>The connection string is the estate's App Configuration key</strong> (constitution
 * 5.1.0): {@code APP-CONFIG-FEATURE-MANAGER-CONNECTION-STRING} from Key Vault on a deployed pod, the
 * secret resultsvalidator reads, because no App Configuration role can be assigned to this service's
 * identity. It names the store itself ({@code Endpoint=}) and authorises the read ({@code Id=},
 * {@code Secret=}), so there is no separate endpoint to disagree with it. It is the one static key
 * this service holds, and nothing prints it: {@link #toString()} says only whether it is set.
 *
 * <p>The key is the exception to "a binding, not a value": it is the same setting the legacy reads,
 * spelled the same way, and that sameness is what makes the flag one lever rather than three.
 *
 * <p>{@link #validate()} holds the two settings that have defaults to what those defaults have to
 * be. Whether the connection string and the label are present, and whether the string can be read,
 * is a question about generation rather than about this record, and {@link PropertiesValidator}
 * asks it.
 *
 * @param connectionString the App Configuration connection string, blank where none is configured
 * @param key              the setting key, in App Configuration's feature-flag form
 * @param label            the stack's label, which is how one store serves every stack
 * @param timeout          the whole budget for the read; a timeout is UNREADABLE, which is OFF
 */
@ConfigurationProperties(prefix = "yotresultsdistribution.feature")
public record FeatureFlagProperties(
        String connectionString,
        @DefaultValue(".appconfig.featureflag/YotResultsDistributionService") String key,
        String label,
        @DefaultValue("2s") Duration timeout) {

    /**
     * The identity of the published local pair the compose loop reads its WireMock stub with.
     *
     * <p>Published, and a secret of nothing: no Azure store has ever been given it, and the only
     * thing that answers it is a mapping that checks no credential. {@link PropertiesValidator}
     * refuses it anywhere a real flag is read.
     */
    public static final String PUBLISHED_LOCAL_ID = "0-l0-s0:yotresultsdistributionlocal";

    /** The part of a connection string that names the store. */
    public static final String ENDPOINT_PART = "Endpoint";

    /** The part that names the access key's identity. */
    public static final String ID_PART = "Id";

    /** The part that carries the access key's secret. */
    public static final String SECRET_PART = "Secret";

    private static final String PREFIX = "yotresultsdistribution.feature";
    private static final String KEY = PREFIX + ".key";
    private static final String TIMEOUT = PREFIX + ".timeout";

    /** Separates the parts of a connection string. */
    private static final String PART_SEPARATOR = ";";

    /** Separates a part's name from its value; the first one only, since a value may carry more. */
    private static final String NAME_SEPARATOR = "=";

    /**
     * Whether a connection string is configured at all.
     *
     * @return {@code true} where one is set
     */
    public boolean hasConnectionString() {
        return connectionString != null && !connectionString.isBlank();
    }

    /**
     * One part of the connection string, read exactly as the SDK reads it.
     *
     * <p>{@code ConfigurationClientCredentials} (azure-data-appconfiguration) splits the string on
     * {@code ;}, trims each segment, and takes a part only where the trimmed segment begins with the
     * part's name immediately followed by {@code =}, ignoring case - a later segment of the same
     * name replacing an earlier one. Reading it any other way admits strings the SDK then refuses,
     * and the SDK's refusal quotes the whole value; so {@code Endpoint =...} is no endpoint here
     * because it is none there, and the value returned is the one the SDK will be given.
     *
     * @param name the part's name - {@link #ENDPOINT_PART}, {@link #ID_PART} or
     *             {@link #SECRET_PART}
     * @return the part's value, or empty where the string carries no such part
     */
    public Optional<String> connectionStringPart(final String name) {
        final String prefix = name + NAME_SEPARATOR;
        Optional<String> part = Optional.empty();
        if (hasConnectionString()) {
            for (final String segment : connectionString.split(PART_SEPARATOR)) {
                final String trimmed = segment.trim();
                if (trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    part = Optional.of(trimmed.substring(prefix.length()));
                }
            }
        }
        return part;
    }

    /**
     * The settings as text, with the connection string reduced to whether it is set.
     *
     * <p>A record's generated {@code toString()} prints every component, and this one's first
     * component is a key with access to every stack's flags. Settings reach a log or an exception
     * message by accident more often than by design (constitution Principle VII).
     *
     * @return the key, the label and the timeout, and {@code <set>} or {@code <unset>}
     */
    @Override
    public String toString() {
        return "FeatureFlagProperties[connectionString=" + (hasConnectionString() ? "<set>" : "<unset>")
                + ", key=" + key + ", label=" + label + ", timeout=" + timeout + "]";
    }

    /**
     * Refuses a flag configuration that cannot answer the question the run asks.
     *
     * <p>The key is the lever's identity - it is the same string the producer and the legacy read,
     * and an empty one reads a setting nobody writes, which is UNREADABLE, which is a run skipped
     * every night. The timeout gates the start of a run, so one that never expires is a run held
     * open by a slow store rather than a run that decided to skip.
     *
     * @throws IllegalStateException if the key is blank or the timeout cannot expire
     */
    public void validate() {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    KEY + " must be the App Configuration key the flag lives under, the same one"
                            + " the producer and the legacy read - an empty key reads a setting"
                            + " nobody writes, and every run would skip");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException(
                    TIMEOUT + " (" + timeout + ") must be positive - the flag gates the start of a"
                            + " run, and a read that never expires holds the run open instead of"
                            + " deciding it");
        }
    }
}
