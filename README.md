# sp-sykepengegrunnlag

Appen fastsetter sykepengegrunnlaget. Den svarer på behovet `Sykepengegrunnlag` fra Spleis med en sykepengegrunnlag-ID. Den fastsetter grunnlaget for arbeidstakere fra inntektsmeldinger og a-ordningen, og for selvstendig næringsdrivende fra søknaden.

Appen henter inntektsmeldingene og søknadene fra Spedisjon, inntektene fra a-ordningen fra inntektskomponenten og slår opp rettighetskategorien i sp-rettighetskategori. Reglene for beregningsgrunnlaget, omregnet årsinntekt etter § 8-28, 6G-begrensningen, § 8-35 for selvstendig næringsdrivende og grunnbeløpet er kopiert fra Spleis. Grunnlaget, opplysningene fra inntektsmeldingene, den pensjonsgivende inntekten fra søknadene, hele svaret fra a-ordningen og lovreferansene lagres i appens egen Postgres-database. Appen lagrer ikke kopier av inntektsmeldingene eller søknadene.

Mens appen er i skyggedrift, sammenligner den det nyeste grunnlaget for skjæringstidspunktet med `sykepengegrunnlagsfakta` i godkjenningsbehovet fra Spleis. Grunnlag fra backfill er ikke med. Utfallet `lik`, `forskjell` eller `mangler` logges og telles i metrikken `sykepengegrunnlag_sammenligning_i_skyggedrift`. Appen svarer ikke på godkjenningsbehovet.

Appen tar også imot skjønnsfastsettelser fra Spesialist. Da lager den et nytt grunnlag og sier fra til Spleis om at grunnlaget er endret.

Speil kan hente alle grunnlagene for et skjæringstidspunkt gjennom et REST-API, og viser dem i utviklerfanen. Kallet krever saksbehandlerens Azure AD-token med lesetilgang, og appen kontrollerer tilgangen til personen i tilgangsmaskinen. OpenAPI-specen ligger på `/api/openapi.json`, og Swagger UI på `/api/swagger`.

For å kjøre API-et lokalt for Speil kjører du `main` i `LocalApp.kt` (under `src/test`). Appen starter på port 8686 med eksempeldata i minnet.

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
