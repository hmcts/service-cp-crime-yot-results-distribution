package uk.gov.hmcts.cp.yotresultsdistribution.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The settings the operations API is served under.
 *
 * <p>Bound from the {@code yotresultsdistribution.operations} keys. The defaults live <strong>here</strong>,
 * and they are the whole of them: {@code application.yaml} carries no {@code yotresultsdistribution.operations}
 * block, and gains one only when the task that adds the {@code audit.http.*} settings does. They are
 * stated on the record rather than only in a file for the reason 001's, 002's and 003's are: the
 * values are visible to the code that depends on them, and a missing configuration file cannot
 * silently change what an operator is allowed to do.
 *
 * <p><strong>{@link #enabled} is deployment shape and not a cutover lever</strong> (FR-044). It
 * decides whether the seven endpoints are served, and nothing else; the one lever that decides
 * which implementation is live is the App Configuration flag {@code YotResultsDistributionService}, and
 * nothing here may become a second one.
 *
 * <p>The other two settings exist because HTTP reaches further than {@code kubectl exec} did.
 * {@link #supersedeMaxAge} bounds an irreversible mutation that the command it replaces was
 * unbounded in, because the command was reachable only by someone already inside the namespace with
 * a runbook open. {@link #lockWait} is how long the background regeneration waits for the nightly
 * run's lock before recording that it could not have it - zero being a non-blocking attempt, which
 * is the only kind that cannot sit on a request thread.
 *
 * <p>Every refusal these values can earn lives in {@link PropertiesValidator}, beside every other
 * startup refusal this service has.
 *
 * @param enabled         whether the {@code /operations/**} endpoints are served
 * @param supersedeMaxAge the oldest {@code sharedBefore} the supersede endpoint will accept; an
 *                        unbounded irreversible mutation is one keystroke from giving up the
 *                        estate's whole history of registers
 * @param lockWait        how long the background regeneration waits for the register-generation
 *                        lock before recording that it could not take it
 */
@ConfigurationProperties(prefix = "yotresultsdistribution.operations")
public record OperationsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30d") Duration supersedeMaxAge,
        @DefaultValue("0s") Duration lockWait) {
}
