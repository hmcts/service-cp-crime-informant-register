-- V1 — the processed log.
--
-- Two tables. `processed_request` is the durable memory that makes this service idempotent: one row
-- per (source, request_id), carrying the request's immutable identity, its state, the lifetime
-- attempt count and the single-runner claim. `processed_output` is created complete but stays empty
-- in CRA-220 — the skeleton pipeline produces no outputs — so the later submission stories add rows
-- rather than columns.
--
-- The database clock is the single time authority: `created_at`/`updated_at` default to now(), every
-- update sets `updated_at = now()` in SQL, and claim expiry is written as now() + lease and compared
-- against now() in SQL. No JVM clock reading is ever compared against a stored timestamp, so clock
-- skew between pods cannot grant two runners the same claim.

CREATE TABLE processed_request (
    -- Identity: written once at insert, never updated.
    source                text        NOT NULL,
    request_id            uuid        NOT NULL,
    hearing_id            uuid        NOT NULL,
    hearing_day           date        NOT NULL,
    shared_time           timestamptz NOT NULL,
    event_type            text        NOT NULL,

    -- SHA-256 hex over the canonical form of the immutable fields. The collision comparison: a
    -- delivery whose fingerprint differs from the stored one is dead-lettered and the row left
    -- untouched.
    request_fingerprint   text        NOT NULL,

    status                text        NOT NULL,

    -- Lifetime count of pipeline-run starts, successes included. Incremented in the same statement
    -- that acquires the claim, so a crashed runner can never leave a claim with no attempt recorded.
    -- Never a control variable: retry exhaustion is judged by the broker delivery count.
    attempts              integer     NOT NULL DEFAULT 0,

    completion_reason     text,

    -- Bounded reason code plus a sanitised summary. Never raw exception text, never a fragment of
    -- the message body.
    failure_reason        text,

    -- Broker messageId of the delivery that exhausted maxDeliveryCount, written in the same
    -- transaction as status = 'FAILED'. A later delivery under this same identity is re-dead-lettered
    -- without a run; a different identity is a deliberate resubmission and replays.
    exhausted_message_id  text,

    audit_note            text,

    -- The claim triple: written together, cleared together, never independently.
    claim_owner           text,
    claim_token           uuid,
    claim_expires_at      timestamptz,

    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT processed_request_pkey
        PRIMARY KEY (source, request_id),
    CONSTRAINT processed_request_status_chk
        CHECK (status IN ('RECEIVED', 'RETRYING', 'COMPLETED', 'FAILED')),
    CONSTRAINT processed_request_attempts_chk
        CHECK (attempts >= 0),
    CONSTRAINT processed_request_claim_triple_chk
        CHECK ((claim_owner IS NULL) = (claim_expires_at IS NULL)
           AND (claim_owner IS NULL) = (claim_token IS NULL)),
    -- The cheap half of the exhausted-identity rule. The other half — absent unless FAILED — is
    -- enforced by the replay statement clearing it, because a column-level constraint would have to
    -- encode the FAILED -> RECEIVED transition.
    CONSTRAINT processed_request_exhausted_id_chk
        CHECK (status <> 'FAILED' OR exhausted_message_id IS NOT NULL)
);

-- Support query: "was this hearing processed?"
CREATE INDEX processed_request_hearing_idx
    ON processed_request (hearing_id, hearing_day);

CREATE TABLE processed_output (
    -- Application-generated (UUID v4). Deliberately no database default: the row is written by code
    -- that already holds the identifier it will report.
    output_id                 uuid        NOT NULL,

    source                    text        NOT NULL,
    request_id                uuid        NOT NULL,
    prosecution_authority_id  text        NOT NULL,
    status                    text        NOT NULL,

    -- The only nullable column: SHA-256 of the outbound body, written once that body exists.
    request_digest            text,

    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT processed_output_pkey
        PRIMARY KEY (output_id),
    CONSTRAINT processed_output_status_chk
        CHECK (status IN ('PENDING', 'POSTED', 'FAILED')),
    CONSTRAINT processed_output_unique_authority
        UNIQUE (source, request_id, prosecution_authority_id),
    -- RESTRICT, not CASCADE: the processed log is append-only support evidence, and deleting a
    -- request out from under its outputs would destroy the record of what was submitted.
    CONSTRAINT processed_output_request_fk
        FOREIGN KEY (source, request_id) REFERENCES processed_request (source, request_id)
        ON DELETE RESTRICT
);
