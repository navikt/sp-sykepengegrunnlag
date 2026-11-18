-- Radene er testdata fra dev og mangler inntektsmeldingens egen ID, som bare kan hentes på nytt fra Spedisjon.
DELETE FROM subsumsjon;
DELETE FROM arbeidsgiverinntekt;
DELETE FROM sykepengegrunnlag;
DELETE FROM inntektsmelding;

ALTER TABLE inntektsmelding ADD COLUMN inntektsmelding_id UUID NOT NULL;

ALTER TABLE arbeidsgiverinntekt RENAME COLUMN inntektsmelding_id TO inntektsmelding_intern_dokument_id;
