package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The two string operations the outbound mappers share, with JavaScript's semantics rather than
 * Java's.
 *
 * <p>Both are one legacy expression each, written once here because they are written four times
 * over there and because the thing that makes them non-obvious — the filter is on
 * <em>truthiness</em>, so an empty string is dropped exactly as an absent field is — is a rule worth
 * being able to read in one place.
 *
 * <ul>
 *   <li>{@link #composedName} is
 *       {@code [firstName, middleName, lastName].filter(item => item).join(' ').trim()}, which the
 *       counsel mapper ({@code Mappers/Counsel/CounselMapper.js:11}), the parent-guardian mapper
 *       ({@code .../ParentGuardian/ParentGuardianMapper.js:19}) and the youth-defendant mapper
 *       ({@code .../YouthDefendant/YouthDefendantMapper.js:37}) each write out in full. A person
 *       with no middle name gets one space and not two; a person with no name at all composes to
 *       the empty string, which is what {@code trim()} on an empty join leaves behind.</li>
 *   <li>{@link #trimmed} is {@code emailAddress ? emailAddress.trim() : emailAddress}
 *       ({@code .../Recipient/RecipientMapper.js:36-38}) — an address the trim empties is an
 *       address with nothing in it, which is what makes the recipient drop reachable at all.</li>
 * </ul>
 *
 * <p><strong>Both of them end in {@code trim()}, and that word means two different things.</strong>
 * {@code String.prototype.trim()} strips the whole of JavaScript's {@code WhiteSpace} and
 * {@code LineTerminator} production — which includes {@code U+00A0}, the ideographic space, the two
 * separators and the byte-order mark. Java's {@link String#trim()} strips every codepoint up to
 * {@code U+0020} and nothing above it, and {@link String#strip()} asks
 * {@link Character#isWhitespace(int)}, which answers <em>false</em> for {@code U+00A0},
 * {@code U+2007} and {@code U+202F} and knows nothing of {@code U+FEFF}. Neither is the legacy's
 * rule, and they are wrong in opposite directions: Java's takes out four separator controls
 * JavaScript leaves in. {@link #jsTrim} is the rule itself, so a child whose name arrives padded
 * with a non-breaking space reaches the register named what the legacy named them and not a
 * character wider.
 *
 * <p>Pure, like everything else in the transformation: strings and nodes in, strings out.
 */
final class JsStrings {

    /** The parts a person's name is composed from, in the order the legacy joins them. */
    private static final List<String> NAME_PARTS = List.of("firstName", "middleName", "lastName");

    private JsStrings() {
    }

    /**
     * A person's name, composed from the parts they carry.
     *
     * @param person the payload's person block — a counsel, an associated person, or a defendant's
     *               person details
     * @return the composed name, which is the empty string where the person carries no name part
     */
    /* default */ static String composedName(final JsonNode person) {
        final List<String> parts = new ArrayList<>(NAME_PARTS.size());
        for (final String namePart : NAME_PARTS) {
            if (Json.truthy(person, namePart)) {
                parts.add(Json.text(person, namePart));
            }
        }
        return jsTrim(String.join(" ", parts));
    }

    /**
     * The values that are there, joined — and nothing at all where none of them were.
     *
     * <p>The offence mapper's wording join (defect fix C24) is this: a wording and a legislation
     * where there are both, either one alone where there is one, and an absent field rather than a
     * sentinel with {@code undefined} hanging off it where there is neither.
     *
     * @param separator what to put between the values that are there
     * @param values    the values to join, in order; each may be {@code null} or empty
     * @return the joined value, or {@code null} where every value was absent or empty
     */
    /* default */ static String joinedOnTruth(final String separator, final String... values) {
        final List<String> present = new ArrayList<>(values.length);
        for (final String value : values) {
            if (value != null && !value.isEmpty()) {
                present.add(value);
            }
        }
        return present.isEmpty() ? null : String.join(separator, present);
    }

    /**
     * A value with its surrounding space removed, and nothing where there was nothing.
     *
     * @param value the value to trim; may be {@code null}
     * @return the trimmed value, or {@code null}
     */
    /* default */ static String trimmed(final String value) {
        return value == null ? null : jsTrim(value);
    }

    /**
     * A value with the space {@code String.prototype.trim()} would remove taken off both ends.
     *
     * <p>Written as the specification's own set rather than as an approximation of it, because both
     * approximations Java offers are wrong and are wrong in opposite directions — see this class's
     * own note. The characters are all in the basic plane and none is a surrogate, so scanning by
     * {@code char} reads the same as scanning by codepoint.
     *
     * @param value the value to trim; never {@code null}
     * @return the value with its leading and trailing JavaScript whitespace removed
     */
    private static String jsTrim(final String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isJavaScriptWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && isJavaScriptWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * Whether a character is one {@code String.prototype.trim()} removes.
     *
     * <p>ECMA-262's {@code WhiteSpace} production — tab, vertical tab, form feed, space, no-break
     * space, zero-width no-break space and every character of the Unicode {@code Zs} category — plus
     * {@code LineTerminator}: line feed, carriage return, line separator and paragraph separator.
     * The {@code Zs} category is spelled out rather than asked of
     * {@link Character#getType(char)}, so this list cannot change under the port when a JDK adopts a
     * newer Unicode revision than the recording's runtime did.
     *
     * @param character the character
     * @return whether JavaScript would trim it
     */
    private static boolean isJavaScriptWhitespace(final char character) {
        return switch (character) {
            case '\t', '\n', '\u000b', '\f', '\r', ' ', '\u00a0',
                 '\u1680', '\u2028', '\u2029', '\u202f', '\u205f', '\u3000', '\ufeff' -> true;
            default -> character >= '\u2000' && character <= '\u200a';
        };
    }
}
