package uk.gov.hmcts.cp.yotresultsdistribution.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A request whose action is the server's answer rather than the caller's, by every route there is.
 *
 * <p>The wrapper overrides the three header reads together, because the authorisation filter uses
 * {@code getHeader} and something downstream may well use the other two: a value that could be seen
 * through one accessor and not another would be a hole with a longer life than the first one.
 * Header names are case-insensitive in HTTP and are treated so here.
 *
 * <p><strong>A {@code null} action is an answer too, and it is the header's absence.</strong> Every
 * request is wrapped, including the ones this service names no action for, because "this service
 * derived nothing" and "the caller said it was the regeneration" must not arrive at the
 * authorisation filter as the same thing. A {@code HEAD} on a path served for {@code GET} is
 * exactly such a request - Spring answers it through the {@code @GetMapping}, and the filter's
 * lookup is keyed by method as well as path - so without this it would have been authorised
 * against a name the caller chose (FR-036). Stripped, it falls to the library's computed
 * {@code "HEAD /operations/flag"}, which matches no rule and is refused.
 *
 * <p><strong>And {@code CPP-ACTION} is not the only route to the resolver.</strong>
 * {@code cp-auth-rules-filter} 1.0.7 reads {@code getContentType()} first, then {@code Accept},
 * and takes the first {@code application/vnd.<token>} it finds in either as the action - both of
 * them <em>ahead</em> of the header this wrapper writes. A caller admitted to the cheapest action
 * could otherwise send {@code Content-Type: application/vnd.yot-results-distribution-operations.check-flag+json}
 * to the listing and be authorised against {@code check-flag} while being served
 * {@code list-batches}. So on this service's own paths a vendor media type is answered as
 * {@code application/json}: the resolver can see no vendor token at all, and the only action it can
 * resolve is the one this service derived. These endpoints take and return plain
 * {@code application/json}, so nothing legitimate is altered by it.
 *
 * <p>The sanitising is confined to {@code /operations} paths - it covers every request this service
 * names an action for, and the unnamed methods on those same paths, and leaves actuator's own
 * {@code application/vnd.spring-boot.actuator.v3+json} negotiation exactly as it was.
 *
 * <p>Nothing else about the request is altered. The body, the query string and every other header
 * arrive at the controller exactly as they were sent.
 */
/* default */ class ActionRequestWrapper extends HttpServletRequestWrapper {

    /** The media type every one of these endpoints speaks, and the only one answered for them. */
    private static final String PLAIN_JSON = "application/json";

    /** The request header a media type arrives in. */
    private static final String CONTENT_TYPE = "Content-Type";

    /** The header a caller states what it will take in. */
    private static final String ACCEPT = "Accept";

    /**
     * The vendor token the authorisation library looks for, written as that library writes it.
     *
     * <p>Copied from {@code RequestActionResolver} deliberately: this has to match what the
     * resolver would find, not what a stricter reading of RFC 9110 would, because anything the
     * resolver finds and this does not is the hole back again.
     */
    private static final Pattern VENDOR_TOKEN = Pattern.compile(
            "(?i)\\bapplication/vnd\\.([a-z0-9][a-z0-9._-]*)(?:\\+[^\\s;,]+)?\\b");

    /**
     * The action this service derived for the request, or {@code null} where it derived none -
     * which overrides whatever arrived, either way.
     */
    private final String action;

    /** Whether a vendor media type on this request is answered away, which is so on our paths. */
    private final boolean ours;

    /**
     * Wraps one request around one derived action, or around the absence of one.
     *
     * @param request the request as it arrived
     * @param action  the action name this service derived from the path and the method, or
     *                {@code null} where this service names no action for the request
     * @param ours    whether the request is for one of this service's own paths, on which a vendor
     *                media type is answered as {@code application/json} so that it can name no
     *                action
     */
    /* default */ ActionRequestWrapper(final HttpServletRequest request, final String action,
            final boolean ours) {
        super(request);
        this.action = action;
        this.ours = ours;
    }

    @Override
    public String getContentType() {
        return withoutVendorToken(super.getContentType());
    }

    // PMD.OnlyOneReturn: each header is answered where it is decided; carrying three verdicts to
    // one exit would say less about which of them a reader is looking at.
    @SuppressWarnings("PMD.OnlyOneReturn")
    @Override
    public String getHeader(final String name) {
        if (OperationsActionFilter.ACTION_HEADER.equalsIgnoreCase(name)) {
            return action;
        }
        if (namesAMediaType(name)) {
            return withoutVendorToken(super.getHeader(name));
        }
        return super.getHeader(name);
    }

    // PMD.OnlyOneReturn: as above.
    @SuppressWarnings("PMD.OnlyOneReturn")
    @Override
    public Enumeration<String> getHeaders(final String name) {
        if (OperationsActionFilter.ACTION_HEADER.equalsIgnoreCase(name)) {
            return Collections.enumeration(action == null ? List.of() : List.of(action));
        }
        if (namesAMediaType(name)) {
            final List<String> answered = new ArrayList<>();
            for (final String value : Collections.list(super.getHeaders(name))) {
                answered.add(withoutVendorToken(value));
            }
            return Collections.enumeration(answered);
        }
        return super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        final List<String> names = Collections.list(super.getHeaderNames());
        names.removeIf(OperationsActionFilter.ACTION_HEADER::equalsIgnoreCase);
        if (action != null) {
            names.add(OperationsActionFilter.ACTION_HEADER);
        }
        return Collections.enumeration(names);
    }

    /**
     * Whether a header name is one the action resolver reads a media type out of.
     *
     * @param name the header name, in whatever case it was asked for
     * @return {@code true} for {@code Content-Type} and {@code Accept}
     */
    private static boolean namesAMediaType(final String name) {
        return CONTENT_TYPE.equalsIgnoreCase(name) || ACCEPT.equalsIgnoreCase(name);
    }

    /**
     * One media-type value as this service answers it.
     *
     * <p>The whole value is replaced rather than the token edited out of it: a list the resolver
     * walks entry by entry would otherwise need every entry to be judged, and a partial edit is
     * exactly the kind of thing that stops matching when the library's pattern is widened.
     *
     * @param value the value as the caller sent it, or {@code null} where the header is absent
     * @return {@code application/json} where a vendor token is present on one of this service's
     *         paths, and the value untouched otherwise
     */
    private String withoutVendorToken(final String value) {
        return ours && value != null && VENDOR_TOKEN.matcher(value).find() ? PLAIN_JSON : value;
    }
}
