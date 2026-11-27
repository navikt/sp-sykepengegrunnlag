package no.nav.helse.sykepenger.sykepengegrunnlag.api

import com.fasterxml.jackson.annotation.JsonProperty
import no.nav.helse.sykepenger.sykepengegrunnlag.Arbeidsgiverinntekt
import no.nav.helse.sykepenger.sykepengegrunnlag.Inntektskilde
import no.nav.helse.sykepenger.sykepengegrunnlag.Lovreferanse
import no.nav.helse.sykepenger.sykepengegrunnlag.Opphav
import no.nav.helse.sykepenger.sykepengegrunnlag.Selvstendiginntekt
import no.nav.helse.sykepenger.sykepengegrunnlag.Skatteopplysning
import no.nav.helse.sykepenger.sykepengegrunnlag.Sykepengegrunnlag
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

internal data class ApiSykepengegrunnlagListe(
    val sykepengegrunnlag: List<ApiSykepengegrunnlag>,
)

internal data class ApiSykepengegrunnlag(
    val id: UUID,
    val opphav: ApiOpphav,
    val skjæringstidspunkt: LocalDate,
    val rettighetskategoriId: UUID,
    val grunnbeløp: BigDecimal,
    val seksG: BigDecimal,
    val beregningsgrunnlag: BigDecimal,
    val sykepengegrunnlag: BigDecimal,
    val er6GBegrenset: Boolean,
    val arbeidsgivere: List<ApiArbeidsgiverinntekt>,
    val selvstendig: ApiSelvstendiginntekt?,
    val lovreferanser: List<ApiLovreferanse>,
)

internal enum class ApiOpphav { VILKARSPROVING, OVERSTYRING_INNTEKT, OVERSTYRING_ARBEIDSFORHOLD, SKJONNSFASTSETTELSE, GRUNNBELOPSREGULERING, BACKFILL_SPLEIS, BACKFILL_INFOTRYGD }

internal data class ApiArbeidsgiverinntekt(
    val orgnummer: String,
    val inntektskilde: ApiInntektskilde,
    val inntektsmeldingInternDokumentId: UUID?,
    val inntektsmeldingHentet: Instant?,
    val aOrdningsoppslagId: UUID?,
    val aOrdningenHentet: Instant?,
    val skatteopplysninger: List<ApiSkatteopplysning>,
    val omregnetÅrsinntekt: BigDecimal,
    val overstyrtInntekt: ApiOverstyrtInntekt?,
    val skjønnsfastsatt: ApiSkjønnsfastsatt?,
    val deaktivert: Boolean,
    val fastsattÅrsinntekt: BigDecimal,
)

internal data class ApiOverstyrtInntekt(
    val overstyringId: UUID,
    @get:JsonProperty("årsinntekt")
    val årsinntekt: BigDecimal,
)

internal enum class ApiInntektskilde { INNTEKTSMELDING, A_ORDNINGEN }

internal data class ApiSkatteopplysning(
    val måned: YearMonth,
    val beløp: BigDecimal,
    val type: ApiInntekttype,
    val fordel: String,
    val beskrivelse: String,
)

internal enum class ApiInntekttype { LONNSINNTEKT, NAERINGSINNTEKT, PENSJON_ELLER_TRYGD, YTELSE_FRA_OFFENTLIGE }

// Jackson finner ikke egenskaper der navnet starter med æ, ø eller å, så de får navnet med @JsonProperty.
internal data class ApiSkjønnsfastsatt(
    val skjønnsfastsettelseId: UUID,
    @get:JsonProperty("årsinntekt")
    val årsinntekt: BigDecimal,
)

internal data class ApiSelvstendiginntekt(
    val søknadInternDokumentId: UUID,
    val søknadId: UUID,
    val søknadHentet: Instant,
    val anvendtGrunnbeløp: BigDecimal,
    @get:JsonProperty("årsinntekter")
    val årsinntekter: List<ApiÅrsinntekt>,
    val fastsattÅrsinntekt: BigDecimal,
    val beregningsgrunnlag: BigDecimal,
)

internal data class ApiÅrsinntekt(
    @get:JsonProperty("år")
    val år: Int,
    val beløp: Int,
    val gjennomsnittligG: Int,
)

internal data class ApiLovreferanse(
    val lov: String,
    val paragraf: String,
    val avsnitt: Int?,
    val setning: Int?,
    val bokstav: String?,
    val iKraftFra: LocalDate,
    val utfall: String,
)

internal fun List<Sykepengegrunnlag>.tilApi() = ApiSykepengegrunnlagListe(map { it.tilApi() })

