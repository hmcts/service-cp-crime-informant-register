package uk.gov.hmcts.cp.informantregister.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One defendant's entry in a court session of the register.
 *
 * <p>The schema requires exactly two components — {@code name} and {@code address1} — and leaves the
 * other twelve optional, including the cases and the results. That is a surprisingly thin
 * requirement for the node that carries the document's substance, and it is reproduced here
 * unchanged: this record's job is to make the contract representable, not to tighten it.
 *
 * <p>{@code dateOfBirth} is a string even though the schema marks it {@code format: date}, matching
 * the results-side binding, which holds it as {@code String}. The value written there is whatever
 * the ported pipeline renders, and typing it would impose a rendering the consumer never asked for.
 *
 * <p>Everything on this record is defendant PII. Nothing here may be logged at {@code info} or
 * above.
 *
 * @param name                           the defendant's name as rendered; required by the schema
 * @param dateOfBirth                    the date of birth, as rendered
 * @param address1                       address line 1; required by the schema
 * @param address2                       address line 2
 * @param address3                       address line 3
 * @param address4                       address line 4
 * @param address5                       address line 5
 * @param postCode                       the postcode
 * @param nationality                    the nationality
 * @param title                          the title
 * @param firstName                      the first name
 * @param lastName                       the last name
 * @param prosecutionCasesOrApplications the cases and applications the defendant appeared on
 * @param results                        results recorded against the defendant as a whole
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InformantRegisterDefendant(
        String name,
        String dateOfBirth,
        String address1,
        String address2,
        String address3,
        String address4,
        String address5,
        String postCode,
        String nationality,
        String title,
        String firstName,
        String lastName,
        List<InformantRegisterCaseOrApplication> prosecutionCasesOrApplications,
        List<InformantRegisterResult> results) {

    /**
     * Freezes both lists so the defendant cannot be changed after it is built.
     */
    public InformantRegisterDefendant {
        prosecutionCasesOrApplications = ContractLists.frozen(prosecutionCasesOrApplications);
        results = ContractLists.frozen(results);
    }
}
