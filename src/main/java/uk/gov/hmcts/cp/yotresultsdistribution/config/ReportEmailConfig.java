package uk.gov.hmcts.cp.yotresultsdistribution.config;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.notificationnotify.NotificationNotifyReportMailer;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.report.EmailReportSink;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ExceptionReportSink;
import uk.gov.hmcts.cp.yotresultsdistribution.application.PayloadFileStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ReportMailer;

/**
 * The report's second output: the exception list as a CSV, e-mailed to support.
 *
 * <p>Behind <strong>one</strong> condition, {@code yotresultsdistribution.report.email.enabled}, and
 * deliberately no other. In particular it is not conditional on {@code yotresultsdistribution.report.enabled}:
 * the 07:00 schedule and {@code POST /operations/exception-reports} are two callers of one report,
 * and the sink belongs to neither of them - an endpoint that could not resolve it would decline an
 * output the deployment says is switched on.
 *
 * <p>With the output off, a context holds one sink, the log's, and the job's run line reads
 * {@code delivered_email=disabled}: not {@code skipped}, because nothing was decided by anybody.
 *
 * <p><strong>The output ships switched off.</strong> The Notify template is the notificationnotify
 * team's to provide, and until it exists {@code enabled} stays false in every environment - user
 * stories 1, 2, 3 and 5 are complete without it, and {@code --email} is refused with a bounded
 * reason rather than silently doing nothing. {@link PropertiesValidator} refuses the flag with no
 * template or no recipients, so an environment cannot switch it on half-configured.
 *
 * <p>The two settings the beans below need are the environment's, never this repository's: the
 * template id and the recipient list arrive from Key Vault through the CSI driver, and neither is
 * ever a chart value - they are a Notify configuration and people's addresses.
 *
 * <p>Its own file rather than two more beans on {@link LiveNotificationConfig}, because that one is
 * conditional on the generation half being enabled and this output must work on the pod FR-004
 * describes, which generates nothing. Excluded from the {@code test} profile alongside the rest of
 * the live wiring.
 *
 * <p><strong>The file this output writes is what made the same argument about a third bean.</strong>
 * The CSV goes into the framework file service before anybody is told about it, and the
 * {@code PayloadFileStore} that writes it - and the second datasource under that - were declared
 * behind the generation half's switch, so the pod this configuration exists for could not start.
 * Both are behind {@link FileServiceNeeded} now, which is this switch or the generation half's;
 * {@link PropertiesValidator} refuses either half with no {@code yotresultsdistribution.fileservice.url},
 * naming the half that asked.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "yotresultsdistribution.report.email", name = "enabled",
        havingValue = "true")
public class ReportEmailConfig {

    /**
     * The report's own send, over notificationnotify's command API.
     *
     * <p>Its own {@code RestClient} rather than the register leg's, for the reason this
     * configuration exists at all: the notifier's client is built only where the generation half is
     * enabled, and this output has to work where it is not. Both timeouts are set, as they are on
     * every other client this service builds - a POST with no read timeout can outlive the run that
     * was supposed to bound it, and here that is a scheduled run hanging on one recipient.
     *
     * @param properties   the bound settings, for the endpoint, the identity and the two timeouts
     * @param objectMapper the shared mapper, so the body is written exactly as every other JSON is
     * @return the port
     */
    @Bean
    public ReportMailer reportMailer(
            final YotResultsDistributionProperties properties, final ObjectMapper objectMapper) {

        final YotResultsDistributionProperties.Endpoints endpoints = properties.endpoints();
        return new NotificationNotifyReportMailer(
                RestClient.builder()
                        .baseUrl(endpoints.notificationnotify())
                        .requestFactory(requestFactory(endpoints))
                        .build(),
                endpoints.systemUserId(),
                objectMapper);
    }

    /**
     * The e-mail sink, which is the second {@code ExceptionReportSink} on a context that has one.
     *
     * <p>The file store it is handed is the port the generation half also writes payloads through -
     * one caller more of a pinned, write-only contract, not a second use of it - and the reason that
     * port is chosen by {@link FileServiceNeeded} rather than by the generation half's switch: a
     * file in the file service is what the two outward legs have in common.
     *
     * @param files      where the CSV goes, over the shared file-service port
     * @param mailer     the report's own send
     * @param properties the report's settings, for the template and the recipients
     * @return the sink
     */
    @Bean
    public ExceptionReportSink emailReportSink(final PayloadFileStore files,
            final ReportMailer mailer, final ReportProperties properties) {

        // Parsed here rather than held as a UUID on the record, exactly as the register's template
        // is: the shape is validated at startup by PropertiesValidator, which names the setting
        // when it refuses, and a record that refused a malformed id would say only that binding
        // failed.
        return new EmailReportSink(files, mailer,
                UUID.fromString(properties.email().templateId()),
                properties.email().recipients());
    }

    /**
     * A request factory with both timeouts set.
     *
     * <p>The simple factory rather than a pooled client, for the reason the other clients use it:
     * this is one POST per recipient once a morning, and a pooled client would hold background
     * threads for the lifetime of every context to save nothing.
     */
    private static ClientHttpRequestFactory requestFactory(
            final YotResultsDistributionProperties.Endpoints endpoints) {
        final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(endpoints.connectTimeout());
        factory.setReadTimeout(endpoints.readTimeout());
        return factory;
    }
}