private fun Sykepengegrunnlag.tilApi() =
    ApiSykepengegrunnlag(
        id = id.uuid,
        opphav = opphav.tilApi(),
        skjæringstidspunkt = skjæringstidspunkt,
        rettighetskategoriId = rettighetskategoriId.uuid,
        grunnbeløp = grunnbeløp,
        seksG = seksG,
        beregningsgrunnlag = beregningsgrunnlag,
        sykepengegrunnlag = sykepengegrunnlag,
        er6GBegrenset = er6GBegrenset,
        arbeidsgivere = arbeidsgivere.map { it.tilApi() },
        selvstendig = selvstendig?.tilApi(),
        lovreferanser = lovreferanser.map { it.tilApi() },
    )

private fun Opphav.tilApi() =
    when (this) {
        Opphav.VILKÅRSPRØVING -> ApiOpphav.VILKARSPROVING
        Opphav.OVERSTYRING_INNTEKT -> ApiOpphav.OVERSTYRING_INNTEKT
        Opphav.OVERSTYRING_ARBEIDSFORHOLD -> ApiOpphav.OVERSTYRING_ARBEIDSFORHOLD
        Opphav.SKJØNNSFASTSETTELSE -> ApiOpphav.SKJONNSFASTSETTELSE
        Opphav.GRUNNBELØPSREGULERING -> ApiOpphav.GRUNNBELOPSREGULERING
        Opphav.BACKFILL_SPLEIS -> ApiOpphav.BACKFILL_SPLEIS
        Opphav.BACKFILL_INFOTRYGD -> ApiOpphav.BACKFILL_INFOTRYGD
    }

private fun Arbeidsgiverinntekt.tilApi() =
    ApiArbeidsgiverinntekt(
        orgnummer = orgnummer.verdi,
        inntektskilde =
            when (inntektskilde) {
                Inntektskilde.INNTEKTSMELDING -> ApiInntektskilde.INNTEKTSMELDING
                Inntektskilde.A_ORDNINGEN -> ApiInntektskilde.A_ORDNINGEN
            },
        inntektsmeldingInternDokumentId = inntektsmeldingInternDokumentId?.uuid,
        inntektsmeldingHentet = inntektsmeldingHentet,
        aOrdningsoppslagId = aOrdningsoppslagId?.uuid,
        aOrdningenHentet = aOrdningenHentet,
        skatteopplysninger = skatteopplysninger.map { it.tilApi() },
        omregnetÅrsinntekt = omregnetÅrsinntekt,
        overstyrtInntekt = overstyrtInntekt?.let { ApiOverstyrtInntekt(it.overstyringId.uuid, it.årsinntekt) },
        skjønnsfastsatt = skjønnsfastsatt?.let { ApiSkjønnsfastsatt(it.skjønnsfastsettelseId.uuid, it.årsinntekt) },
        deaktivert = deaktivert,
        fastsattÅrsinntekt = fastsattÅrsinntekt,
    )

private fun Skatteopplysning.tilApi() =
    ApiSkatteopplysning(
        måned = måned,
        beløp = beløp,
        type =
            when (type) {
                Skatteopplysning.Inntekttype.LØNNSINNTEKT -> ApiInntekttype.LONNSINNTEKT
                Skatteopplysning.Inntekttype.NÆRINGSINNTEKT -> ApiInntekttype.NAERINGSINNTEKT
                Skatteopplysning.Inntekttype.PENSJON_ELLER_TRYGD -> ApiInntekttype.PENSJON_ELLER_TRYGD
                Skatteopplysning.Inntekttype.YTELSE_FRA_OFFENTLIGE -> ApiInntekttype.YTELSE_FRA_OFFENTLIGE
            },
        fordel = fordel,
        beskrivelse = beskrivelse,
    )

private fun Selvstendiginntekt.tilApi() =
    ApiSelvstendiginntekt(
        søknadInternDokumentId = søknad.internDokumentId.uuid,
        søknadId = søknad.søknadId.uuid,
        søknadHentet = søknad.hentet,
        anvendtGrunnbeløp = anvendtGrunnbeløp,
        årsinntekter = årsinntekter.map { ApiÅrsinntekt(it.år, it.beløp, it.gjennomsnittligG) },
        fastsattÅrsinntekt = fastsattÅrsinntekt,
        beregningsgrunnlag = beregningsgrunnlag,
    )

private fun Lovreferanse.tilApi() =
    ApiLovreferanse(
        lov = lov,
        paragraf = paragraf,
        avsnitt = avsnitt,
        setning = setning,
        bokstav = bokstav,
        iKraftFra = iKraftFra,
        utfall = utfall,
    )
