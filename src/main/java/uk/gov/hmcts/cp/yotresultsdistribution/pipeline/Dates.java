package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException;

/**
 * The court register's date handling — the legacy {@code NowsHelper/service/DateService.js} with
 * its three catalogued defects fixed.
 *
 * <p>Three fixes live here, and nothing else about the legacy behaviour moves:
 *
 * <ul>
 *   <li><strong>C10</strong> — {@code getLocalDateTime} formats a {@code Europe/London} wall clock
 *       and appends a literal {@code Z}, so a 10:00 UTC share is stored and printed as
 *       {@code 11:00:00Z} for half the year. {@link #dateTime} carries the instant it was given.</li>
 *   <li><strong>C12</strong> — the reference-data lookup derives its {@code on=} day from that
 *       relabelled value ({@code ReferenceDataService.js:38}), so an evening share reads the
 *       <em>next</em> day's subscription set. {@link #subscriptionDay} keys the day to the share
 *       instant instead.</li>
 *   <li><strong>C13</strong> — {@code parse} reads {@code YYYY-MM-DD} data with a
 *       {@code 'YYYY/MM/DD'} format and its {@code catch} throws a second time on an unbound
 *       {@code this}. {@link #orderingKey} parses ISO-8601 and refuses with one classified failure.
 *       </li>
 * </ul>
 *
 * <p>This class is pure: no clock, no I/O, no randomness (constitution Principle V). The legacy's
 * "absent input means now" behaviour — {@code moment.tz(undefined, zone)} is the current time, so a
 * hearing shared without a shared time is stamped with the wall clock — is deliberately not
 * reproduced: {@code sharedTime} is a required field of the inbound contract, and a date this class
 * cannot read is a classified failure rather than a value invented from a clock.
 *
 * <p><strong>One reading, three questions.</strong> Every method resolves its argument through the
 * same three-step ladder — an offset-carrying date-time, then a date-time carrying none, then a bare
 * ISO day — and differs only in what it then asks of the result. A value that carries an offset
 * names an instant, and is answered as that instant; a value that carries none names nothing to
 * convert, and is answered as it was recorded. That is the whole of C10's rule, and it is the reason
 * {@code moment}'s "read the digits as London and then call them UTC" third route has no counterpart
 * here.
 */
// PMD.OnlyOneReturn: the resolution ladder answers at the step that succeeds, which is the shape
// moment's own fallback chain has; funnelling it through one exit would hide which reading a value
// took.
@SuppressWarnings("PMD.OnlyOneReturn")
public final class Dates {

    /** The zone the court's own sitting days are recorded in. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /** The rendering of an instant this register writes: whole seconds, always UTC. */
    private static final DateTimeFormatter INSTANT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    /** The rendering of a calendar day. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * The legacy's own refusal message, kept verbatim.
     *
     * <p>{@code DateService.parse} throws {@code new Error('Invalid date format')}. The wording is
     * kept so a support engineer reading a parked delivery meets the sentence the function app's
     * logs would have carried, and it names the shape that was wrong and never the value that was in
     * it (constitution Principle VII).
     */
    private static final String UNREADABLE = "Invalid date format";

    /**
     * The instant a value records, rendered as {@code yyyy-MM-dd'T'HH:mm:ss'Z'}.
     *
     * <p>The fixed replacement for {@code DateService.getLocalDateTime} (defect C10). A value that
     * carries an offset is the instant it names, rendered in UTC; a value that carries none is
     * carried as recorded, digit for digit, because there is nothing in it to convert. Neither is
     * re-labelled through a timezone.
     *
     * @param value an instant, a local date-time, or a bare ISO day
     * @return the value as a UTC instant
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException if the value cannot
     *     be read as a date
     */
    public String dateTime(final String value) {
        return asUtc(read(value)).format(INSTANT);
    }

    /**
     * The day whose subscription set a share is matched against.
     *
     * <p>The fixed {@code on=} day of the reference-data lookup (defect C12): the UTC day of the
     * shared time, so a hearing shared at 23:30 UTC on 1 June reads the set in force on 1 June and
     * not the next day's.
     *
     * @param sharedTime the instant the results were shared
     * @return the day to look subscriptions up on
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException if the shared time
     *     cannot be read as a date
     */
    public LocalDate subscriptionDay(final String sharedTime) {
        return asUtc(read(sharedTime)).toLocalDate();
    }

    /**
     * The day a register is dated by, as {@code yyyy-MM-dd} — the UTC day of the register instant.
     *
     * <p>The day component of C11's file name, and the same day {@link #subscriptionDay} answers for
     * the same value: a register is filed under the day it was shared and addressed on, and those
     * are one day rather than two. Rendered rather than returned as a {@link LocalDate} because the
     * file name is a string and a caller that formatted it itself would be a second place this
     * rendering is decided.
     *
     * <p><strong>Not {@link #localDate}, and the difference is a whole day for half the year.</strong>
     * A hearing shared at 23:00 UTC on 1 June is 2 June in London, so the London reading files the
     * register under a day its own {@code registerDate} contradicts and its own subscription set
     * disagrees with. C10's row names the file-name day among the three things a BST evening share
     * moves; this is the one that moves it.
     *
     * @param registerDate the instant the register is dated by
     * @return the UTC day it falls on
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException if the value cannot
     *     be read as a date
     */
    public String registerDay(final String registerDate) {
        return asUtc(read(registerDate)).format(DAY);
    }

