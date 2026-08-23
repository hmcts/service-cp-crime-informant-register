package uk.gov.hmcts.cp.informantregister.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;

/**
 * One case of the informant-register parity pack, loaded from the classpath.
 *
 * <p>The pack lives at {@code src/test/resources/parity/} and is a byte-identical copy of
 * {@code analysis/results-distribution/InformantRegister/parity-pack/}. Every case is a recording of
 * the <em>real</em> Node function app running
 * {@code SetInformantRegister -> InformantRegisterSubscriptions -> OutboundInformantRegister} over a
 * hearing payload, so {@code expected.json} is the outbound {@code add-informant-register} document
 * array as {@code ProcessOutboundInformantRegister} received it
 * ({@code InformantRegisterOrchestrator/index.js:47}).
 *
 * <p>Everything a test needs to reproduce the run is here, and the clock pin is part of it: the
 * transformation reads "now" ({@code DateService.js:37}) and that value reaches {@code registerDate},
 * {@code hearingDate} and {@code fileName}, so a case replayed against a live clock is not the case
 * that was recorded.
 *
 * @param caseId        the case's directory name
 * @param hearing       the hearing payload handed to {@code SetInformantRegister}
 * @param subscriptions the now-subscriptions body reference data answered with; {@code null} when the
 *                      case makes the reference-data call fail
 * @param sharedTime    the shared time, exactly as the recording supplied it; may be {@code null}
 * @param clockPin      the instant the oracle pinned the wall clock to
 * @param refdata       what the reference-data call is made to do
 * @param expected      the recorded document array, or a JSON null when the chain produced none
 * @param meta          the case's {@code meta.json}
 */
public record ParityCase(
        String caseId,
        JsonNode hearing,
        JsonNode subscriptions,
        String sharedTime,
        Instant clockPin,
        RefdataAnswer refdata,
        JsonNode expected,
        JsonNode meta) {

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /** What the reference-data now-subscriptions call is made to do for a case. */
    public enum RefdataAnswer {

        /** It answers, with the case's {@code inputs/subscriptions.json}. */
        RESOLVE,

        /** Every attempt fails; the legacy swallows that and carries on with no subscriptions. */
        REJECT,

        /** It fails and then succeeds, so the retry loop recovers and the body is answered. */
        SEQUENCE_RECOVERS
    }

    /**
     * The case ids of the {@code recorded/} corpus, in the order {@code index.json} lists them.
     *
     * @param corpus the corpus directory under {@code /parity/}
     * @return the case ids
     */
    public static List<String> caseIds(final String corpus) {
        final JsonNode index = read("/parity/" + corpus + "/index.json");
        final List<String> ids = new ArrayList<>();
        index.forEach(row -> ids.add(row.get("caseId").stringValue()));
        return ids;
    }

    /**
     * The {@code index.json} rows of a corpus, keyed by case id order.
     *
     * @param corpus the corpus directory under {@code /parity/}
     * @return the index
     */
    public static JsonNode index(final String corpus) {
        return read("/parity/" + corpus + "/index.json");
    }

    /**
     * Loads one case.
     *
     * @param corpus the corpus directory under {@code /parity/} — {@code recorded} or {@code pinning}
     * @param caseId the case's directory name
     * @return the case
     */
    public static ParityCase load(final String corpus, final String caseId) {
        final String root = "/parity/" + corpus + "/" + caseId;
        final JsonNode params = read(root + "/inputs/params.json");
        final JsonNode subscriptions = readOptional(root + "/inputs/subscriptions.json");
        return new ParityCase(
                caseId,
                read(root + "/inputs/hearing.json"),
                subscriptions,
                text(params, "sharedTime"),
                Instant.parse(text(params, "clockPinIso")),
                refdataAnswer(params),
                read(root + "/expected.json"),
                read(root + "/meta.json"));
    }

    /**
     * What the oracle observed — {@code documents}, {@code no-fragments} or
     * {@code swallowed-exception}.
     *
     * @return the recorded outcome
     */
    public String oracleOutcome() {
        return meta.get("observed").get("outcome").stringValue();
    }

    /**
     * Whether the input satisfies the published schema, and so whether golden equality is owed.
     *
     * @return {@code IN_CONTRACT}, {@code SCHEMA_INVALID} or {@code PRODUCER_IMPLAUSIBLE}
     */
    public String contractStatus() {
        return meta.get("contract").get("status").stringValue();
    }

    /**
     * The documents a second delivery of the same hearing produced, for the re-share cases.
     *
     * @return the second delivery's document array, or {@code null} when the case has one delivery
     */
    public JsonNode expectedSecondDelivery() {
        return readOptional("/parity/recorded/" + caseId + "/expected-second-delivery.json");
    }

    /**
     * The {@code on} query parameter the recording shows reference data being asked for.
     *
     * @return the query date, or {@code null} when the case made no reference-data call
     */
    public String recordedRefdataQueryDate() {
        final JsonNode calls = meta.get("observed").get("refdataCalls");
        return calls == null || calls.isEmpty() ? null : calls.get(0).get("on").stringValue();
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return caseId;
    }

    private static RefdataAnswer refdataAnswer(final JsonNode params) {
        final JsonNode refdata = params.get("refdata");
        if (refdata == null || refdata.isNull()) {
            return RefdataAnswer.RESOLVE;
        }
        return "sequence".equals(text(refdata, "kind"))
                ? RefdataAnswer.SEQUENCE_RECOVERS
                : RefdataAnswer.REJECT;
    }

    private static String text(final JsonNode node, final String field) {
        final JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.stringValue();
    }

    private static JsonNode read(final String resource) {
        final JsonNode node = readOptional(resource);
        if (node == null) {
            throw new IllegalStateException("missing parity resource " + resource);
        }
        return node;
    }

    private static JsonNode readOptional(final String resource) {
        try (InputStream stream = ParityCase.class.getResourceAsStream(resource)) {
            if (stream == null) {
                return null;
            }
            return MAPPER.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException cannotRead) {
            throw new IllegalStateException("could not read " + resource, cannotRead);
        }
    }
}
