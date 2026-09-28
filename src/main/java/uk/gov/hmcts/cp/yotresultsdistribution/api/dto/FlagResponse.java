package uk.gov.hmcts.cp.yotresultsdistribution.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What {@code GET /operations/flag} answers: one reading of the one lever.
 *
 * <p>Three values and nothing else. {@code ON} and {@code OFF} are what the store said;
 * {@code UNREADABLE} is what a reader that could not reach it said, and only then is
 * {@link #reason} present - the reading's own bounded code, which is what an absent endpoint, a
 * refused identity and a slow store are told apart by.
 *
 * <p>The reason is <strong>omitted</strong> rather than rendered null where the flag was read, so a
 * runbook step cannot come to depend on a key that means nothing.
 *
 * @param flag   {@code ON}, {@code OFF} or {@code UNREADABLE}
 * @param reason the reading's bounded code, present only with {@code UNREADABLE}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FlagResponse(String flag, String reason) {

    /** The flag was read and this service is the implementation that generates. */
    public static final String ON = "ON";

    /** The flag was read and the legacy is the implementation that generates. */
    public static final String OFF = "OFF";

    /** Nobody could read the flag, which this service treats exactly as off. */
    public static final String UNREADABLE = "UNREADABLE";
}
