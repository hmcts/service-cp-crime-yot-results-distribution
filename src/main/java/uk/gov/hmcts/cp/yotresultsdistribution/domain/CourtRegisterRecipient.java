package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One organisation the finished register is emailed to.
 *
 * <p>Ports {@code Models/Recipient.js} against the vendored {@code courtRegisterRecipient.json}.
 * Both {@code emailAddress1} and {@code emailTemplateName} are required by the contract, which is
 * why the mapper drops a matched subscription that has no first address rather than sending a
 * recipient progression would reject ({@code Mappers/Recipient/RecipientMapper.js:24-26}) and why
 * the template name falls back to {@code cr_standard} when the subscription names none
 * ({@code :20-22}) — a default no legacy test asserts.
 *
 * <p>{@code emailAddress2} is carried and, as far as anyone has traced, never used downstream. It
 * stays carried: this port does not decide what progression does with a field it has always been
 * given.
 *
 * @param recipientName     the subscribing organisation's name
 * @param emailAddress1     the address the register is sent to, trimmed
 * @param emailAddress2     the second address, trimmed, where the subscription carries one
 * @param emailTemplateName the notify template, or {@code cr_standard} by default
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CourtRegisterRecipient(
        String recipientName,
        String emailAddress1,
        String emailAddress2,
        String emailTemplateName) {
}
