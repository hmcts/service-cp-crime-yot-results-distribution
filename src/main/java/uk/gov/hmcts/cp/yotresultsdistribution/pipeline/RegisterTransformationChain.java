package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.progression.OutboundContractValidator;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterTransformer;
import uk.gov.hmcts.cp.yotresultsdistribution.application.TransformationResult;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ContractValidationException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NoRegisterReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterFragment;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException;

/**
 * The whole transformation, in the order the legacy orchestrator runs it.
 *
 * <p>Build the fragment ({@code SetCourtRegister}), address it against the subscriptions the core
 * has already read ({@code CourtRegisterSubscriptions}), assemble the document
 * ({@code OutboundCourtRegister}), and hold it to the contract progression published before anybody
 * tries to send it. Four stages, three of which can legitimately end the run without a register.
 *
 * <p><strong>Each stage that declines says which reason it was.</strong> The legacy has the same
 * three answers plus a fourth from the orchestrator, and every one of them is a bare
 * {@code undefined} funnelled into {@code Success: true} (defect C33). Two of them —
 * {@code no-subscriptions} and {@code no-youth-defendants} — are this flow's commonest legitimate
 * outcomes, so an undifferentiated success is not a simplification, it is the reason a court centre
 * nobody subscribes to and a pipeline that has stopped working look identical from outside.
 *
 * <p><strong>The order of the first two questions is load-bearing, and is this chain's own.</strong>
 * A register that gathered no defendants matches no subscription either, so asking about
 * subscriptions first — which is what {@link AggregationMapper} does, because
 * {@code OutboundCourtRegister/index.js:17} asks before {@code :22} — would answer
 * {@code no-subscriptions} for a hearing whose real outcome is {@code no-defendants}. That is defect
 * C6: {@code SetCourtRegister/index.js:35-38} guards the gather with a condition that can never
 * fire, so an empty list flows on and the run reports success having done nothing. The gather is
 * therefore asked about first, here, before anything is addressed.
 *
 * <p><strong>Pure, by contract</strong> (constitution Principle V). Reference data's answer is an
 * argument rather than something any stage fetches; there is no clock behind any of them, so two
 * runs over one hearing produce one register; and nothing edits what it was handed — the legacy
 * passes one mutable hearing object down the whole chain and is saved only by the Durable Functions
 * serialisation boundary between activities.
 *
 * <p>The contract check is a stage of this chain rather than of the submission adapter, and it
 * imports no infrastructure to be one: {@link OutboundContractValidator} reads the vendored schemas
 * once, when it is built, and thereafter does nothing but apply them to a tree. Putting it here is
 * what makes an unsendable register a <em>transformation</em> outcome — visible, classified and
 * dead-lettered — rather than a 400 discovered at the far end and swallowed (C29 with C1).
 *
 * <p><strong>The defendant type is attached after the check, and that order is the settlement the
 * check itself forces.</strong> Increment 002 records the register instead of posting it, and the
 * batch's PDF payload prints its defendants under the {@code defendantType} progression used to
 * resolve for itself once the POST had arrived ({@code CourtRegisterHandler.java:131-153}); this
 * chain is where the hearing and the assembled document meet, so {@link DefendantTypeResolver} is
 * asked here. The field is a legal, optional one of the frozen <em>register document</em>
 * ({@code courtRegisterDocumentRequest.json:30-32}) and is <em>not</em> a field of the
 * {@code add-court-register} command this chain validates against, which is
 * {@code additionalProperties: false} - so a document carrying it is refused under
 * {@code UNKNOWN_FIELD [/defendantType]}. Validating first and attaching after therefore holds the
 * document to exactly the contract 001 held it to, byte for byte, and leaves the register this
 * increment records carrying the one field 002 adds.
 */
// PMD.OnlyOneReturn: each stage answers where it decides, which is the whole of C6, C33 and C36 —
// a single exit would put the four answers back behind one variable and lose which stage chose.
@SuppressWarnings("PMD.OnlyOneReturn")
public final class RegisterTransformationChain implements RegisterTransformer {

    private static final Logger LOG = LoggerFactory.getLogger(RegisterTransformationChain.class);

    /** The field of the claim-check envelope the hearing itself sits under. */
    private static final String HEARING = "hearing";

    /** The field of the claim-check envelope the results' share instant sits under. */
    private static final String SHARED_TIME = "sharedTime";

    private final RegisterBuilder registerBuilder;
    private final SubscriptionMatcher subscriptionMatcher;
    private final OutboundContractValidator contractValidator;
    private final DefendantTypeResolver defendantTypeResolver;

