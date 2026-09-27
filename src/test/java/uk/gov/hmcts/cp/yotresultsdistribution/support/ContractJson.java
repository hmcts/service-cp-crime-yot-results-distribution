package uk.gov.hmcts.cp.yotresultsdistribution.support;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;

/**
 * The mapper the comparator's own suites read JSON with.
 *
 * <p>It has to carry {@link DeserializationFeature#USE_BIG_DECIMAL_FOR_FLOATS}, which is what makes
 * {@code 1.0} and {@code 1.00} arrive as two distinct {@code BigDecimal} scales in the first place.
 * Parse the comparator vectors with a default mapper instead and both collapse to the same
 * {@code double}, every numeric-scale vector passes trivially, and the suites stop testing the thing
 * they exist to test.
 *
 * <p>The stand-in this used to be is gone: {@code config/JacksonConfig} landed with the inbound
 * contract (T012) and is where the feature belongs, because it configures the mapper the running
 * service uses as well as the standalone one. This factory now delegates to it, so the comparator
 * and the service cannot drift apart.
 */
public final class ContractJson {

    private ContractJson() {
        // Static fixture holder.
    }

    /**
     * A mapper carrying this service's JSON contract.
     *
     * @return the shared contract mapper
     */
    public static ObjectMapper mapper() {
        return JacksonConfig.contractObjectMapper();
    }

    /**
     * Parses JSON text with {@link #mapper()}.
     *
     * @param json the text
     * @return the tree
     */
    public static JsonNode tree(final String json) {
        return mapper().readTree(json);
    }
}
