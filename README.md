# sp-sykepengegrunnlag

Appen skal fastsette sykepengegrunnlaget. Foreløpig er den bare et skall uten domenelogikk: den kobler seg til rapiden, svarer på `ping` med `pong`, kjører Flyway-migreringer mot sin egen Postgres-database og svarer på helsesjekkene `isalive` og `isready`.

Bygg og kjør testene:

```shell
./gradlew build -x addKtlintFormatGitPreCommitHook
```

## Moduler

- `sp-sykepengegrunnlag`: selve appen.
- `migreringer`: Flyway-migreringer for databasen.

## Henvendelser

Spørsmål knyttet til koden eller prosjektet kan stilles som issues her på GitHub.

### For Nav-ansatte

Interne henvendelser kan sendes via Slack i
kanalen [#sykepenger-værsågod](https://nav-it.slack.com/archives/C019637N90X).

## Kode generert av GitHub Copilot

Dette repoet bruker GitHub Copilot til å generere kode.
