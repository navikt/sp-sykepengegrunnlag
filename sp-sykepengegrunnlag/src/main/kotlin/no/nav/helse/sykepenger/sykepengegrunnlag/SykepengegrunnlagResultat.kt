package no.nav.helse.sykepenger.sykepengegrunnlag

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** Løsningen på behovet `SykepengegrunnlagResultat`. Beløpene er avrundet til øre. */
internal data class SykepengegrunnlagResultat(
    val opphav: Opphav,
    val beregningsgrunnlag: BigDecimal,
    val sykepengegrunnlag: BigDecimal,
    val fastsatt: Fastsatt,
    val grunnbeløp: BigDecimal,
    val grunnbeløpVirkningstidspunkt: LocalDate,
    val seksG: BigDecimal,
    val arbeidsgivere: List<Arbeidsgiver>,
    val selvstendig: Selvstendig?,
) {
    /** Som `fastsatt` i `sykepengegrunnlagsfakta` fra Spleis. `IInfotrygd` kommer med backfill av grunnlag fra Infotrygd. */
    enum class Fastsatt { EtterHovedregel, EtterSkjønn }

    /** `SAKSBEHANDLER` når saksbehandler har korrigert inntekten. En skjønnsfastsatt inntekt beholder kilden, og beløpet står i `skjønnsfastsatt`. */
    enum class Resultatkilde { INNTEKTSMELDING, A_ORDNINGEN, SAKSBEHANDLER }

    data class Arbeidsgiver(
        val orgnummer: Organisasjonsnummer,
        val inntektskilde: Resultatkilde,
        val omregnetÅrsinntekt: BigDecimal,
        val skjønnsfastsatt: BigDecimal?,
        val fastsattÅrsinntekt: BigDecimal,
        val deaktivert: Boolean,
    )

    data class Selvstendig(
        val beregningsgrunnlag: BigDecimal,
        val fastsattÅrsinntekt: BigDecimal,
        val pensjonsgivendeInntekter: List<PensjonsgivendeInntekt>,
    )

    data class PensjonsgivendeInntekt(
        val år: Int,
        val beløp: BigDecimal,
    )

    companion object {
        fun fra(grunnlag: Sykepengegrunnlag) =
            SykepengegrunnlagResultat(
                opphav = grunnlag.opphav,
                beregningsgrunnlag = beløp(grunnlag.beregningsgrunnlag),
                sykepengegrunnlag = beløp(grunnlag.sykepengegrunnlag),
                fastsatt = if (grunnlag.erSkjønnsfastsatt) Fastsatt.EtterSkjønn else Fastsatt.EtterHovedregel,
                grunnbeløp = beløp(grunnlag.grunnbeløp),
                grunnbeløpVirkningstidspunkt = Grunnbeløp.virkningstidspunktFor(grunnlag.grunnbeløp),
                seksG = beløp(grunnlag.seksG),
                arbeidsgivere =
                    grunnlag.arbeidsgivere.map {
                        Arbeidsgiver(
                            orgnummer = it.orgnummer,
                            inntektskilde =
                                when {
                                    it.overstyrtInntekt != null -> Resultatkilde.SAKSBEHANDLER
                                    it.inntektskilde == Inntektskilde.INNTEKTSMELDING -> Resultatkilde.INNTEKTSMELDING
                                    else -> Resultatkilde.A_ORDNINGEN
                                },
                            omregnetÅrsinntekt = beløp(it.overstyrtEllerOmregnetÅrsinntekt),
                            skjønnsfastsatt = it.skjønnsfastsatt?.let { skjønnsfastsatt -> beløp(skjønnsfastsatt.årsinntekt) },
                            fastsattÅrsinntekt = beløp(it.fastsattÅrsinntekt),
                            deaktivert = it.deaktivert,
                        )
                    },
                selvstendig =
                    grunnlag.selvstendig?.let {
                        Selvstendig(
                            beregningsgrunnlag = beløp(it.beregningsgrunnlag),
                            fastsattÅrsinntekt = beløp(it.fastsattÅrsinntekt),
                            pensjonsgivendeInntekter = it.årsinntekter.map { årsinntekt -> PensjonsgivendeInntekt(årsinntekt.år, beløp(årsinntekt.beløp.toBigDecimal())) },
                        )
                    },
            )

        private fun beløp(verdi: BigDecimal) = verdi.setScale(2, RoundingMode.HALF_UP)
    }

    fun tilLøsning(): Map<String, Any?> =
        mapOf(
            "opphav" to opphav.iMeldinger,
            "beregningsgrunnlag" to beregningsgrunnlag,
            "sykepengegrunnlag" to sykepengegrunnlag,
            "fastsatt" to fastsatt.name,
            "grunnbeløp" to grunnbeløp,
            "grunnbeløpVirkningstidspunkt" to grunnbeløpVirkningstidspunkt,
            "seksG" to seksG,
            "arbeidsgivere" to
                arbeidsgivere.map {
                    mapOf(
                        "orgnummer" to it.orgnummer.verdi,
                        "inntektskilde" to it.inntektskilde.name,
                        "omregnetÅrsinntekt" to it.omregnetÅrsinntekt,
                        "skjønnsfastsatt" to it.skjønnsfastsatt,
                        "fastsattÅrsinntekt" to it.fastsattÅrsinntekt,
                        "deaktivert" to it.deaktivert,
                    )
                },
            "selvstendig" to
                selvstendig?.let {
                    mapOf(
                        "beregningsgrunnlag" to it.beregningsgrunnlag,
                        "fastsattÅrsinntekt" to it.fastsattÅrsinntekt,
                        "pensjonsgivendeInntekter" to it.pensjonsgivendeInntekter.map { inntekt -> mapOf("år" to inntekt.år, "beløp" to inntekt.beløp) },
                    )
                },
        )
}
