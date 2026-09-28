package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

/**
 * One judicial result, gathered against the defendant it belongs to and tagged with where it was
 * found.
 *
 * <p>The shared kernel's {@code Result} ({@code DefendantContextBaseService.js:16-27}), which the
 * register carries unchanged: the outbound mappers scope by {@link #level} and {@link #offenceId},
 * and the court-extract filter and the subscription matcher both read {@link #judicialResult}
 * directly.
 *
 * <p>{@link #judicialResult} stays a {@link JsonNode} rather than becoming a typed model. It is the
 * producer's shape, not this service's, and binding it would silently discard every field this
 * service does not know about (constitution Principle IV).
 *
 * @param prosecutionCaseId the case the result was found under, where it was found under one
 * @param defendantId       the case-scoped defendant the result was found under
 * @param offenceId         the offence the result was ordered against, where there is one
 * @param applicationId     the court application the result was found under, where there was one
 * @param level             where in the hearing the result was recorded
 * @param masterDefendantId the defendant's identity across cases and applications
 * @param judicialResult    the result itself, exactly as the producer sent it
 * @param includeInNcesResult the legacy's NCES marker, set only on application-level results
 * @param isApplicant       the legacy's applicant marker, set on every result reached through an
 *                          application
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterResult(
        String prosecutionCaseId,
        String defendantId,
        String offenceId,
        String applicationId,
        ResultLevel level,
        String masterDefendantId,
        JsonNode judicialResult,
        @JsonProperty("includeInNcesResult") Boolean includeInNcesResult,
        @JsonProperty("isApplicant") Boolean isApplicant) {
}
