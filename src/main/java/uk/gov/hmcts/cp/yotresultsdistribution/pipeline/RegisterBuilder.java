package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterFragment;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterResult;

/**
 * Turns a hearing payload into the register fragment for that hearing.
 *
 * <p>Ports {@code SetCourtRegister/index.js} — the gather, the latest ordered date, the
 * court-extract filter, the per-defendant vocabulary, and the six fields of the fragment — in the
 * legacy's own order, because the order is load-bearing: the ordered dates are collected from the
 * results <em>before</em> the court-extract filter removes any of them
 * ({@code index.js:40-43}), so a result that never reaches the register still decides which day the
 * register covers. The vocabulary is attached after the filter, for the same reason in reverse: the
 * custodial-result flags are computed from the results that survived.
 *
 * <p>Pure: JSON in, a typed fragment out, no clock and no I/O (constitution Principle V). Every date
 * it needs comes from {@link Dates}, which is where the three date fixes live.
 *
 * <p>Two catalogued defects surface here:
 *
 * <ul>
 *   <li><strong>C6</strong> — {@code index.js:35-38} guards the gather's result with
 *       {@code if (!defendantContextBaseList) return;}, which can never fire because the builder
 *       always returns an array. The guard disappears: a hearing that gathers nobody produces a
 *       fragment with an empty defendant list, and the pipeline records that as the named outcome
 *       {@code no-defendants} rather than as an absent return nothing downstream can tell from an
 *       exception.</li>
 *   <li><strong>C10</strong> — {@code registerDate} is the instant the results were shared, not a
 *       London wall clock relabelled with a {@code Z}. See {@link Dates#dateTime(String)}.</li>
 * </ul>
 *
 * <p>A third is visible in the fragment this builds rather than in the building: the court centre's
 * id is written to {@code courtCentreId}, spelled the way the rest of the estate spells it. The
 * legacy declares and writes {@code courtCenterId} while every payload supplies
 * {@code courtCentreId}, so the field is {@code undefined} on both sides of the pipeline and the one
 * Jest assertion that names it compares {@code undefined} to {@code undefined} — the fragment half
 * of defect C26.
 */
public final class RegisterBuilder {

    private static final String COURT_CENTRE = "courtCentre";

    private final Dates dates;

    /**
     * Creates the builder over the register's date handling.
     *
     * @param dates the register's date handling
     */
    public RegisterBuilder(final Dates dates) {
        this.dates = dates;
    }

    /**
     * Builds one hearing's register fragment.
     *
     * @param hearing    the hearing payload, exactly as the producer sent it
     * @param sharedTime the instant the results were shared
     * @return the fragment, which carries an empty defendant list where the hearing gathered nobody
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException if the payload
     *     cannot be read
     */
    public RegisterFragment build(final JsonNode hearing, final String sharedTime) {
        final List<DefendantContext> gathered =
                new DefendantContextBuilder(hearing, dates).build();

        // Collected before the filter runs, and from a Set, exactly as `index.js:40-43,69-79` does:
        // a result published through the NOWs route is about to be dropped and still dates the
        // register, and two results ordered on the same day are one date to sort.
        final String latestOrderedDate = OrderedDates.latest(orderedDates(gathered), dates);

        CourtExtractFilter.apply(gathered);

        final JsonNode courtCentre = Json.dereferenced(hearing, COURT_CENTRE);
        return new RegisterFragment(
                Json.text(courtCentre, "id"),
                dates.dateTime(sharedTime),
                HearingDates.resolve(latestOrderedDate, hearing, dates),
                Json.text(hearing, "id"),
                registerDefendants(hearing, gathered),
                Json.text(courtCentre, "code"));
    }

    /**
     * Every ordered date the hearing's gathered results name, in the order they were found and
     * without repeats.
     *
     * @param gathered the gathered defendants
     * @return the ordered dates; a {@code null} member is a result that named none
     */
    private static Set<JsonNode> orderedDates(final List<DefendantContext> gathered) {
        final Set<JsonNode> orderedDates = new LinkedHashSet<>();
        for (final DefendantContext defendant : gathered) {
            for (final RegisterResult result : defendant.results()) {
                orderedDates.add(Json.at(result.judicialResult(), "orderedDate"));
            }
        }
        return orderedDates;
    }

    /**
     * The gathered defendants, frozen and each carrying their own vocabulary.
     *
     * <p>Computed per defendant ({@code index.js:63-68}), not once for the list. A hearing routinely
     * carries adults and youths together — the youth filter is one stage further on, at aggregation
     * — so a shared vocabulary would be the wrong one for every defendant but the first, which is
     * the shape defect C31 takes at the matching stage.
     *
     * @param hearing  the hearing payload
     * @param gathered the gathered defendants
     * @return the register defendants
     */
    private static List<RegisterDefendant> registerDefendants(
            final JsonNode hearing, final List<DefendantContext> gathered) {

        final VocabularyBuilder vocabulary = new VocabularyBuilder(hearing);
        final List<RegisterDefendant> registerDefendants = new ArrayList<>(gathered.size());
        for (final DefendantContext defendant : gathered) {
            registerDefendants.add(asRegisterDefendant(defendant, vocabulary));
        }
        return registerDefendants;
    }

    /**
     * One gathered defendant as they appear on the fragment.
     *
     * @param defendant  the gathered defendant
     * @param vocabulary the vocabulary builder for this hearing
     * @return the register defendant
     */
    private static RegisterDefendant asRegisterDefendant(
            final DefendantContext defendant, final VocabularyBuilder vocabulary) {

        return new RegisterDefendant(
                defendant.defendantIds(),
                defendant.results(),
                defendant.cases(),
                defendant.applications(),
                defendant.masterDefendantId(),
                Boolean.TRUE.equals(defendant.youthDefendant()),
                defendant.orderedDate(),
                vocabulary.build(defendant));
    }
}
