package uk.gov.hmcts.cp.simulation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configures WireMock stubs via the admin API ({@code /__admin/mappings}) for the two outbound
 * calls the intake leg makes:
 *
 * <ol>
 *   <li>Hearing payload fetch (results-query-api GET) — the Redis claim-check fallback
 *   <li>NOW subscriptions (referencedata-query-api GET)
 * </ol>
 *
 * <p>There is no third, outbound stub: the register document is recorded in this service's own
 * store, not POSTed. The hearing is {@code fixtures/base/hearing-with-surviving-youth-defendant.json}
 * and the subscription is {@code NowSubscriptionFixtures.youthCourtRegisterSubscription("B01LY00")}
 * with a recipient of its own, the pair that carries a hearing all the way to
 * {@code completion_reason = recorded}. The recipient's address must pass the frozen register
 * contract's {@code format: email} as the service's validator applies it: an
 * {@code example.invalid} address fails every register with {@code OUTBOUND_CONTRACT_VIOLATION}.
 * It is on the reserved {@code example.com} domain so nothing can reach a real mailbox even if the
 * 18:00 leg runs against these registers.
 *
 * <p>The mappings are the files under {@code src/gatling/resources/wiremock/mappings/} — the same
 * files {@code setup-wiremock-stubs.sh} installs in the pipeline. Edit the files, not this class.
 * Installing them is additive: the compose stack's own mappings (systemdocgenerator,
 * notificationnotify, App Configuration) are left in place.
 *
 * <p>This path is for local runs, and here a stub that cannot be installed aborts the run: without
 * the stubs every request goes transient, and a publish-only Gatling report would still read green.
 * In the pipeline the Gatling process cannot reach WireMock (the agent's istio sidecar routes
 * localhost:8080 to the service pod), so the shell script installs the stubs and the pipeline sets
 * {@code -Dgatling.wiremockViaScript=true}, which skips this class entirely.
 */
public final class WireMockStubs {

    private static final Logger LOG = LoggerFactory.getLogger(WireMockStubs.class);

    private static final List<String> MAPPINGS = List.of(
            "wiremock/mappings/hearing-payload.json",
            "wiremock/mappings/now-subscriptions.json");

    private WireMockStubs() {
    }

    /**
     * Resets the request journal and installs all stubs.
     *
     * @param wiremockUrl the WireMock base URL (e.g. {@code http://localhost:8080})
     */
    public static void configure(String wiremockUrl) {
        if (Boolean.getBoolean("gatling.wiremockViaScript")) {
            LOG.info("WireMock stubs installed by setup-wiremock-stubs.sh; not configuring {}",
                    wiremockUrl);
            return;
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            resetJournal(client, wiremockUrl);
            MAPPINGS.forEach(mapping -> createStub(client, wiremockUrl, mapping));
            LOG.info("WireMock stubs configured at {}", wiremockUrl);
        }
    }

    private static void resetJournal(HttpClient client, String wiremockUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(wiremockUrl + "/__admin/requests"))
                    .DELETE()
                    .build();
            HttpResponse<Void> response = client.send(request,
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("ABORTING: WireMock journal reset at " + wiremockUrl
                        + " returned " + response.statusCode());
            }
            LOG.info("WireMock request journal reset");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("ABORTING: WireMock unreachable at " + wiremockUrl
                    + " (set -Dgatling.wiremockViaScript=true if the pipeline installed the stubs)",
                    e);
        }
    }

    private static void createStub(HttpClient client, String wiremockUrl, String mapping) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(wiremockUrl + "/__admin/mappings"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(read(mapping)))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201) {
                throw new IllegalStateException("ABORTING: WireMock stub creation for " + mapping
                        + " returned " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("ABORTING: WireMock unreachable at " + wiremockUrl
                    + " while installing " + mapping, e);
        }
    }

    private static String read(String resource) {
        try (InputStream in = WireMockStubs.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing WireMock mapping on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read WireMock mapping " + resource, e);
        }
    }
}
