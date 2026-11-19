# sp-sykepengegrunnlag

Appen fastsetter sykepengegrunnlaget. Den svarer på behovet `Sykepengegrunnlag` fra Spleis med en sykepengegrunnlag-ID. Den fastsetter grunnlaget for arbeidstakere fra inntektsmeldinger og a-ordningen, og for selvstendig næringsdrivende fra søknaden.

Appen henter inntektsmeldingene og søknadene fra Spedisjon, inntektene fra a-ordningen fra inntektskomponenten og slår opp rettighetskategorien i sp-rettighetskategori. Reglene for beregningsgrunnlaget, omregnet årsinntekt etter § 8-28, 6G-begrensningen, § 8-35 for selvstendig næringsdrivende og grunnbeløpet er kopiert fra Spleis. Grunnlaget, opplysningene fra inntektsmeldingene, den pensjonsgivende inntekten fra søknadene, de månedlige inntektene fra a-ordningen og subsumsjonene lagres i appens egen Postgres-database. Appen lagrer ikke kopier av inntektsmeldingene eller søknadene.

Bygg og kjør testene:

```shell
./gradlew build -x addKtlintFormatGitPreCommitHook
```

## Moduler

- `sp-sykepengegrunnlag`: selve appen.
- `migreringer`: Flyway-migreringer for databasen.
- `opprydding-dev`: sletter persondata i dev når det kommer `slett_person` på rapiden, og svarer med `person_slettet`.

## Henvendelser

Spørsmål knyttet til koden eller prosjektet kan stilles som issues her på GitHub.

### For Nav-ansatte

Interne henvendelser kan sendes via Slack i
kanalen [#sykepenger-værsågod](https://nav-it.slack.com/archives/C019637N90X).

## Kode generert av GitHub Copilot

Dette repoet bruker GitHub Copilot til å generere kode.
