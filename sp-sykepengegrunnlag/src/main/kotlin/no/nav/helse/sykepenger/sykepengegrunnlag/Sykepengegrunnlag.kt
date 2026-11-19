package no.nav.helse.sykepenger.sykepengegrunnlag

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

@JvmInline
internal value class InternDokumentId(
    val uuid: UUID,
)

@JvmInline
internal value class RettighetskategoriId(
    val uuid: UUID,
)

@JvmInline
internal value class InntektsmeldingId(
    val uuid: UUID,
)

internal data class Inntektsmelding(
    val internDokumentId: InternDokumentId,
    val inntektsmeldingId: InntektsmeldingId,
    val orgnummer: Organisasjonsnummer,
    val beregnetInntektMånedlig: BigDecimal,
    val mottattDato: Instant,
    val hentet: Instant,
)

@JvmInline
internal value class SøknadId(
    val uuid: UUID,
)

internal data class Søknad(
    val internDokumentId: InternDokumentId,
    val søknadId: SøknadId,
    val pensjonsgivendeInntekter: List<PensjonsgivendeInntekt>,
    val hentet: Instant,
)

internal data class PensjonsgivendeInntekt(
    val år: Int,
    val erFerdigLignet: Boolean,
    val lønnsinntekt: Int,
    val lønnsinntektBarePensjonsdel: Int,
    val næringsinntekt: Int,
    val næringsinntektFraFiskeFangstEllerFamiliebarnehage: Int,
) {
    val sum = lønnsinntekt + lønnsinntektBarePensjonsdel + næringsinntekt + næringsinntektFraFiskeFangstEllerFamiliebarnehage
}

/**
 * Reglene etter § 8-35 er kopiert fra `SelvstendigFaktaavklartInntekt` i Spleis, og regnes med `Double` slik Spleis gjør,
 * slik at resultatet blir likt.
 */
internal class Selvstendiginntekt(
    val søknad: Søknad,
    val anvendtGrunnbeløp: BigDecimal,
) {
    val årsinntekter = søknad.pensjonsgivendeInntekter.map { Årsinntekt(it.år, it.sum) }

    init {
        check(årsinntekter.size <= 3) { "Selvstendig kan ikke ha mer enn tre inntekter" }
        check(årsinntekter.distinctBy { it.år }.size == årsinntekter.size) { "Selvstendig kan ikke ha flere inntekter med samme årstall" }
        årsinntekter.map { it.år }.sorted().zipWithNext().forEach { (forrige, neste) ->
            check(neste == forrige + 1) { "Inntektene må være i sekvens; årstallet må øke med 1 for hvert år" }
        }
    }

    val fastsattÅrsinntekt = summer { it.grunnbeløpsjustertGjennomsnitt(anvendtGrunnbeløp.toDouble()) }
    val beregningsgrunnlag = summer { it.beregningsgrunnlag(anvendtGrunnbeløp.toDouble()) }

    private fun summer(beløp: (Årsinntekt) -> Double) = årsinntekter.sumOf(beløp).toInt().toBigDecimal()

    class Årsinntekt(
        val år: Int,
        val beløp: Int,
    ) {
        val snitt = Grunnbeløp.snitt(år)

        private val antallG = beløp.toDouble() / snitt
        private val inntekterMellom6gOg12g = antallG.coerceIn(SEKS_G, TOLV_G) - SEKS_G
        private val inntekterOver12g = antallG.coerceAtLeast(TOLV_G) - TOLV_G
        private val antallGKompensert = antallG - inntekterOver12g - inntekterMellom6gOg12g * TO_TREDJEDELER

        init {
            check(antallGKompensert <= 8) { "Antall kompenserte G kan ikke være over 8, var $antallGKompensert" }
        }

        fun grunnbeløpsjustertGjennomsnitt(anvendtGrunnbeløp: Double) = anvendtGrunnbeløp * (antallG / 3.0)

        fun beregningsgrunnlag(anvendtGrunnbeløp: Double) = anvendtGrunnbeløp * (antallGKompensert / 3.0)

        private companion object {
            const val SEKS_G = 6.0
            const val TOLV_G = 12.0
            const val TO_TREDJEDELER = 2 / 3.0
        }
    }
}

