package uk.gov.hmcts.cp.informantregister.inbound;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.domain.ContractValidationException;
import uk.gov.hmcts.cp.informantregister.domain.ContractViolation;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;

/**
 * Turns a raw message body into a validated {@link DistributionCommand}, or refuses it with a
 * bounded reason.
 *
 * <p>Validation is explicit rather than schema-driven at runtime: the committed draft-07 schema is
 * the contract's source of truth, and the contract tests hold this parser to it case for case. That
 * keeps one validation implementation on the hot path and lets a rejection say precisely what was
 * wrong, which is what a dead-letter description is for.
 *
 * <p>Every failure is a {@link ContractValidationException} carrying a {@link ContractViolation} and,
 * where the failure is attributable to one field, that field's name. No rejection ever carries a
 * value from the body.
 */
public class DistributionCommandParser {

    private static final String SOURCE = "source";
    private static final String REQUEST_ID = "requestId";
    private static final String HEARING_ID = "hearingId";
    private static final String HEARING_DAY = "hearingDay";
    private static final String SHARED_TIME = "sharedTime";
    private static final String EVENT_TYPE = "eventType";

    /** The closed contract, in the order the schema declares it. */
    private static final List<String> DECLARED_FIELDS =
            List.of(SOURCE, REQUEST_ID, HEARING_ID, HEARING_DAY, SHARED_TIME, EVENT_TYPE);

    private static final Set<String> PERMITTED_SOURCES = Set.of("RESULTS");
    private static final Set<String> PERMITTED_EVENT_TYPES = Set.of("Hearing_Resulted");

    /**
     * The canonical RFC 4122 layout the schema's {@code uuid} format requires. Deliberately stricter
     * than {@link UUID#fromString}, which also accepts abbreviated groups.
     */
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /**
     * Bounds what an unknown field's name may look like before it is reported. The name comes from
     * the producer, and it travels onward into a dead-letter description and a log index.
     */
    private static final Pattern REPORTABLE_FIELD_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");

    private static final String UNPRINTABLE_FIELD_NAME = "<unprintable>";

    /**
     * RFC 3339 {@code full-date}, which is what draft-07's {@code date} format means.
     *
     * <p>Checked before {@link LocalDate#parse}, which is looser in two ways this contract cannot
     * afford: it accepts a signed, expanded year ({@code +12026-08-20}, {@code -0001-08-20}) and it
     * would accept an unpadded month were the formatter any more lenient.
     */
    private static final Pattern RFC3339_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /**
     * RFC 3339 {@code date-time}, which is what draft-07's {@code date-time} format means.
     *
     * <p>Checked before {@link OffsetDateTime#parse}, which accepts four shapes RFC 3339 does not:
     * an offset carrying seconds ({@code +01:00:30}), an omitted seconds field
     * ({@code 2026-08-20T09:00Z}), a signed or expanded year, and a fractional part with no digits
     * after the point. The lower-case {@code t} and {@code z} forms are deliberately permitted —
     * RFC 3339 allows them and the schema accepts them, so rejecting them here would trade one
     * divergence for another.
     *
     * <p>{@code -00:00} is excluded explicitly: RFC 3339 gives it the distinct meaning "offset
     * unknown", and the schema refuses it.
     */
    private static final Pattern RFC3339_DATE_TIME = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([Zz]|\\+\\d{2}:\\d{2}|-(?!00:00)\\d{2}:\\d{2})$");

    private final ObjectMapper objectMapper;

