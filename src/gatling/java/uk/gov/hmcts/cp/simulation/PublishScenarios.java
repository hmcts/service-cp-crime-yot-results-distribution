package uk.gov.hmcts.cp.simulation;

import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.jms.JmsDsl.jms;

import io.gatling.javaapi.core.ScenarioBuilder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The publish chain shared by every scenario: generate a message, publish it, and record it in the
 * {@link PublishManifest} only once the send has succeeded.
 *
 * <p>When an ephemeral Redis URI is configured, the claim-check payload is seeded before the send
 * and before the timestamp. The verifier's publish-to-record latency includes the broker publish but
 * not fixture setup. A failed send exits the virtual user before the manifest write: a message that
 * never reached the queue must not be counted as a request that produced no output.
 *
 * <p>The body is sent as a JMS {@code BytesMessage}, which Qpid JMS encodes as an AMQP {@code data}
 * section — the shape the producer's Azure SDK sends, and the only one
 * {@code ServiceBusReceivedMessage.getBody()} reads. A {@code TextMessage} is encoded as an AMQP
 * {@code value} section instead, and the listener's {@code getBody()} throws
 * {@code UnsupportedOperationException} on it, so every delivery would be abandoned and
 * redelivered until it dead-lettered.
 */
final class PublishScenarios {

    private PublishScenarios() {
    }

    static ScenarioBuilder publishing(final String simulation, final String scenarioName,
                                      final String requestName, final String queueName,
                                      final AtomicInteger publishedCount) {
        return scenario(scenarioName)
                .exec(session -> {
                    final PublishedMessage message = MessageBuilder.next();
                    RedisPayloadSeeder.seed(message);
                    return session.set("message", message)
                            .set("payload", message.body().getBytes(StandardCharsets.UTF_8))
                            .set("publishedAt", System.currentTimeMillis());
                })
                .exec(jms(requestName).send()
                        .queue(queueName)
                        .bytesMessage(session -> session.<byte[]>get("payload")))
                .exitHereIfFailed()
                .exec(session -> {
                    PublishManifest.record(simulation, scenarioName,
                            session.<PublishedMessage>get("message"),
                            session.getLong("publishedAt"));
                    publishedCount.incrementAndGet();
                    return session;
                });
    }
}
