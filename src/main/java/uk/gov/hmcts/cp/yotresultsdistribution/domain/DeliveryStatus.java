package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * How completely one sink delivered one report.
 *
 * <p>Three states rather than a boolean, because the e-mail sink has more than one recipient and
 * "two of the three were told" is neither a success nor a failure: it is a list of resends.
 */
public enum DeliveryStatus {

    /** Everything the sink was asked to deliver was delivered. */
    DELIVERED,

    /** Some of it was, and the rest is resendable. */
    PARTIALLY_DELIVERED,

    /** None of it was. The run carries on to the other sink regardless (FR-007). */
    NOT_DELIVERED
}
