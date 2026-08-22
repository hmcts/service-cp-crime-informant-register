package uk.gov.hmcts.cp.informantregister.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;

/**
 * The results-owned {@code add-informant-register} schema tree, loaded from committed copies.
 *
 * <p><strong>Provenance.</strong> Every file under {@code src/test/resources/contracts/results/} is a
 * byte-identical copy, taken on 2026-08-22 and verified with {@code cmp}:
 *
 * <ul>
 *   <li>{@code informantRegisterDocumentRequest.json}, {@code informantRegisterHearingVenue.json},
 *       {@code informantRegisterHearing.json}, {@code informantRegisterDefendant.json},
 *       {@code informantRegisterCaseOrApplication.json}, {@code informantRegisterOffence.json},
 *       {@code informantRegisterResult.json}, {@code informantRegisterResultData.json},
 *       {@code verdict.json} and {@code informantRegisterRecipient.json} — from
 *       {@code cpp-context-results} at commit {@code 0f13730aa}, path
 *       {@code results-json/src/main/resources/json/schema/informantRegisterDocument/}.</li>
 *   <li>{@code courtsDefinitions.json} — from {@code cpp-platform-core-domain} at commit
 *       {@code 4f081ac7}, path
 *       {@code criminal-court-public-model/src/main/resources/json/schema/global/}. It is here only
 *       because the document tree {@code $ref}s {@code #/definitions/uuid} out of it.</li>
 * </ul>
 *
 * <p>The results-local copy is the authoritative one, not the platform copy of the same document.
 * The command API's schema stub {@code results.add-informant-register.json} refs the
 * {@code justice.gov.uk/results/courts/...} namespace, and that namespace resolves to
 * {@code results-json}. The two copies differ only in their {@code id}, their {@code $ref}
 * namespaces and their description wording — the field tree is identical — so the distinction
 * matters for provenance rather than for behaviour, and is recorded so nobody later reconciles
 * against the platform copy and concludes the contract moved.
 *
 * <p>The tree is draft-04 and its {@code $ref}s are absolute {@code http://justice.gov.uk/…} IRIs.
 * Nothing is fetched: each IRI is mapped to its committed copy, so a validator that ever tried to
 * reach the network would fail to resolve rather than quietly validate against whatever was served.
 *
 * <p>Formats are assertions here rather than annotations. {@code format: date-time} on the two
 * timestamps is the only thing standing between a schema-valid body and a pair of epoch numbers, so
 * a suite that left formats unchecked would prove nothing about the wire form.
 */
public final class ResultsCommandSchemas {

    /** The IRI of the root schema — the body of one {@code add-informant-register} command. */
    public static final String ADD_INFORMANT_REGISTER_SCHEMA_IRI =
            "http://justice.gov.uk/results/courts/informantRegisterDocument/informantRegisterDocumentRequest.json";

    /**
     * Every schema file that makes up the document tree, in the order the tree nests them.
     *
     * <p>{@code courtsDefinitions.json} is deliberately absent: it is a shared definitions document
     * rather than a node of this contract, and the assertions that hold this service's records to
     * the tree must not be handed a file that has no record to match.
     */
    public static final List<String> DOCUMENT_SCHEMA_FILES = List.of(
            "informantRegisterDocumentRequest.json",
            "informantRegisterHearingVenue.json",
            "informantRegisterHearing.json",
            "informantRegisterDefendant.json",
            "informantRegisterCaseOrApplication.json",
            "informantRegisterOffence.json",
            "informantRegisterResult.json",
            "informantRegisterResultData.json",
            "verdict.json",
            "informantRegisterRecipient.json");

    private static final String RESOURCE_ROOT = "contracts/results/";
    private static final String RESULTS_NAMESPACE =
            "http://justice.gov.uk/results/courts/informantRegisterDocument/";
    private static final String CORE_DEFINITIONS_IRI = "http://justice.gov.uk/core/courts/courtsDefinitions.json";
    private static final String CORE_DEFINITIONS_FILE = "courtsDefinitions.json";

    private static final Map<String, String> SCHEMA_TEXT_BY_IRI = schemaTextByIri();
    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();
    private static final Schema ADD_INFORMANT_REGISTER_SCHEMA = buildSchema();

    private ResultsCommandSchemas() {
    }

    /**
     * Returns the compiled root schema for the {@code add-informant-register} body.
     *
     * @return the compiled draft-04 schema, with format assertions enabled
     */
    public static Schema addInformantRegisterSchema() {
        return ADD_INFORMANT_REGISTER_SCHEMA;
    }

    /**
     * Returns one schema document as a tree, so assertions can be taken from the contract itself
     * rather than restated in Java.
     *
     * @param fileName one of {@link #DOCUMENT_SCHEMA_FILES}
     * @return the parsed schema document
     */
    public static JsonNode schemaDocument(final String fileName) {
        final String text = SCHEMA_TEXT_BY_IRI.get(RESULTS_NAMESPACE + fileName);
        if (text == null) {
            throw new IllegalArgumentException("Not a document schema of this contract: " + fileName);
        }
        return MAPPER.readTree(text);
    }

    private static Map<String, String> schemaTextByIri() {
        final Map<String, String> byIri = new LinkedHashMap<>();
        DOCUMENT_SCHEMA_FILES.forEach(file -> byIri.put(RESULTS_NAMESPACE + file, read(file)));
        byIri.put(CORE_DEFINITIONS_IRI, read(CORE_DEFINITIONS_FILE));
        return Map.copyOf(byIri);
    }

    private static String read(final String fileName) {
        final String resource = RESOURCE_ROOT + fileName;
        try (InputStream stream = ResultsCommandSchemas.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Committed contract schema not found: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Committed contract schema could not be read: " + resource, e);
        }
    }

    private static Schema buildSchema() {
        final SchemaRegistryConfig config = SchemaRegistryConfig.builder()
                .formatAssertionsEnabled(Boolean.TRUE)
                .build();
        final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_4,
                builder -> builder
                        .schemaRegistryConfig(config)
                        .schemas(SCHEMA_TEXT_BY_IRI));
        return registry.getSchema(SchemaLocation.of(ADD_INFORMANT_REGISTER_SCHEMA_IRI));
    }
}
