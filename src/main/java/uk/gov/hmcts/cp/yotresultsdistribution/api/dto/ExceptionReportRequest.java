package uk.gov.hmcts.cp.yotresultsdistribution.api.dto;

/**
 * What {@code POST /operations/exception-reports} takes.
 *
 * <p>Both fields are optional and neither is defaulted by this service beyond what the command
 * defaulted: an absent {@code since} means "since the previous scheduled report", computed from
 * the report schedule exactly as {@code report-exceptions} computed it, and an absent
 * {@code email} means the log sink alone.
 *
 * <p>{@code since} is carried as a string because the three forms an operator may type - an ISO
 * instant, an ISO-8601 duration and the {@code <n>d} / {@code <n>h} / {@code <n>m} / {@code <n>s}
 * shorthand - are one argument with one refusal, and binding it to a type here would turn two of
 * the three into a framework error message quoting the value back.
 *
 * @param since what the window opens at, or {@code null} for the previous scheduled report
 * @param email whether the e-mail sink is asked as well as the log one
 */
public record ExceptionReportRequest(String since, Boolean email) {

    /** The request a call with no body at all is read as: the default window, no e-mail. */
    public static final ExceptionReportRequest NOTHING = new ExceptionReportRequest(null, null);

    /**
     * Whether the e-mail sink was asked for, with an absent field read as "no".
     *
     * @return {@code true} only where the caller asked for it
     */
    public boolean emailAsked() {
        return Boolean.TRUE.equals(email);
    }
}