    public DistributionCommandParser(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Validates and converts a message body.
     *
     * @param body the raw message body
     * @return the validated command
     * @throws ContractValidationException if the body does not satisfy the inbound contract
     */
    public DistributionCommand parse(final String body) {
        final JsonNode root = readTree(body);
        if (!root.isObject()) {
            throw new ContractValidationException(ContractViolation.NOT_AN_OBJECT, null);
        }
        rejectUnknownFields(root);

        return new DistributionCommand(
                enumeratedValue(root, SOURCE, PERMITTED_SOURCES),
                canonicalUuid(root, REQUEST_ID),
                canonicalUuid(root, HEARING_ID),
                isoDate(root, HEARING_DAY),
                isoInstant(root, SHARED_TIME),
                enumeratedValue(root, EVENT_TYPE, PERMITTED_EVENT_TYPES));
    }

    private JsonNode readTree(final String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JacksonException malformed) {
            // Translated, not wrapped. The library's message is written to help a developer and may
            // quote the bytes it choked on; this exception travels into a dead-letter description
            // and a log index, so it carries the bounded reason and nothing else.
            throw new ContractValidationException(ContractViolation.MALFORMED_JSON, null);
        }
    }

    private void rejectUnknownFields(final JsonNode root) {
        for (final String property : root.propertyNames()) {
            if (!DECLARED_FIELDS.contains(property)) {
                throw new ContractValidationException(ContractViolation.UNKNOWN_FIELD, reportable(property));
            }
        }
    }

    private static String reportable(final String fieldName) {
        return REPORTABLE_FIELD_NAME.matcher(fieldName).matches() ? fieldName : UNPRINTABLE_FIELD_NAME;
    }

    /**
     * Reads a required field as a non-blank JSON string.
     *
     * <p>Absent, null and empty are one failure class — a field with nothing in it is a field the
     * producer did not supply — while a value of the wrong JSON type is a format failure.
     */
    private static String requiredText(final JsonNode root, final String field) {
        final JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            throw new ContractValidationException(ContractViolation.MISSING_FIELD, field);
        }
        if (!value.isString()) {
            throw new ContractValidationException(ContractViolation.INVALID_FORMAT, field);
        }
        final String text = value.stringValue();
        if (text.isBlank()) {
            throw new ContractValidationException(ContractViolation.MISSING_FIELD, field);
        }
        return text;
    }

    private static String enumeratedValue(final JsonNode root,
                                          final String field,
                                          final Set<String> permitted) {
        final String text = requiredText(root, field);
        if (!permitted.contains(text)) {
            throw new ContractValidationException(ContractViolation.INVALID_ENUM_VALUE, field);
        }
        return text;
    }

    private static UUID canonicalUuid(final JsonNode root, final String field) {
        final String text = requiredText(root, field);
        if (!CANONICAL_UUID.matcher(text).matches()) {
            throw new ContractValidationException(ContractViolation.INVALID_FORMAT, field);
        }
        return UUID.fromString(text);
    }

    private static LocalDate isoDate(final JsonNode root, final String field) {
        final String text = requiredText(root, field);
        requireLexicalShape(RFC3339_DATE, text, field);
        try {
            // Lexically an RFC 3339 date by now, so this parse is purely semantic: ISO_LOCAL_DATE
            // resolves strictly, making a well-formed but non-existent day — 30 February, 29
            // February in a common year — a rejection rather than a silent shift.
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException notADate) {
            throw new ContractValidationException(ContractViolation.INVALID_FORMAT, field);
        }
    }

    private static Instant isoInstant(final JsonNode root, final String field) {
        final String text = requiredText(root, field);
        requireLexicalShape(RFC3339_DATE_TIME, text, field);
        try {
            // Lexically an RFC 3339 date-time by now, so this parse is purely semantic: it rejects
            // an impossible hour or more precision than a nanosecond, and normalises to UTC so that
            // 09:00Z and 10:00+01:00 become the same instant, and therefore the same fingerprint.
            return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException notAnInstant) {
            throw new ContractValidationException(ContractViolation.INVALID_FORMAT, field);
        }
    }

    /**
     * Rejects a value whose shape is outside the contract's grammar, before any parser gets to be
     * generous about it.
     */
    private static void requireLexicalShape(final Pattern grammar, final String text, final String field) {
        if (!grammar.matcher(text).matches()) {
            throw new ContractValidationException(ContractViolation.INVALID_FORMAT, field);
        }
    }
}
