package uk.gov.hmcts.cp.informantregister.domain;

/**
 * The bounded set of reasons a message body can fail contract validation.
 *
 * <p>One member per failure class, and deliberately no member that could carry free text. What
 * reaches a dead-letter reason, a log field or the processed log is one of these names plus a
 * sanitised summary the service composes itself — never a raw parser message and never a fragment of
 * the body, both of which are producer-influenced content that would leak into the DLQ and the log
 * index.
 */
public enum ContractViolation {

    /** The body is not well-formed JSON. */
    MALFORMED_JSON,

    /** The body is well-formed JSON but not a JSON object. */
    NOT_AN_OBJECT,

    /** A required field is absent, null, or an empty string. */
    MISSING_FIELD,

    /** The body carries a field the closed contract does not declare. */
    UNKNOWN_FIELD,

    /** A field's value falls outside the agreed enumeration. */
    INVALID_ENUM_VALUE,

    /** A field is present but not in its agreed format, or not of its agreed JSON type. */
    INVALID_FORMAT
}