internal data class Skatteopplysning(
    val måned: YearMonth,
    val beløp: BigDecimal,
    val type: Inntekttype,
    val fordel: String,
    val beskrivelse: String,
) {
    enum class Inntekttype(
        private val inntektskomponenten: String,
    ) {
        LØNNSINNTEKT("LOENNSINNTEKT"),
        NÆRINGSINNTEKT("NAERINGSINNTEKT"),
        PENSJON_ELLER_TRYGD("PENSJON_ELLER_TRYGD"),
        YTELSE_FRA_OFFENTLIGE("YTELSE_FRA_OFFENTLIGE"),
        ;

        companion object {
            fun fraInntektskomponenten(inntektType: String) = requireNotNull(entries.find { it.inntektskomponenten == inntektType }) { "Ukjent inntektstype fra inntektskomponenten: $inntektType" }
        }
    }
}

internal data class Arbeidsgiverinntekt(
    val orgnummer: Organisasjonsnummer,
    val inntektskilde: Inntektskilde,
    val inntektsmeldingInternDokumentId: InternDokumentId?,
    val inntektsmeldingHentet: Instant?,
    val skatteopplysninger: List<Skatteopplysning>,
    val aOrdningenHentet: Instant?,
    val omregnetÅrsinntekt: BigDecimal,
) {
    companion object {
        fun fraInntektsmelding(inntektsmelding: Inntektsmelding) =
            Arbeidsgiverinntekt(
                orgnummer = inntektsmelding.orgnummer,
                inntektskilde = Inntektskilde.INNTEKTSMELDING,
                inntektsmeldingInternDokumentId = inntektsmelding.internDokumentId,
                inntektsmeldingHentet = inntektsmelding.hentet,
                skatteopplysninger = emptyList(),
                aOrdningenHentet = null,
                omregnetÅrsinntekt = inntektsmelding.beregnetInntektMånedlig * BigDecimal(12),
            )

        /** § 8-28 tredje ledd bokstav a: gjennomsnittet av de tre månedene før skjæringstidspunktet, regnet om til årsinntekt. */
        fun fraAOrdningen(
            orgnummer: Organisasjonsnummer,
            oppslag: AOrdningsoppslag,
        ): Arbeidsgiverinntekt {
            val skatteopplysninger =
                oppslag.inntekter
                    .filter { it.virksomhet == orgnummer.verdi }
                    .map { it.skatteopplysning }
            val sum = skatteopplysninger.fold(BigDecimal.ZERO) { sum, opplysning -> sum + opplysning.beløp }
            // Regnes som Skatteopplysning.omregnetÅrsinntekt i Spleis, slik at grunnlaget blir likt:
            // - Summen deles alltid på tre, også når a-ordningen mangler inntekt for noen av månedene. Et kortere tidsrom
            //   etter § 8-28 tredje ledd bokstav b må saksbehandler fastsette ved å overstyre inntekten.
            // - En negativ sum, for eksempel når arbeidsgiveren har trukket tilbake lønn, gir 0.
            val gjennomsnittPerMåned = sum.max(BigDecimal.ZERO).divide(BigDecimal(MÅNEDER_I_BEREGNINGSPERIODEN), MathContext.DECIMAL128)
            return Arbeidsgiverinntekt(
                orgnummer = orgnummer,
                inntektskilde = Inntektskilde.A_ORDNINGEN,
                inntektsmeldingInternDokumentId = null,
                inntektsmeldingHentet = null,
                skatteopplysninger = skatteopplysninger,
                aOrdningenHentet = oppslag.hentet,
                omregnetÅrsinntekt = (gjennomsnittPerMåned * BigDecimal(12)).setScale(2, RoundingMode.HALF_UP),
            )
        }

        fun beregningsperiodeForAOrdningen(skjæringstidspunkt: LocalDate): ClosedRange<YearMonth> {
            val utgangspunkt = YearMonth.from(skjæringstidspunkt)
            return utgangspunkt.minusMonths(MÅNEDER_I_BEREGNINGSPERIODEN)..utgangspunkt.minusMonths(1)
        }

        private const val MÅNEDER_I_BEREGNINGSPERIODEN = 3L
    }
}

