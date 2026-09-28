package uk.gov.hmcts.cp.yotresultsdistribution.api;

import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.api.dto.ExceptionReportRequest;
import uk.gov.hmcts.cp.yotresultsdistribution.api.dto.GenerateRegisterRequest;
import uk.gov.hmcts.cp.yotresultsdistribution.api.dto.SupersedeRequest;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;

/**
 * The operations request contract is <strong>closed</strong>, and this is what closes it.
 *
 * <p>FR-028: a body carrying a field this service does not take is a contract violation and is
 * refused, exactly as an unknown field on the inbound queue message dead-letters. Tolerating one
 * would hide a caller's mistake - or a runbook that has drifted from the API - until the day it
 * mattered, which is the failure mode this whole service exists to end.
 *
 * <p><strong>Scoped to the request records and to nothing else.</strong> The strictness is
 * registered per type on the HTTP converter rather than switched on globally, because the
 * auto-configured mapper is the one every adapter in this service reads with: hearing payloads,
 * reference data and the platform's own event envelopes are large, weakly specified and read
 * leniently on purpose, and making them fail on an unknown field would dead-letter a night's
 * hearings for a field somebody else added. The contract that is closed is this service's own
 * request shapes.
 *
 * <p>A {@code WebMvcConfigurer} rather than a bean on a configuration class, so the slice tests
 * that exercise one controller pick it up: {@code @WebMvcTest} includes {@code WebMvcConfigurer}
 * beans and excludes ordinary configuration classes, and a closed contract asserted only in a full
 * context is a closed contract nobody notices opening.
 */
@Component
public class OperationsRequestBodies implements WebMvcConfigurer {

    /** Every request record an operations endpoint takes a body as. */
    private static final Set<Class<?>> REQUEST_RECORDS =
            Set.of(ExceptionReportRequest.class, GenerateRegisterRequest.class,
                    SupersedeRequest.class);

    /** The contract's own mapper: this service's defaults, and an unknown field is a failure. */
    private static final JsonMapper CLOSED = JacksonConfig.applyContractDefaults(JsonMapper.builder())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Override
    public void configureMessageConverters(final HttpMessageConverters.ServerBuilder builder) {
        builder.configureMessageConverters(converter -> {
            if (converter instanceof JacksonJsonHttpMessageConverter json) {
                close(json);
            }
        });
    }

    /**
     * Points one converter at the closed mapper for every request record.
     *
     * @param converter the JSON converter the operations endpoints are read through
     */
    private static void close(final JacksonJsonHttpMessageConverter converter) {
        for (final Class<?> record : REQUEST_RECORDS) {
            converter.registerMappersForType(record,
                    mappers -> mappers.put(MediaType.APPLICATION_JSON, CLOSED));
        }
    }
}
