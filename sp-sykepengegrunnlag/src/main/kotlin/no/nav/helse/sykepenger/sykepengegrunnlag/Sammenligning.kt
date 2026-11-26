package no.nav.helse.sykepenger.sykepengegrunnlag

import java.math.BigDecimal
import java.math.RoundingMode

/** Feltene i `sykepengegrunnlagsfakta` i godkjenningsbehovet fra Spleis som sammenlignes i skyggedrift. */
internal data class Sykepengegrunnlagsfakta(
    val sykepengegrunnlag: BigDecimal,
    val seksG: BigDecimal,
    val fastsatt: String,
    val arbeidsgivere: List<Arbeidsgiver>,
    val pensjonsgivendeInntekter: List<PensjonsgivendeInntekt>,
) {
    data class Arbeidsgiver(
        val orgnummer: Organisasjonsnummer,
        val omregnetÅrsinntekt: BigDecimal,
        val inntektskilde: String,
        val skjønnsfastsatt: BigDecimal?,
    )

    data class PensjonsgivendeInntekt(
        val år: Int,
        val beløp: BigDecimal,
    )

    fun forskjellerFra(spleis: Sykepengegrunnlagsfakta): List<Forskjell> =
        buildList {
            sammenlign("sykepengegrunnlag", sykepengegrunnlag, spleis.sykepengegrunnlag)
            sammenlign("6G", seksG, spleis.seksG)
            sammenlign("fastsatt", fastsatt, spleis.fastsatt)
            val våreArbeidsgivere = arbeidsgivere.associateBy { it.orgnummer }
            val spleisArbeidsgivere = spleis.arbeidsgivere.associateBy { it.orgnummer }
            (våreArbeidsgivere.keys + spleisArbeidsgivere.keys).forEach { orgnummer ->
                val vår = våreArbeidsgivere[orgnummer]
                val spleisArbeidsgiver = spleisArbeidsgivere[orgnummer]
                val felt = "arbeidsgivere[${orgnummer.verdi}]"
                if (vår == null || spleisArbeidsgiver == null) {
                    add(Forskjell(felt, orgnummer, vår?.let { FINNES }, spleisArbeidsgiver?.let { FINNES }))
                    return@forEach
                }
                sammenlign("$felt.omregnetÅrsinntekt", vår.omregnetÅrsinntekt, spleisArbeidsgiver.omregnetÅrsinntekt, orgnummer)
                sammenlign("$felt.inntektskilde", vår.inntektskilde, spleisArbeidsgiver.inntektskilde, orgnummer)
                sammenlign("$felt.skjønnsfastsatt", vår.skjønnsfastsatt, spleisArbeidsgiver.skjønnsfastsatt, orgnummer)
            }
            val våreÅr = pensjonsgivendeInntekter.associate { it.år to it.beløp }
            val spleisÅr = spleis.pensjonsgivendeInntekter.associate { it.år to it.beløp }
            (våreÅr.keys + spleisÅr.keys).sorted().forEach { år ->
                sammenlign("pensjonsgivendeInntekter[$år]", våreÅr[år], spleisÅr[år])
            }
        }

    private fun MutableList<Forskjell>.sammenlign(
        felt: String,
        vår: BigDecimal?,
        spleis: BigDecimal?,
        orgnummer: Organisasjonsnummer? = null,
    ) {
        val lik = if (vår == null || spleis == null) vår == spleis else vår.compareTo(spleis) == 0
        if (!lik) add(Forskjell(felt, orgnummer, vår?.toPlainString(), spleis?.toPlainString()))
    }

    private fun MutableList<Forskjell>.sammenlign(
        felt: String,
        vår: String,
        spleis: String,
        orgnummer: Organisasjonsnummer? = null,
    ) {
        if (vår != spleis) add(Forskjell(felt, orgnummer, vår, spleis))
    }

    companion object {
        private const val FINNES = "finnes"

        /** Spleis viser inntektskilden `Saksbehandler` når inntekten er skjønnsfastsatt eller korrigert av saksbehandler. */
        private const val SAKSBEHANDLER = "Saksbehandler"

        fun fra(grunnlag: Sykepengegrunnlag) =
            Sykepengegrunnlagsfakta(
                sykepengegrunnlag = beløp(grunnlag.sykepengegrunnlag),
                seksG = beløp(grunnlag.seksG),
                fastsatt = if (grunnlag.erSkjønnsfastsatt) "EtterSkjønn" else "EtterHovedregel",
                arbeidsgivere =
                    grunnlag.aktiveArbeidsgivere.map {
                        Arbeidsgiver(
                            orgnummer = it.orgnummer,
                            omregnetÅrsinntekt = beløp(it.overstyrtEllerOmregnetÅrsinntekt),
                            inntektskilde = if (it.skjønnsfastsatt != null || it.overstyrtInntekt != null) SAKSBEHANDLER else it.inntektskilde.iSpleis,
                            skjønnsfastsatt = it.skjønnsfastsatt?.let { skjønnsfastsatt -> beløp(skjønnsfastsatt.årsinntekt) },
                        )
                    },
                pensjonsgivendeInntekter =
                    grunnlag.selvstendig
                        ?.årsinntekter
                        .orEmpty()
                        .map { PensjonsgivendeInntekt(år = it.år, beløp = beløp(it.beløp.toBigDecimal())) },
            )

        /** Navnet på inntektskilden i `sykepengegrunnlagsfakta` fra Spleis. */
        private val Inntektskilde.iSpleis
            get() =
                when (this) {
                    Inntektskilde.INNTEKTSMELDING -> "Arbeidsgiver"
                    Inntektskilde.A_ORDNINGEN -> "AOrdningen"
                }

        /** Spleis sender beløpene som `Double`. Begge sider avrundes til øre før de sammenlignes, slik at binærfeil ikke gir forskjeller. */
        fun beløp(verdi: BigDecimal): BigDecimal = verdi.setScale(2, RoundingMode.HALF_UP)
    }
}

