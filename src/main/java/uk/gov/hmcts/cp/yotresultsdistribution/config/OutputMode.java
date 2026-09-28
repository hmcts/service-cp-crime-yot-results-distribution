package uk.gov.hmcts.cp.yotresultsdistribution.config;

/**
 * What the pipeline does with a register it has assembled and validated.
 *
 * <p><strong>Not the cutover lever.</strong> The one thing that decides which implementation
 * generates a court register is the App Configuration flag {@code YotResultsDistributionService}
 * (constitution Principle III and the Cutover Rule). This is a build-time fallback: it is set at
 * deploy time for the whole life of a release, never flipped while a pod runs, and nothing in the
 * service reads it more than once. The plan says the same in its Complexity Tracking, and the reason
 * it exists at all is the design's fallback sequencing (§9) - one release may need the 001 shape
 * live while progression's leg is still deployed, and deleting the adapter now would make that
 * fallback a re-implementation.
 */
public enum OutputMode {

    /**
     * The register is written into this service's own store as an active RECORDED row.
     *
     * <p>The default, and what this increment is for: the nightly job batches those rows, renders
     * them and e-mails each Youth Offending Team, and progression is not called at all.
     */
    RECORD,

    /**
     * The 001 behaviour: one {@code add-court-register} POST to progression per register.
     *
     * <p>Kept only for the documented fallback sequencing while progression's leg is still deployed.
     */
    PROGRESSION_POST
}
