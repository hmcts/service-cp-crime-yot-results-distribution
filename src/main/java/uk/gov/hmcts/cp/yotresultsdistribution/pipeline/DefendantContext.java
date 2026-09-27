package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.ArrayList;
import java.util.List;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterResult;

/**
 * The mutable working copy of one defendant's context, while it is still being gathered.
 *
 * <p>The legacy {@code DefendantContextBase} is built by accumulation: results are concatenated on
 * as four separate passes find them, the ordered date is computed at the end, court-extract
 * filtering replaces the result list in place, and vocabulary is attached last
 * ({@code SetCourtRegister/index.js:39-45}). Modelling that as a chain of immutable records would
 * mean rebuilding the whole object four times and would cost the port the name-for-name mirror it is
 * reviewed against.
 *
 * <p>So the accumulation stays mutable and package-private. The register fragment that leaves the
 * pipeline is built from these and is immutable; nothing outside this package ever sees this form.
 */
// PMD.AvoidFieldNameMatchingMethodName: record-style accessors named for the legacy
// DefendantContextBase fields they carry. Renaming either half would cost the port the
// name-for-name mirror it is reviewed against.
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
final class DefendantContext {

    private final List<String> defendantIds = new ArrayList<>();
    private final List<String> cases = new ArrayList<>();
    private final List<String> applications = new ArrayList<>();
    private List<RegisterResult> results = new ArrayList<>();
    private String masterDefendantId;
    private Boolean youthDefendant;
    private String orderedDate;

    /* default */ List<String> defendantIds() {
        return defendantIds;
    }

    /* default */ List<String> cases() {
        return cases;
    }

    /* default */ List<String> applications() {
        return applications;
    }

    /* default */ List<RegisterResult> results() {
        return results;
    }

    /* default */ void results(final List<RegisterResult> replacement) {
        this.results = replacement;
    }

    /* default */ void addResults(final List<RegisterResult> additional) {
        this.results.addAll(additional);
    }

    /* default */ String masterDefendantId() {
        return masterDefendantId;
    }

    /* default */ void masterDefendantId(final String value) {
        this.masterDefendantId = value;
    }

    /* default */ Boolean youthDefendant() {
        return youthDefendant;
    }

    /* default */ void youthDefendant(final Boolean value) {
        this.youthDefendant = value;
    }

    /* default */ String orderedDate() {
        return orderedDate;
    }

    /* default */ void orderedDate(final String value) {
        this.orderedDate = value;
    }
}