internal enum class Inntektskilde {
    INNTEKTSMELDING,
    A_ORDNINGEN,
}

internal enum class Opphav {
    VILKÅRSPRØVING,
}

/** Reglene er kopiert fra `Inntektsgrunnlag`, `ArbeidsgiverInntektsopplysning` og `SelvstendigFaktaavklartInntekt` i Spleis, slik at resultatet kan sammenlignes. */
internal class Sykepengegrunnlag private constructor(
    val id: Id,
    val fødselsnummer: Identitetsnummer,
    val skjæringstidspunkt: LocalDate,
    val rettighetskategoriId: RettighetskategoriId,
    val opphav: Opphav,
    val arbeidsgivere: List<Arbeidsgiverinntekt>,
    val selvstendig: Selvstendiginntekt?,
    val grunnbeløp: BigDecimal,
) {
    @JvmInline
    value class Id(
        val uuid: UUID,
    )

    init {
        check(arbeidsgivere.isNotEmpty() != (selvstendig != null)) { "Sykepengegrunnlaget må ha enten arbeidsgivere eller selvstendig næringsdrivende" }
        check(arbeidsgivere.distinctBy { it.orgnummer }.size == arbeidsgivere.size) { "Sykepengegrunnlaget har duplikate orgnumre" }
    }

    val seksG = grunnbeløp * BigDecimal(6)
    val beregningsgrunnlag = selvstendig?.beregningsgrunnlag ?: arbeidsgivere.fold(BigDecimal.ZERO) { sum, arbeidsgiver -> sum + arbeidsgiver.omregnetÅrsinntekt }
    val sykepengegrunnlag = minOf(beregningsgrunnlag, seksG)
    val er6GBegrenset = beregningsgrunnlag > seksG

    val subsumsjoner =
        arbeidsgivere
            .filter { it.inntektskilde == Inntektskilde.A_ORDNINGEN }
            .map { Subsumsjon.`§ 8-28 ledd 3 bokstav a`(it, skjæringstidspunkt) } +
            listOfNotNull(
                Subsumsjon.`§ 8-10 ledd 2 punktum 1`(
                    erBegrenset = er6GBegrenset,
                    maksimaltSykepengegrunnlagÅrlig = seksG,
                    skjæringstidspunkt = skjæringstidspunkt,
                    beregningsgrunnlagÅrlig = beregningsgrunnlag,
                ),
                selvstendig?.let {
                    Subsumsjon.`§ 8-35 ledd 2`(
                        årsinntekter = it.årsinntekter,
                        nåværendeGrunnbeløp = it.anvendtGrunnbeløp,
                        skjæringstidspunkt = skjæringstidspunkt,
                        sykepengegrunnlag = sykepengegrunnlag,
                    )
                },
            )

    companion object {
        fun fastsett(
            fødselsnummer: Identitetsnummer,
            skjæringstidspunkt: LocalDate,
            rettighetskategoriId: RettighetskategoriId,
            arbeidsgivere: List<Arbeidsgiverinntekt>,
            nå: Instant,
        ) = Sykepengegrunnlag(
            id = Id(UUID.randomUUID()),
            fødselsnummer = fødselsnummer,
            skjæringstidspunkt = skjæringstidspunkt,
            rettighetskategoriId = rettighetskategoriId,
            opphav = Opphav.VILKÅRSPRØVING,
            arbeidsgivere = arbeidsgivere,
            selvstendig = null,
            grunnbeløp = gjeldendeGrunnbeløp(skjæringstidspunkt, nå),
        )

        fun fastsettForSelvstendig(
            fødselsnummer: Identitetsnummer,
            skjæringstidspunkt: LocalDate,
            rettighetskategoriId: RettighetskategoriId,
            søknad: Søknad,
            nå: Instant,
        ): Sykepengegrunnlag {
            val grunnbeløp = gjeldendeGrunnbeløp(skjæringstidspunkt, nå)
            return Sykepengegrunnlag(
                id = Id(UUID.randomUUID()),
                fødselsnummer = fødselsnummer,
                skjæringstidspunkt = skjæringstidspunkt,
                rettighetskategoriId = rettighetskategoriId,
                opphav = Opphav.VILKÅRSPRØVING,
                arbeidsgivere = emptyList(),
                selvstendig = Selvstendiginntekt(søknad, anvendtGrunnbeløp = grunnbeløp),
                grunnbeløp = grunnbeløp,
            )
        }

        private fun gjeldendeGrunnbeløp(
            skjæringstidspunkt: LocalDate,
            nå: Instant,
        ) = Grunnbeløp.gjeldende(skjæringstidspunkt, LocalDate.ofInstant(nå, ZoneId.of("Europe/Oslo")))
    }
}

