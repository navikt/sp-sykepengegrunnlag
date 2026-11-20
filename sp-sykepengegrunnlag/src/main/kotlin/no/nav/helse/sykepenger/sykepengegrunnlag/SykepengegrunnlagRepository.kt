package no.nav.helse.sykepenger.sykepengegrunnlag

import java.time.LocalDate

internal interface SykepengegrunnlagRepository {
    fun lagre(
        grunnlag: Sykepengegrunnlag,
        inntektsmeldinger: List<Inntektsmelding>,
        aOrdningsoppslag: AOrdningsoppslag?,
    )

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
}