    /**
     * The London calendar day of a value, as {@code yyyy-MM-dd}.
     *
     * <p>Ports {@code DateService.getLocalDate} unchanged. Its one court-register call site matches a
     * hearing's sitting day against a judicial result's ordered date
     * ({@code RegisterFragmentService.js:48}) — both of them days a court recorded locally, which is
     * why this one stays local while {@link #subscriptionDay} is keyed to the share instant.
     *
     * @param value an instant, a local date-time, or a bare ISO day
     * @return the London calendar day
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException if the value cannot
     *     be read as a date
     */
    public String localDate(final String value) {
        return asLondon(read(value)).format(DAY);
    }

    /**
     * The calendar day an ordered date is sorted by.
     *
     * <p>The fixed replacement for {@code DateService.parse} (defect C13): ISO-8601, so the
     * {@code YYYY-MM-DD} the producer actually sends is read as what it says, and a value that is
     * not a date raises exactly one classified failure carrying the legacy's own message.
     *
     * <p>What the legacy does instead is not a stricter or looser version of this. Non-strict
     * {@code moment} walks the tokens of {@code 'YYYY/MM/DD'}, giving the year up to four digits and
     * the month and day up to two each, and skips whatever separates them — so {@code 20-01-2020}
     * reads as year 20, month 1, day 20, and the two-digit-year rule makes it 20 January 2020, a
     * date nobody wrote. A missing month or day defaults to 1, so {@code 2020} is an ordering key
     * too. Every one of those shapes is refused here.
     *
     * @param value the ordered date to sort by
     * @return the day to sort by
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException if the value cannot
     *     be read as a date
     */
    public LocalDate orderingKey(final String value) {
        return read(value).local().toLocalDate();
    }

    /**
     * Whether the first ordered date falls after the second.
     *
     * <p>Ports {@code DateService.isGreater}, which is the comparison the latest-ordered-date sorts
     * in {@code RegisterFragmentService.js:33} and {@code DefendantContextBaseService.js:296} perform
     * inline.
     *
     * @param first  the ordered date to test
     * @param second the ordered date to test it against
     * @return whether {@code first} is later than {@code second}
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException if either value
     *     cannot be read as a date
     */
    public boolean isGreater(final String first, final String second) {
        return orderingKey(first).isAfter(orderingKey(second));
    }

    /**
     * A value read as a date-time, and whether it named an instant.
     *
     * @param local  the date and time as the value wrote them
     * @param offset the offset the value carried, or {@code null} where it carried none
     */
    private record Reading(LocalDateTime local, ZoneOffset offset) {
    }

    /**
     * Reads a value, trying each shape this service accepts in turn.
     *
     * @param value the value to read; may be {@code null}
     * @return the reading
     * @throws TransformationFailedException if the value is absent or is no shape this can read
     */
    private static Reading read(final String value) {
        if (value == null) {
            throw new TransformationFailedException(UNREADABLE);
        }
        try {
            final OffsetDateTime carried = OffsetDateTime.parse(value);
            return new Reading(carried.toLocalDateTime(), carried.getOffset());
        } catch (DateTimeParseException carriesNoOffset) {
            return readWithoutOffset(value);
        }
    }

    /**
     * Reads a value that named no offset — a local date-time, or failing that a bare ISO day.
     *
     * @param value the value to read
     * @return the reading
     * @throws TransformationFailedException if the value is no shape this can read
     */
    private static Reading readWithoutOffset(final String value) {
        try {
            return new Reading(LocalDateTime.parse(value), null);
        } catch (DateTimeParseException notADateTime) {
            return readDay(value);
        }
    }

    /**
     * Reads a bare ISO day as its own midnight.
     *
     * @param value the value to read
     * @return the reading
     * @throws TransformationFailedException if the value is not an ISO day either
     */
    private static Reading readDay(final String value) {
        try {
            return new Reading(LocalDate.parse(value).atStartOfDay(), null);
        } catch (DateTimeParseException notADay) {
            // One classified refusal, with nothing hanging off it that could fail a second time on
            // the way out — which is the half of C13 the legacy's rethrowing catch never manages.
            throw new TransformationFailedException(UNREADABLE);
        }
    }

    /**
     * A reading as the UTC instant it names, to whole seconds.
     *
     * @param reading the reading
     * @return the instant
     */
    private static OffsetDateTime asUtc(final Reading reading) {
        final ZoneOffset carried = reading.offset() == null ? ZoneOffset.UTC : reading.offset();
        return reading.local()
                .atOffset(carried)
                .withOffsetSameInstant(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * A reading as a London wall clock.
     *
     * @param reading the reading
     * @return the London date-time
     */
    private static ZonedDateTime asLondon(final Reading reading) {
        if (reading.offset() == null) {
            // Nothing in the value says which instant it was, so there is nothing to convert: the
            // digits are already the local ones this method answers about.
            return reading.local().atZone(LONDON);
        }
        return reading.local().atOffset(reading.offset()).atZoneSameInstant(LONDON);
    }
}
