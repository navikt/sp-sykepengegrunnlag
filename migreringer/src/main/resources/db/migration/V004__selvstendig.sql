CREATE TABLE soknad
(
    intern_dokument_id UUID PRIMARY KEY,
    søknad_id          UUID        NOT NULL,
    fødselsnummer      VARCHAR(11) NOT NULL
);

CREATE TABLE pensjonsgivende_inntekt
(
    søknad_intern_dokument_id                              UUID           NOT NULL REFERENCES soknad (intern_dokument_id),
    år                                                     INT            NOT NULL,
    er_ferdig_lignet                                       BOOLEAN        NOT NULL,
    lønnsinntekt                                           NUMERIC(19, 2) NOT NULL,
    lønnsinntekt_bare_pensjonsdel                          NUMERIC(19, 2) NOT NULL,
    næringsinntekt                                         NUMERIC(19, 2) NOT NULL,
    næringsinntekt_fra_fiske_fangst_eller_familiebarnehage NUMERIC(19, 2) NOT NULL,
    PRIMARY KEY (søknad_intern_dokument_id, år)
);

CREATE TABLE selvstendiginntekt
(
    sykepengegrunnlag_id      UUID PRIMARY KEY REFERENCES sykepengegrunnlag (id),
    søknad_intern_dokument_id UUID           NOT NULL REFERENCES soknad (intern_dokument_id),
    søknad_hentet             TIMESTAMPTZ    NOT NULL,
    fastsatt_årsinntekt       NUMERIC(19, 2) NOT NULL
);
