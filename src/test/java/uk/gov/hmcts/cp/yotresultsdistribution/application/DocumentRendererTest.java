package uk.gov.hmcts.cp.yotresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The renderer port's shape, which is the one thing that stops the query coming back.
 *
 * <p>Nothing else in this repository can hold that claim. A deletion is proved by the suite that
 * would fail if the thing returned, and the thing here is a <em>method on an interface</em>: an
 * adapter could implement one nobody declared and no test would notice, because every other suite
 * drives the port through the calls it already makes. So the port itself is the subject, read by
 * reflection, and the assertion is the count as well as the name - a second method is a second
 * conversation with systemdocgenerator, and the whole of FR-006 is that there is not one.
 *
 * <p><strong>Why it mattered.</strong> The retired query was this flow's one synchronous coupling
 * to the renderer: a call made between runs, on a schedule of its own, whose answer could fail a
 * batch on the strength of something systemdocgenerator could not say. What replaces it asks
 * nothing of anybody - a batch still awaiting its render when the next run begins is released by
 * the run itself, from the store's own columns. A port that declared the query again would be the
 * first half of that coming back.
 */
@DisplayName("the renderer port")
class DocumentRendererTest {

    /** The one conversation this service has with systemdocgenerator. */
    private static final String THE_ONE_METHOD = "requestRender";

    @Test
    @DisplayName("declares one method, because there is one conversation")
    void the_renderer_port_declares_one_method() {
        assertThat(DocumentRenderer.class.getDeclaredMethods())
                .as("asking for a document is a command; asking what became of one was a query, "
                        + "and nothing asks systemdocgenerator anything between runs any more "
                        + "(FR-006)")
                .extracting(Method::getName)
                .containsExactly(THE_ONE_METHOD);
    }
}
