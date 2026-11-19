ALTER TABLE arbeidsgiverinntekt ADD COLUMN a_ordningen_hentet TIMESTAMPTZ;

CREATE TABLE skatteopplysning
(
    løpenummer           BIGSERIAL PRIMARY KEY,
    sykepengegrunnlag_id UUID           NOT NULL,
    orgnummer            VARCHAR(9)     NOT NULL,
    måned                DATE           NOT NULL,
    beløp                NUMERIC(19, 2) NOT NULL,
    type                 TEXT           NOT NULL,
    fordel               TEXT           NOT NULL,
    beskrivelse          TEXT           NOT NULL,
    FOREIGN KEY (sykepengegrunnlag_id, orgnummer) REFERENCES arbeidsgiverinntekt (sykepengegrunnlag_id, orgnummer)
);

CREATE INDEX idx_skatteopplysning_sykepengegrunnlag_id ON skatteopplysning (sykepengegrunnlag_id);
