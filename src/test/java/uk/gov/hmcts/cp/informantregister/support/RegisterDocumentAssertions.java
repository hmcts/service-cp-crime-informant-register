package uk.gov.hmcts.cp.informantregister.support;

import java.util.ArrayList;
import java.util.List;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;

/**
 * Static traversal and assertion helpers for outbound {@code add-informant-register} documents.
 * Stateless — import statically and call from any test.
 */
public final class RegisterDocumentAssertions {

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    private RegisterDocumentAssertions() {
    }

    public static Iterable<JsonNode> iterable(final JsonNode node) {
        return node.isMissingNode() || node.isNull() ? List.of() : node;
    }

    public static List<String> allResultTexts(final JsonNode document) {
        final List<String> texts = new ArrayList<>();
        for (final JsonNode session : iterable(document.path("hearingVenue").path("courtSessions"))) {
            for (final JsonNode defendant : iterable(session.path("defendants"))) {
                for (final JsonNode caseOrApp
                        : iterable(defendant.path("prosecutionCasesOrApplications"))) {
                    for (final JsonNode offence : iterable(caseOrApp.path("offences"))) {
                        for (final JsonNode result : iterable(offence.path("offenceResults"))) {
                            final String text = result.path("resultText").stringValue();
                            if (text != null) {
                                texts.add(text);
                            }
                        }
                    }
                }
            }
        }
        return texts;
    }

    public static int countDefendants(final JsonNode document) {
        int count = 0;
        for (final JsonNode session : iterable(document.path("hearingVenue").path("courtSessions"))) {
            final JsonNode defendants = session.path("defendants");
            if (!defendants.isMissingNode()) {
                count += defendants.size();
            }
        }
        return count;
    }

    public static int countOffencesWithResults(final JsonNode document) {
        int count = 0;
        for (final JsonNode session : iterable(document.path("hearingVenue").path("courtSessions"))) {
            for (final JsonNode defendant : iterable(session.path("defendants"))) {
                for (final JsonNode caseOrApp
                        : iterable(defendant.path("prosecutionCasesOrApplications"))) {
                    for (final JsonNode offence : iterable(caseOrApp.path("offences"))) {
                        final JsonNode offenceResults = offence.path("offenceResults");
                        if (!offenceResults.isMissingNode() && !offenceResults.isEmpty()) {
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    public static int countOffencesWithoutResults(final JsonNode document) {
        int count = 0;
        for (final JsonNode session : iterable(document.path("hearingVenue").path("courtSessions"))) {
            for (final JsonNode defendant : iterable(session.path("defendants"))) {
                for (final JsonNode caseOrApp
                        : iterable(defendant.path("prosecutionCasesOrApplications"))) {
                    for (final JsonNode offence : iterable(caseOrApp.path("offences"))) {
                        final JsonNode offenceResults = offence.path("offenceResults");
                        if (offenceResults.isMissingNode() || offenceResults.isEmpty()) {
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    public static List<String> allCaseReferences(final JsonNode document) {
        final List<String> refs = new ArrayList<>();
        for (final JsonNode session : iterable(document.path("hearingVenue").path("courtSessions"))) {
            for (final JsonNode defendant : iterable(session.path("defendants"))) {
                for (final JsonNode caseOrApp
                        : iterable(defendant.path("prosecutionCasesOrApplications"))) {
                    final String ref =
                            caseOrApp.path("caseOrApplicationReference").stringValue();
                    if (ref != null) {
                        refs.add(ref);
                    }
                }
            }
        }
        return refs;
    }

    public static List<String> resultTextsForCase(
            final JsonNode document, final String caseReference) {
        final List<String> texts = new ArrayList<>();
        for (final JsonNode session : iterable(document.path("hearingVenue").path("courtSessions"))) {
            for (final JsonNode defendant : iterable(session.path("defendants"))) {
                for (final JsonNode caseOrApp
                        : iterable(defendant.path("prosecutionCasesOrApplications"))) {
                    if (!caseReference.equals(
                            caseOrApp.path("caseOrApplicationReference").stringValue())) {
                        continue;
                    }
                    for (final JsonNode offence : iterable(caseOrApp.path("offences"))) {
                        for (final JsonNode result : iterable(offence.path("offenceResults"))) {
                            final String text = result.path("resultText").stringValue();
                            if (text != null) {
                                texts.add(text);
                            }
                        }
                    }
                }
            }
        }
        return texts;
    }

    public static JsonNode bodyForAuthority(
            final List<LoggedRequest> commands, final String authorityCode) {
        return commands.stream()
                .map(request -> MAPPER.readTree(request.getBodyAsString()))
                .filter(body -> authorityCode.equals(
                        body.path("prosecutionAuthorityCode").stringValue()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no outbound document for authority " + authorityCode));
    }
}
