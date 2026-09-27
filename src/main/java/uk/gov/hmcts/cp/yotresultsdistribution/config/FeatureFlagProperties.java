package uk.gov.hmcts.cp.yotresultsdistribution.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the one lever is read from.
 *
 * <p>Bound from the {@code yotresultsdistribution.feature} keys. The endpoint and the label are bindings
 * rather than values - they arrive from the deployment, and a service that invented either would
 * read a different stack's flag or none at all - so neither has a default worth having and both
 * become required once generation is enabled.
 *
 * <p>The key is the exception: it is the same setting the legacy reads, spelled the same way, and
 * that sameness is what makes the flag one lever rather than three. Changing it here without
 * changing it there leaves two implementations disagreeing about the cutover.
 *
 * <p>{@link #validate()} holds the two settings that have defaults to what those defaults have to
 * be. The endpoint and the label have none, so whether they are present is a question about
 * generation rather than about this record, and {@link PropertiesValidator} asks it.
 *
 * @param endpoint   the App Configuration store, empty where none is configured
 * @param key        the setting key, in App Configuration's feature-flag form
 * @param label      the stack's label, which is how one store serves every stack
 * @param timeout    the whole budget for the read; a timeout is UNREADABLE, which is OFF
 * @param credential which identity the read is authorised with, deployed or local
 */
@ConfigurationProperties(prefix = "yotresultsdistribution.feature")
public record FeatureFlagProperties(
        String endpoint,
        @DefaultValue(".appconfig.featureflag/YotResultsDistributionService") String key,
        String label,
        @DefaultValue("2s") Duration timeout,
        @DefaultValue(WORKLOAD_IDENTITY) Credential credential) {

    /** The deployed identity, and the default: what the AKS webhook projects into the pod. */
    public static final String WORKLOAD_IDENTITY = "workload-identity";

    /** The compose loop's fixed identity, refused anywhere the reading could matter. */
    public static final String LOCAL_TEST = "local-test";

    private static final String PREFIX = "yotresultsdistribution.feature";
    private static final String KEY = PREFIX + ".key";
    private static final String TIMEOUT = PREFIX + ".timeout";

    /**
     * Which identity the App Configuration read is authorised with.
     *
     * <p>A setting rather than a Spring profile, for the reason {@link PayloadSourceMode} gives:
     * "whose identity read the flag" is a question an operator must be able to answer from the
     * configuration in front of them - and, unlike a profile, it is a question
     * {@link PropertiesValidator} can refuse the wrong answer to at startup.
     */
    public enum Credential {

        /**
         * The pod's own workload identity. The deployed value, and the default.
         *
         * <p>Built from the three variables the AKS webhook projects, exactly as the producer builds
         * its Service Bus credential, so the platform ask stays one checkable statement.
         */
        WORKLOAD_IDENTITY,

        /**
         * A fixed, published identity that authorises nothing, for the local compose loop alone.
         *
         * <p>It exists so the <em>real</em> reader can be pointed at the WireMock App Configuration
         * stub: a bearer token is only ever sent over TLS, so a workload-identity credential cannot
         * read a plain-HTTP stand-in at all. {@link PropertiesValidator} refuses this value wherever
         * the endpoint names a real store or the pod is a deployed one.
         */
        LOCAL_TEST
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
