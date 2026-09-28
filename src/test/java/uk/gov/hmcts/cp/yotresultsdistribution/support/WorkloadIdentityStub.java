package uk.gov.hmcts.cp.yotresultsdistribution.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * The three facts the AKS workload-identity webhook projects into a deployed pod.
 *
 * <p>{@code LiveFeatureFlagConfig} refuses to build a LIVE flag reader without all three, and it is
 * right to: a deployed pod that had lost them would skip every night on an unreadable flag and look
 * exactly like a store outage. A suite whose subject is the <em>wiring</em> of a generating pod
 * therefore has to look like a generating pod, and this supplies them as system properties, which is
 * where {@code Environment.getProperty} finds a variable a deployment would set.
 *
 * <p>The token file is real and empty. Nothing here asks App Configuration for anything, so the
 * token is never read; what the credential builder wants is a path, and a path that does not exist
 * is a different failure from the one this fixture is avoiding.
 *
 * <p>All three are removed afterwards, so a suite that runs next in the same JVM and expects a
 * laptop does not find itself on a pod.
 */
public final class WorkloadIdentityStub implements BeforeAllCallback, AfterAllCallback {

    /** The pod's own client id, projected by the webhook. */
    private static final String CLIENT_ID = "AZURE_CLIENT_ID";

    /** The directory that identity lives in, projected by the same webhook. */
    private static final String TENANT_ID = "AZURE_TENANT_ID";

    /** Where the projected federated token is mounted. */
    private static final String TOKEN_FILE = "AZURE_FEDERATED_TOKEN_FILE";

    private static final String[] PROJECTED = {CLIENT_ID, TENANT_ID, TOKEN_FILE};

    private Path token;

    @Override
    public void beforeAll(final ExtensionContext extension) {
        try {
            token = Files.createTempFile("federated-token", ".jwt");
            Files.writeString(token, "", StandardCharsets.UTF_8);
        } catch (final IOException notCreated) {
            throw new UncheckedIOException(
                    "the federated token file the credential is built from could not be created",
                    notCreated);
        }
        System.setProperty(CLIENT_ID, "8f2c1d47-0b93-4e5a-9c31-6d0a7b4e2f18");
        System.setProperty(TENANT_ID, "531ff96d-0ae9-462a-8d2d-bec7c0b42082");
        System.setProperty(TOKEN_FILE, token.toString());
    }

    @Override
    public void afterAll(final ExtensionContext extension) {
        for (final String projected : PROJECTED) {
            System.clearProperty(projected);
        }
        try {
            Files.deleteIfExists(token);
        } catch (final IOException notDeleted) {
            throw new UncheckedIOException("the federated token file was left behind", notDeleted);
        }
    }
}