internal data class Subsumsjon(
    val lovverk: String,
    val versjon: LocalDate,
    val paragraf: String,
    val ledd: Int?,
    val punktum: Int?,
    val bokstav: String?,
    val utfall: String,
    val input: Map<String, Any>,
    val output: Map<String, Any>,
) {
    companion object {
        fun `§ 8-10 ledd 2 punktum 1`(
            erBegrenset: Boolean,
            maksimaltSykepengegrunnlagÅrlig: BigDecimal,
            skjæringstidspunkt: LocalDate,
            beregningsgrunnlagÅrlig: BigDecimal,
        ) = Subsumsjon(
            lovverk = "folketrygdloven",
            versjon = LocalDate.of(2020, 1, 1),
            paragraf = "8-10",
            ledd = 2,
            punktum = 1,
            bokstav = null,
            utfall = "VILKAR_BEREGNET",
            input =
                mapOf(
                    "maksimaltSykepengegrunnlag" to maksimaltSykepengegrunnlagÅrlig,
                    "skjæringstidspunkt" to skjæringstidspunkt,
                    "grunnlagForSykepengegrunnlag" to beregningsgrunnlagÅrlig,
                ),
            output = mapOf("erBegrenset" to erBegrenset),
        )

        fun `§ 8-28 ledd 3 bokstav a`(
            arbeidsgiver: Arbeidsgiverinntekt,
            skjæringstidspunkt: LocalDate,
        ) = Subsumsjon(
            lovverk = "folketrygdloven",
            versjon = LocalDate.of(2019, 1, 1),
            paragraf = "8-28",
            ledd = 3,
            punktum = null,
            bokstav = "a",
            utfall = "VILKAR_BEREGNET",
            input =
                mapOf(
                    "organisasjonsnummer" to arbeidsgiver.orgnummer.verdi,
                    "inntekterSisteTreMåneder" to
                        arbeidsgiver.skatteopplysninger.map {
                            mapOf(
                                "beløp" to it.beløp,
                                "årMåned" to it.måned.toString(),
                                "type" to it.type.name,
                                "fordel" to it.fordel,
                                "beskrivelse" to it.beskrivelse,
                            )
                        },
                    "skjæringstidspunkt" to skjæringstidspunkt,
                ),
            output =
                mapOf(
                    "beregnetGrunnlagForSykepengegrunnlagPrÅr" to arbeidsgiver.omregnetÅrsinntekt,
                    "beregnetGrunnlagForSykepengegrunnlagPrMåned" to arbeidsgiver.omregnetÅrsinntekt.divide(BigDecimal(12), 2, RoundingMode.HALF_UP),
                ),
        )

        fun `§ 8-35 ledd 2`(
            årsinntekter: List<Selvstendiginntekt.Årsinntekt>,
            nåværendeGrunnbeløp: BigDecimal,
            skjæringstidspunkt: LocalDate,
            sykepengegrunnlag: BigDecimal,
        ) = Subsumsjon(
            lovverk = "folketrygdloven",
            versjon = LocalDate.of(2018, 12, 20),
            paragraf = "8-35",
            ledd = 2,
            punktum = null,
            bokstav = null,
            utfall = "VILKAR_BEREGNET",
            input =
                mapOf(
                    "pensjonsgivendeInntekter" to
                        årsinntekter.map {
                            mapOf(
                                "pensjonsgivendeInntekt" to it.beløp,
                                "årstall" to it.år,
                                "gjennomsnittligG" to it.snitt,
                            )
                        },
                    "nåværendeGrunnbeløp" to nåværendeGrunnbeløp,
                    "skjæringstidspunkt" to skjæringstidspunkt,
                ),
            output = mapOf("sykepengegrunnlag" to sykepengegrunnlag),
        )
    }
}
