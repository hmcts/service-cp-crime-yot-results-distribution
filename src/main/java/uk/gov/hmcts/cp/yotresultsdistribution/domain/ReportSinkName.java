package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * The two audiences a report is delivered to, as the bounded {@code sink} label.
 *
 * <p>Bounded because it is a metric label and a field on a run line: a sink named by free text
 * would be a cardinality problem and an unqueryable dashboard at once.
 */
public enum ReportSinkName {

    /** The structured events the platform's container-log collection carries into Log Analytics. */
    LOG,

    /** The e-mail, with the exception list attached as a CSV by file-service id. */
    EMAIL
}
