package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The key one register batch is assembled under: a court centre and a register day.
 *
 * <p>A value rather than a pair of arguments because grouping is the whole of assembly, and two
 * positional parameters of which one is a {@link UUID} and one a {@link LocalDate} are two
 * parameters a caller can pass in the wrong order exactly once.
 *
 * <p>The day is the London date part of the document's register instant, not the instant itself:
 * one register per court centre per working day is the business fact, and an instant would group
 * 18:00 BST and 18:00 GMT into different batches.
 *
 * @param courtCentreId the court centre the register is for
 * @param registerDate  the London register day
 */
public record CourtCentreDay(UUID courtCentreId, LocalDate registerDate) {

    /**
     * Refuses a key that could group rows by accident.
     *
     * <p>A null component would compare equal to every other null one, so two court centres with no
     * identifier would share a batch and a single PDF would carry both.
     */
    public CourtCentreDay {
        Objects.requireNonNull(courtCentreId, "a batch key names its court centre");
        Objects.requireNonNull(registerDate, "a batch key names its register day");
    }
}
