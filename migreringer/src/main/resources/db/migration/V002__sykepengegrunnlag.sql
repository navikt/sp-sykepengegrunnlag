CREATE TABLE inntektsmelding
(
    intern_dokument_id UUID PRIMARY KEY,
    fødselsnummer      VARCHAR(11)    NOT NULL,
    orgnummer          VARCHAR(9)     NOT NULL,
    beregnet_inntekt   NUMERIC(19, 2) NOT NULL,
    mottatt_dato       TIMESTAMPTZ    NOT NULL
);

CREATE TABLE sykepengegrunnlag
(
    id                    UUID PRIMARY KEY,
    fødselsnummer         VARCHAR(11)    NOT NULL,
    skjæringstidspunkt    DATE           NOT NULL,
    rettighetskategori_id UUID           NOT NULL,
    opphav                TEXT           NOT NULL,
    beregningsgrunnlag    NUMERIC(19, 2) NOT NULL,
    sykepengegrunnlag     NUMERIC(19, 2) NOT NULL,
    grunnbeløp            NUMERIC(19, 2) NOT NULL,
    opprettet             TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE arbeidsgiverinntekt
(
    løpenummer             BIGSERIAL PRIMARY KEY,
    sykepengegrunnlag_id   UUID           NOT NULL REFERENCES sykepengegrunnlag (id),
    orgnummer              VARCHAR(9)     NOT NULL,
    inntektskilde          TEXT           NOT NULL,
    inntektsmelding_id     UUID REFERENCES inntektsmelding (intern_dokument_id),
    inntektsmelding_hentet TIMESTAMPTZ,
    omregnet_årsinntekt    NUMERIC(19, 2) NOT NULL,
    UNIQUE (sykepengegrunnlag_id, orgnummer)
);

CREATE TABLE subsumsjon
(
    løpenummer           BIGSERIAL PRIMARY KEY,
    sykepengegrunnlag_id UUID  NOT NULL REFERENCES sykepengegrunnlag (id),
    lovverk              TEXT  NOT NULL,
    versjon              DATE  NOT NULL,
    paragraf             TEXT  NOT NULL,
    ledd                 INT,
    punktum              INT,
    bokstav              TEXT,
    utfall               TEXT  NOT NULL,
    input                JSONB NOT NULL,
    output               JSONB NOT NULL
);

CREATE INDEX idx_subsumsjon_sykepengegrunnlag_id ON subsumsjon (sykepengegrunnlag_id);
