package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A published judicial result as the register prints it.
 *
 * <p>Ports {@code Models/Result.js} against the vendored {@code courtRegisterResult.json}. Two
 * fields off each judicial result the register carries — and note the second is renamed on the way
 * out: {@code judicialResult.cjsCode} becomes {@code cjsResultCode}
 * ({@code Mappers/Result/ResultMapper.js:11}).
 *
 * <p>Not to be confused with {@link RegisterResult}, which is the gathered, level-tagged result the
 * pipeline works with internally. This is the two-field projection of that result's
 * {@code judicialResult} that leaves the service; the tagging exists so that the right results end
 * up under the right offence, case and defendant, and none of it is printed.
 *
 * @param resultText    the result's text — the contract's one required field
 * @param cjsResultCode the result's CJS code
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CourtRegisterResult(String resultText, String cjsResultCode) {
}
