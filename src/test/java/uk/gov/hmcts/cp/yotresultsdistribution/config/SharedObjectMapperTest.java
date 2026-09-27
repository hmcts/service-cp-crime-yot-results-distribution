package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Holds the mapper the <em>application</em> injects to Principle IV, not a mapper a test built for
 * itself.
 *
 * <p>{@link JacksonConfig} says of itself that "a context test pins it to the injected bean so the
 * two cannot drift". This is that test. A static factory can be configured perfectly and still leave
 * the running service using Spring's auto-configured mapper, which knows nothing about it: every
 * collaborator that takes an {@code ObjectMapper} from the context — the command parser, the
 * outbound contract validator, the three HTTP clients — would then read numbers as binary floating
 * point while every unit suite stayed green, because they all build their own mapper from the
 * factory.
 *
 * <p>Money is why it matters here. A court register carries financial results, and the outbound
 * document is assembled from the inbound tree: a fine that arrives as {@code 1234.56} and is written
 * as {@code 1234.5599999999999} is a register that is wrong in a way nobody reads until a defendant
 * disputes it.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("the application's shared ObjectMapper")
class SharedObjectMapperTest {

    private final ObjectMapper objectMapper;

    // Constructor injection, in a test as in production: Principle V forbids field injection
    // everywhere, and a test that takes its collaborator through the constructor is a test that
    // could not accidentally run against a half-built context.
    @Autowired
    SharedObjectMapperTest(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Test
    @DisplayName("a fractional number materialises as a BigDecimal, not as a double")
    void should_materialise_a_fractional_number_as_a_big_decimal() {
        final JsonNode tree = objectMapper.readTree("{\"amount\": 1234.56}");

        assertThat(tree.get("amount").isBigDecimal()).isTrue();
        assertThat(tree.get("amount").decimalValue()).isEqualTo(new BigDecimal("1234.56"));
    }

    @Test
    @DisplayName("a high-precision number keeps every digit it arrived with")
    void should_not_lose_a_digit_of_a_high_precision_number() {
        // Binary floating point cannot hold this exactly. A register that rounds a penny is a
        // register that is wrong.
        final JsonNode tree = objectMapper.readTree("{\"amount\": 0.1234567890123456789}");

        assertThat(tree.get("amount").decimalValue())
                .isEqualTo(new BigDecimal("0.1234567890123456789"));
    }

    @Test
    @DisplayName("it agrees with the standalone factory every unit suite builds from")
    void should_agree_with_the_mapper_the_unit_suites_are_given() {
        // The two must not drift: if the standalone factory and the context ever disagree about
        // number handling, one of the two families of suites is proving nothing.
        final String body = "{\"amount\": 9.99}";
        final ObjectMapper standalone = JacksonConfig.contractObjectMapper();

        assertThat(objectMapper.readTree(body).get("amount").decimalValue())
                .isEqualTo(standalone.readTree(body).get("amount").decimalValue());
        assertThat(objectMapper.readTree(body).get("amount").isBigDecimal())
                .isEqualTo(standalone.readTree(body).get("amount").isBigDecimal());
    }
}