internal data class Forskjell(
    val felt: String,
    val orgnummer: Organisasjonsnummer?,
    val vår: String?,
    val spleis: String?,
) {
    override fun toString() = "$felt: sp-sykepengegrunnlag=${vår ?: "mangler"}, Spleis=${spleis ?: "mangler"}"
}

internal sealed interface Sammenligning {
    val utfall: String

    data object Mangler : Sammenligning {
        override val utfall = "mangler"
    }

    data class Lik(
        val grunnlag: Sykepengegrunnlag,
    ) : Sammenligning {
        override val utfall = "lik"
    }

    data class Forskjellig(
        val grunnlag: Sykepengegrunnlag,
        val forskjeller: List<Forskjell>,
    ) : Sammenligning {
        override val utfall = "forskjell"

        /** Månedene fra a-ordningen for arbeidsgiverne med forskjell, slik at korrigerte a-meldinger kan skilles fra feil (ADR 0019). */
        val månederFraAOrdningenForArbeidsgivereMedForskjell =
            forskjeller
                .mapNotNull { it.orgnummer }
                .distinct()
                .mapNotNull { orgnummer -> grunnlag.arbeidsgivere.singleOrNull { it.orgnummer == orgnummer && it.inntektskilde == Inntektskilde.A_ORDNINGEN } }
                .associate { it.orgnummer to it.skatteopplysninger }
    }

    companion object {
        fun sammenlign(
            grunnlag: Sykepengegrunnlag?,
            spleis: Sykepengegrunnlagsfakta,
        ): Sammenligning {
            if (grunnlag == null) return Mangler
            val forskjeller = Sykepengegrunnlagsfakta.fra(grunnlag).forskjellerFra(spleis)
            return if (forskjeller.isEmpty()) Lik(grunnlag) else Forskjellig(grunnlag, forskjeller)
        }
    }
}
