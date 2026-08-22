package uk.gov.hmcts.cp.informantregister.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The level a judicial result was recorded at.
 *
 * <p>A direct port of the legacy {@code NowsHelper/service/LevelTypeEnum.js}, whose values are the
 * single letters below. The letters are the wire form, not an implementation detail: they appear in
 * the fragment tree the parity goldens are compared against, so they are pinned by
 * {@link JsonValue} rather than derived from the constant name.
 */
public enum ResultLevel {

    /** A result recorded against the defendant, across all of their cases. */
    DEFENDANT("D"),

    /** A result recorded against one defendant's case. */
    CASE("C"),

    /** A result recorded against one offence. */
    OFFENCE("O"),

    /** A result recorded against a court application. */
    APPLICATION("A");

    private final String code;

    ResultLevel(final String code) {
        this.code = code;
    }

    /**
     * The single-letter code this level is written as.
     *
     * @return the wire form of this level
     */
    @JsonValue
    public String code() {
        return code;
    }
}
