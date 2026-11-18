package no.nav.helse.sykepenger.sykepengegrunnlag

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
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

internal data class Arbeidsgiverinntekt(
    val orgnummer: Organisasjonsnummer,
    val inntektskilde: Inntektskilde,
    val inntektsmeldingInternDokumentId: InternDokumentId?,
    val inntektsmeldingHentet: Instant?,
    val omregnetÅrsinntekt: BigDecimal,
) {
    companion object {
        fun fraInntektsmelding(inntektsmelding: Inntektsmelding) =
            Arbeidsgiverinntekt(
                orgnummer = inntektsmelding.orgnummer,
                inntektskilde = Inntektskilde.INNTEKTSMELDING,
                inntektsmeldingInternDokumentId = inntektsmelding.internDokumentId,
                inntektsmeldingHentet = inntektsmelding.hentet,
                omregnetÅrsinntekt = inntektsmelding.beregnetInntektMånedlig * BigDecimal(12),
            )
    }
}

internal enum class Inntektskilde {
    INNTEKTSMELDING,
}

internal enum class Opphav {
    VILKÅRSPRØVING,
}

/** Reglene er kopiert fra `Inntektsgrunnlag` og `ArbeidsgiverInntektsopplysning` i Spleis, slik at resultatet kan sammenlignes. */
internal class Sykepengegrunnlag private constructor(
    val id: Id,
    val fødselsnummer: Identitetsnummer,
    val skjæringstidspunkt: LocalDate,
    val rettighetskategoriId: RettighetskategoriId,
    val opphav: Opphav,
    val arbeidsgivere: List<Arbeidsgiverinntekt>,
    val grunnbeløp: BigDecimal,
) {
    @JvmInline
    value class Id(
        val uuid: UUID,
    )

    init {
        check(arbeidsgivere.isNotEmpty()) { "Sykepengegrunnlaget må ha minst én arbeidsgiver" }
        check(arbeidsgivere.distinctBy { it.orgnummer }.size == arbeidsgivere.size) { "Sykepengegrunnlaget har duplikate orgnumre" }
    }

    val seksG = grunnbeløp * BigDecimal(6)
    val beregningsgrunnlag = arbeidsgivere.fold(BigDecimal.ZERO) { sum, arbeidsgiver -> sum + arbeidsgiver.omregnetÅrsinntekt }
    val sykepengegrunnlag = minOf(beregningsgrunnlag, seksG)
    val er6GBegrenset = beregningsgrunnlag > seksG

    val subsumsjoner =
        listOf(
            Subsumsjon.`§ 8-10 ledd 2 punktum 1`(
                erBegrenset = er6GBegrenset,
                maksimaltSykepengegrunnlagÅrlig = seksG,
                skjæringstidspunkt = skjæringstidspunkt,
                beregningsgrunnlagÅrlig = beregningsgrunnlag,
            ),
        )

    companion object {
        fun fastsett(
            fødselsnummer: Identitetsnummer,
            skjæringstidspunkt: LocalDate,
            rettighetskategoriId: RettighetskategoriId,
            inntektsmeldinger: List<Inntektsmelding>,
            nå: Instant,
        ) = Sykepengegrunnlag(
            id = Id(UUID.randomUUID()),
            fødselsnummer = fødselsnummer,
            skjæringstidspunkt = skjæringstidspunkt,
            rettighetskategoriId = rettighetskategoriId,
            opphav = Opphav.VILKÅRSPRØVING,
            arbeidsgivere = inntektsmeldinger.map(Arbeidsgiverinntekt::fraInntektsmelding),
            grunnbeløp = Grunnbeløp.gjeldende(skjæringstidspunkt, LocalDate.ofInstant(nå, ZoneId.of("Europe/Oslo"))),
        )
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
    }
}
