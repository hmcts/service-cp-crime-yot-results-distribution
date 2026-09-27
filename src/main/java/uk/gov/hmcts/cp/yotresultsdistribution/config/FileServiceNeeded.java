package uk.gov.hmcts.cp.yotresultsdistribution.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Whether this pod needs the framework file service at all.
 *
 * <p>Two halves write into it and neither owns it. The nightly run puts a render payload there
 * before it asks systemdocgenerator for a document, and the morning report puts its CSV there before
 * it asks notificationnotify to attach one - so "the file service is needed" is an <em>either</em>
 * and not the generation half's switch, which is what it was read as until review gate 7. Read as
 * the switch, a pod in FR-004's shape with the e-mail output on held no pool and no
 * {@code PayloadFileStore}, and did not start.
 *
 * <p><strong>Not a third cutover lever, and not a second one either.</strong> Neither setting is
 * read here to decide which implementation is live; both are deployment shape, and this condition
 * only answers whether the two beans that write a file are worth building. The one lever is still
 * the {@code YotResultsDistributionService} App Configuration flag, read once per run by the job.
 *
 * <p>A {@link Condition} rather than two {@code @ConditionalOnProperty} annotations, because
 * {@code @ConditionalOnProperty} is an <em>and</em> of its names and what is wanted is an
 * <em>or</em>: expressed as two configurations, one per half, the pod that has both switched on
 * would hold two of everything.
 */
public class FileServiceNeeded implements Condition {

    /** The generation half's master switch: the nightly run writes a render payload. */
    private static final String GENERATION_ENABLED = "yotresultsdistribution.generation.enabled";

    /** The report's e-mail output: the morning run writes a CSV to attach by id. */
    private static final String REPORT_EMAIL_ENABLED = "yotresultsdistribution.report.email.enabled";

    @Override
    public boolean matches(final ConditionContext context, final AnnotatedTypeMetadata metadata) {
        final Environment environment = context.getEnvironment();
        return switchedOn(environment, GENERATION_ENABLED)
                || switchedOn(environment, REPORT_EMAIL_ENABLED);
    }

    /**
     * Whether one half's switch is on, with an unset switch meaning off.
     *
     * <p>Off is the right default for both: each is a capability a deployment asks for, and the
     * failure of guessing wrong is a pool opened against a database this pod has no use for rather
     * than a morning that goes unreported.
     *
     * @param environment the resolved environment
     * @param setting     the half's own switch
     * @return whether that half is switched on
     */
    private static boolean switchedOn(final Environment environment, final String setting) {
        return environment.getProperty(setting, Boolean.class, Boolean.FALSE);
    }
}
