package no.nav.helse.sykepenger.sykepengegrunnlag

import java.time.LocalDate

internal interface SykepengegrunnlagRepository {
    fun lagre(
        grunnlag: Sykepengegrunnlag,
        inntektsmeldinger: List<Inntektsmelding>,
        aOrdningsoppslag: AOrdningsoppslag?,
    )

    /** Finner grunnlaget med ID-en, også fra backfill. */
    fun finnOrNull(id: Sykepengegrunnlag.Id): Sykepengegrunnlag?

    /** Endringer bygger på det nyeste grunnlaget, også når det kommer fra backfill. */
    fun finnNyesteOrNull(
        fødselsnummer: Identitetsnummer,
        skjæringstidspunkt: LocalDate,
    ): Sykepengegrunnlag?

    /** Grunnlag fra backfill er ikke med, fordi de kommer fra Spleis og ellers ville blitt sammenlignet med seg selv. */
    fun finnNyesteUtenomBackfillOrNull(
        fødselsnummer: Identitetsnummer,
        skjæringstidspunkt: LocalDate,
    ): Sykepengegrunnlag?

    /** Alle grunnlagene for skjæringstidspunktet, også fra backfill, med det nyeste først. */
    fun finnAlle(
        fødselsnummer: Identitetsnummer,
        skjæringstidspunkt: LocalDate,
    ): List<Sykepengegrunnlag>
}