    /**
     * Creates the chain over its four stages.
     *
     * <p>No anomaly sink is held here. One is handed to each call, because it belongs to the run
     * being made and not to the chain making it: this object is a singleton in the running service,
     * and a counter it held would accumulate every hearing the pod has ever transformed.
     *
     * <p>The defendant-type rule is built here rather than handed in, which is what
     * {@link RegisterBuilder} and {@link SubscriptionMatcher} do with {@link Dates} and
     * {@link SubscriptionRules}: it is a pure rule object with no collaborator, no configuration and
     * no state, so there is nothing about it a caller could choose, and nothing a test could
     * usefully replace it with - {@code DefendantTypeResolverTest} holds the rule itself to
     * progression's recorded answers, and this chain's suite holds the wiring to the register that
     * comes out.
     *
     * @param builder   the fragment stage
     * @param matcher   the addressing stage
     * @param validator the stage that refuses a document progression would
     */
    public RegisterTransformationChain(
            final RegisterBuilder builder,
            final SubscriptionMatcher matcher,
            final OutboundContractValidator validator) {
        this.registerBuilder = builder;
        this.subscriptionMatcher = matcher;
        this.contractValidator = validator;
        this.defendantTypeResolver = new DefendantTypeResolver();
    }

    @Override
    public TransformationResult transform(
            final DistributionCommand command,
            final JsonNode hearingPayload,
            final JsonNode subscriptions,
            final Consumer<TransformationAnomaly> anomalies) {

        final JsonNode hearing = hearingOf(hearingPayload);
        final RegisterFragment fragment =
                registerBuilder.build(hearing, sharedTimeOf(hearingPayload));

        if (fragment.registerDefendants().isEmpty()) {
            // C6. Asked before anything is addressed, because a register with nobody on it matches
            // no subscription either and would otherwise be counted as a court centre nobody
            // subscribes to.
            return nothing(command, NoRegisterReason.NO_DEFENDANTS);
        }

        final List<JsonNode> matched = subscriptionMatcher.match(fragment, subscriptions);
        if (matched.isEmpty()) {
            return nothing(command, NoRegisterReason.NO_SUBSCRIPTIONS);
        }

        final CourtRegisterDocument document =
                AggregationMapper.map(fragment, matched, hearing, anomalies);
        if (document == null) {
            return nothing(command, assemblyDeclined(fragment));
        }

        validated(command, document);
        return new TransformationResult.Register(
                recorded(document, hearing), fragment.courtCentreOUCode());
    }

    /**
     * The register as this increment records it: the validated document, carrying the side of the
     * court application its defendants are on.
     *
     * <p>Answered by {@link DefendantTypeResolver} out of the hearing's own court applications, and
     * absent rather than empty where the register names no application at all. Progression writes
     * {@code StringUtils.EMPTY} there ({@code CourtRegisterHandler.java:84}); the field is optional
     * in the frozen register-document schema and the document is serialised {@code NON_NULL}, so a
     * hearing without an application leaves it out exactly as 001 did and every 001 recording comes
     * back byte-identical.
     *
     * <p>The document is rebuilt rather than edited, because it is a record and because nothing in
     * this chain edits what it was handed (constitution Principle V): the resolver is given the
     * assembled register and the hearing, and neither comes back changed.
     *
     * @param document the validated register
     * @param hearing  the hearing the claim-check payload carries, whose court applications the
     *                 register's application is looked up in
     * @return the register with its defendant type, or the register unchanged where the hearing
     *         carried no court application for it
     */
    private CourtRegisterDocument recorded(
            final CourtRegisterDocument document, final JsonNode hearing) {

        return defendantTypeResolver.resolve(hearing, document)
                .map(defendantType -> carrying(document, defendantType))
                .orElse(document);
    }

    /**
     * One register, with its defendant type on it.
     *
     * @param document      the validated register
     * @param defendantType the side of the court application its defendants are on
     * @return the same register, carrying the type
     */
    private static CourtRegisterDocument carrying(
            final CourtRegisterDocument document, final String defendantType) {

        return new CourtRegisterDocument(
                document.registerDate(),
                document.hearingDate(),
                document.hearingId(),
                document.courtCentreId(),
                document.fileName(),
                defendantType,
                document.hearingVenue(),
                document.recipients(),
                document.defendants());
    }

    /**
     * Why the aggregation produced nothing, given that it was handed a non-empty match.
     *
     * <p>Two answers remain to it at that point and they are different outcomes: the youth filter
     * left nobody on the register, or every matched subscriber was dropped before becoming a
     * recipient. The second is C36 — the legacy posts that register with {@code recipients:
     * undefined}, progression stores it, renders the PDF at 18:00 and then emits a notification
     * nothing subscribes to, so it sticks at GENERATED forever, visible to nobody. There is nobody
     * to distribute to, which is what {@code no-subscriptions} says.
     *
     * <p>The predicate is the same one {@link AggregationMapper} filters on, asked here to name the
     * outcome rather than to build the list. Both sites read the same flag on the same fragment, so
     * they cannot disagree; the mapper keeps its own guard because it is called directly, and a
     * stage that is only safe when its caller asked first is not safe.
     *
     * @param fragment the hearing's register fragment
     * @return the reason the register was not assembled
     */
    private static NoRegisterReason assemblyDeclined(final RegisterFragment fragment) {
        return fragment.registerDefendants().stream().anyMatch(RegisterDefendant::youthDefendant)
                ? NoRegisterReason.NO_SUBSCRIPTIONS
                : NoRegisterReason.NO_YOUTH_DEFENDANTS;
    }

