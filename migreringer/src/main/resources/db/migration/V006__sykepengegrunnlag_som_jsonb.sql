-- Et sykepengegrunnlag endres aldri etter at det er lagret, og leses og skrives i sin helhet. Derfor lagres alt annet enn
-- nøkkelkolonnene som jsonb i én kolonne. Kildene har egne tabeller: inntektsmeldingen og søknaden som før, og hele svaret
-- fra a-ordningen i a_ordningsoppslag (ADR 0021).
-- Dataene i dev og prod kan slettes, så de flyttes ikke over.
DELETE FROM subsumsjon;
DELETE FROM skatteopplysning;
DELETE FROM selvstendiginntekt;
DELETE FROM arbeidsgiverinntekt;
DELETE FROM sykepengegrunnlag;
DELETE FROM inntektsmelding;
DELETE FROM pensjonsgivende_inntekt;
DELETE FROM soknad;

DROP TABLE subsumsjon;
DROP TABLE skatteopplysning;
DROP TABLE selvstendiginntekt;
DROP TABLE arbeidsgiverinntekt;

ALTER TABLE sykepengegrunnlag
    DROP COLUMN beregningsgrunnlag,
    DROP COLUMN sykepengegrunnlag,
    DROP COLUMN grunnbeløp,
    ADD COLUMN data JSONB NOT NULL;

CREATE TABLE a_ordningsoppslag
(
    id              UUID PRIMARY KEY,
    fødselsnummer   VARCHAR(11) NOT NULL,
    første_måned    DATE        NOT NULL,
    siste_måned     DATE        NOT NULL,
    ainntektsfilter TEXT        NOT NULL,
    hentet          TIMESTAMPTZ NOT NULL,
    svar            JSONB       NOT NULL,
    opprettet       TIMESTAMPTZ NOT NULL DEFAULT now()
);
