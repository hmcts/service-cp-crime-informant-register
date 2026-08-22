package uk.gov.hmcts.cp.informantregister.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One prosecution case or court application a defendant appeared on.
 *
 * <p>The contract models cases and applications as a single node rather than as two, which is why
 * {@code applicationParticulars} and {@code arrestSummonsNumber} sit side by side: whichever of the
 * two a given entry is, only one of them is filled and the other is simply absent from the wire.
 *
 * @param caseOrApplicationReference the case or application reference; required by the schema
 * @param arrestSummonsNumber        the ASN, where the entry is a prosecution case
 * @param applicationParticulars     the particulars, where the entry is a court application
 * @param offences                   the offences on this case; required by the schema
 * @param results                    results recorded against the case as a whole
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InformantRegisterCaseOrApplication(
        String caseOrApplicationReference,
        String arrestSummonsNumber,
        String applicationParticulars,
        List<InformantRegisterOffence> offences,
        List<InformantRegisterResult> results) {

    /**
     * Freezes both lists so the entry cannot be changed after it is built.
     */
    public InformantRegisterCaseOrApplication {
        offences = ContractLists.frozen(offences);
        results = ContractLists.frozen(results);
    }
}
