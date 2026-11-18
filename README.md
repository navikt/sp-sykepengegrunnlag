# sp-sykepengegrunnlag

Appen fastsetter sykepengegrunnlaget. Den svarer på behovet `Sykepengegrunnlag` fra Spleis med en sykepengegrunnlag-ID. Foreløpig svarer den bare når alle arbeidsgiverne har inntektskilden `INNTEKTSMELDING`.

Appen henter inntektsmeldingene fra Spedisjon og slår opp rettighetskategorien i sp-rettighetskategori. Reglene for beregningsgrunnlaget, 6G-begrensningen og grunnbeløpet er kopiert fra Spleis. Grunnlaget, opplysningene fra inntektsmeldingene og subsumsjonene lagres i appens egen Postgres-database. Appen lagrer ikke kopier av inntektsmeldingene.

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
