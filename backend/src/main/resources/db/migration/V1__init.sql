-- V1: Initial Fresnel schema.
--
-- Targets PostgreSQL in production; the same script also runs against H2 because
-- the default profile launches H2 in PostgreSQL compatibility mode
-- (MODE=PostgreSQL) with DATABASE_TO_LOWER for case-insensitive identifiers.

CREATE TABLE designs (
    id              UUID         PRIMARY KEY,
    kind            VARCHAR(32)  NOT NULL,
    schema_version  INTEGER      NOT NULL,
    name            VARCHAR(256),
    owner_id        VARCHAR(128),
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_designs_owner      ON designs (owner_id);
CREATE INDEX idx_designs_created_at ON designs (created_at);

CREATE TABLE render_jobs (
    id                    VARCHAR(64)  PRIMARY KEY,
    label                 VARCHAR(64)  NOT NULL,
    state                 VARCHAR(16)  NOT NULL,
    progress              DOUBLE PRECISION NOT NULL,
    message               VARCHAR(1024),
    error_message         VARCHAR(2048),
    owner_id              VARCHAR(128),
    result_png            BYTEA,
    result_pixel_size_mm  DOUBLE PRECISION,
    result_width_px       INTEGER,
    result_height_px      INTEGER,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at           TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_render_jobs_owner      ON render_jobs (owner_id);
CREATE INDEX idx_render_jobs_created_at ON render_jobs (created_at);