    /**
     * Records which stage declined, and answers with its reason.
     *
     * @param command the validated request, for correlation
     * @param reason  the bounded reason
     * @return the answer
     */
    private static TransformationResult nothing(
            final DistributionCommand command, final NoRegisterReason reason) {

        // The permitted correlation set and a bounded code: no defendant, no court centre, nothing
        // from inside the payload (constitution Principle VII).
        LOG.info("The transformation produced no register. source={} requestId={} hearingId={} "
                        + "reason={}",
                command.source(), command.requestId(), command.hearingId(),
                reason.completion().value());
        return new TransformationResult.NoRegister(reason);
    }

    /**
     * Holds the assembled register to the frozen progression contract — defect fix C29.
     *
     * <p>The refusal is <strong>translated</strong> rather than wrapped, for the reason
     * {@link ContractValidationException} itself gives: a cause travels wherever the exception
     * travels, and this one travels into a dead-letter description and the log index. What is
     * carried across is the bounded violation and the JSON pointer of the field at fault — a path,
     * never a value, and every defendant on this register is a child.
     *
     * <p><strong>The pointer is written to the log, and only to the log.</strong> Without it C29
     * reports that a register was refused and never says by which field, which leaves support with
     * a hearing to re-derive by hand — and the pointer is the whole diagnostic, since the run's
     * recorded {@code failure_reason} is the bounded {@code OUTBOUND_CONTRACT_VIOLATION} and stays
     * that way. It is not persisted: this refusal happens in the transformation, before any
     * submission, so there is no {@code processed_output} row to carry it — the output cardinality
     * is 0..1 and the row is claimed by the POST that never happens — and {@code failure_reason} is
     * a bounded code that the dead-letter description, the metrics and the privacy suite all read
     * as one. Widening either would be schema churn spent on a value a WARN line already carries.
     *
     * <p>What makes it safe to write at all is that {@link OutboundContractValidator} builds the
     * pointer out of the instance location and, for a {@code required} failure, the missing
     * property's name. Both are schema vocabulary — and the document is serialised from this repo's
     * own records, so every property name in it is one this repository wrote. No part of it can be
     * a value, which is the only reason a court register's field path is loggable at all.
     *
     * <p>The tokens are {@code violation=} and {@code path=} rather than {@code reason=}: a
     * {@code reason} in this service's logs is one of the four bounded enumerations the privacy
     * suite checks against, and a JSON pointer is not one of them. It is bounded in its own way,
     * and named so that nobody has to decide which.
     *
     * @param command  the validated request, for correlation
     * @param document the assembled register
     * @throws TransformationFailedException if progression's contract would refuse it
     */
    private void validated(final DistributionCommand command,
            final CourtRegisterDocument document) {
        try {
            contractValidator.validate(document);
        } catch (ContractValidationException refused) {
            LOG.warn("The assembled register does not satisfy the progression contract, so it is "
                            + "not sent. source={} requestId={} hearingId={} violation={} path={}",
                    command.source(), command.requestId(), command.hearingId(),
                    refused.violation(), refused.field());
            throw new TransformationFailedException(
                    "the assembled register does not satisfy the progression contract: "
                            + refused.violation() + " at " + refused.field(),
                    ReasonCode.OUTBOUND_CONTRACT_VIOLATION);
        }
    }

    /**
     * The hearing inside the claim-check envelope.
     *
     * @param payload the claim-check envelope
     * @return the hearing it carries
     * @throws TransformationFailedException if the envelope carries no hearing
     */
    private static JsonNode hearingOf(final JsonNode payload) {
        final JsonNode hearing = payload == null ? null : payload.get(HEARING);
        if (hearing == null || hearing.isNull()) {
            throw new TransformationFailedException("claim-check payload carries no hearing");
        }
        return hearing;
    }

    /**
     * The instant the results were shared, as the claim-check envelope records it.
     *
     * <p>Read from the envelope rather than from the command, which is where
     * {@code CourtRegisterOrchestrator/index.js:28} reads it and where the core reads it for the
     * subscription day — so the day a register is addressed on and the day it is dated cannot come
     * apart. Every date on the register derives from it, so an envelope without one is unusable, and
     * unusable in the same way on every redelivery.
     *
     * @param payload the claim-check envelope
     * @return the shared time it carries
     * @throws TransformationFailedException if the envelope carries no shared time
     */
    private static String sharedTimeOf(final JsonNode payload) {
        final JsonNode sharedTime = payload.get(SHARED_TIME);
        if (sharedTime == null || !sharedTime.isString()) {
            throw new TransformationFailedException("claim-check payload carries no shared time");
        }
        return sharedTime.stringValue();
    }
}
