package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A counsel on the register — defence for a defendant, prosecution for a case or application.
 *
 * <p>Ports {@code Models/Counsel.js} against the vendored {@code courtRegisterCounsel.json}. Two
 * fields, and the first is composed rather than copied:
 * {@code [firstName, middleName, lastName].filter(item => item).join(' ').trim()}
 * ({@code Mappers/Counsel/CounselMapper.js:11}), so a counsel with no middle name gets one space,
 * not two. No legacy test asserts the composition — the one case that could has all three parts and
 * checks only {@code status}.
 *
 * @param name   the counsel's name, composed from the parts that are present
 * @param status the counsel's status, copied as it arrives
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CourtRegisterCounsel(String name, String status) {
}
