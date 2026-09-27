package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where in a hearing a judicial result was recorded.
 *
 * <p>The shared kernel's {@code NowsHelper/service/LevelTypeEnum.js}, whose single-letter codes are
 * carried on every gathered result and are what the outbound mappers scope by — an offence-level
 * result belongs to one offence, a case-level result to the whole case.
 *
 * <p>The court register gathers with {@code isRegister = true}, which re-tags the offences reached
 * through a court application from {@code APPLICATION} to {@code OFFENCE}
 * ({@code DefendantContextBaseService.js:221-225, 245-249}). That re-tagging is what lets the
 * register scope an application's results to the offence they were ordered against, and it is the
 * one behavioural difference between the register and non-register gathers.
 */
public enum ResultLevel {

    /** Recorded against the defendant, across every case in the hearing. */
    DEFENDANT("D"),

    /** Recorded against a prosecution case. */
    CASE("C"),

    /** Recorded against a single offence. */
    OFFENCE("O"),

    /** Recorded against a court application as a whole. */
    APPLICATION("A");

    private final String wireCode;

    ResultLevel(final String wireCode) {
        this.wireCode = wireCode;
    }

    /**
     * The single-letter code the legacy carries on the gathered result.
     *
     * @return the wire code
     */
    @JsonValue
    public String code() {
        return wireCode;
    }
}
