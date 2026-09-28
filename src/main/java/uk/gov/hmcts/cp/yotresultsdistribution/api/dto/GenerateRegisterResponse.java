package uk.gov.hmcts.cp.yotresultsdistribution.api.dto;

import java.time.LocalDate;

/**
 * What {@code POST /operations/batches/generate} answers, which is an acceptance and not a result.
 *
 * <p>The work runs in the background on the generation scheduler's own thread, so what it did is
 * read back from the run line the {@code runId} correlates and from
 * {@code GET /operations/batches?date=D} - which is the price of the {@code 202} and is stated so
 * nobody looks for a tally here.
 *
 * <p>The date is <strong>this service's own parse</strong> of what was asked for, never the
 * characters the caller typed.
 *
 * @param runId      the id the run's lines are correlated by
 * @param date       the register date the run is about
 * @param overridden whether the run goes ahead over a flag that would have stopped it
 */
public record GenerateRegisterResponse(String runId, LocalDate date, boolean overridden) {
}
