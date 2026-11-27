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
 * slik at resultatet blir likt. Bare [fastsett] regner. Et lagret grunnlag gjenopprettes med beløpene det ble fastsatt med.
 */
internal class Selvstendiginntekt private constructor(
    val søknad: Søknad,
    val anvendtGrunnbeløp: BigDecimal,
    val årsinntekter: List<Årsinntekt>,
    val fastsattÅrsinntekt: BigDecimal,
    val beregningsgrunnlag: BigDecimal,
) {
    data class Årsinntekt(
        val år: Int,
        val beløp: Int,
        val gjennomsnittligG: Int,
    )

    companion object {
        fun fastsett(
            søknad: Søknad,
            anvendtGrunnbeløp: BigDecimal,
        ): Selvstendiginntekt {
            val årsberegninger = søknad.pensjonsgivendeInntekter.map { Årsberegning(it.år, it.sum) }
            check(årsberegninger.size <= 3) { "Selvstendig kan ikke ha mer enn tre inntekter" }
            check(årsberegninger.distinctBy { it.år }.size == årsberegninger.size) { "Selvstendig kan ikke ha flere inntekter med samme årstall" }
            årsberegninger.map { it.år }.sorted().zipWithNext().forEach { (forrige, neste) ->
                check(neste == forrige + 1) { "Inntektene må være i sekvens; årstallet må øke med 1 for hvert år" }
            }
            val grunnbeløp = anvendtGrunnbeløp.toDouble()
            return Selvstendiginntekt(
                søknad = søknad,
                anvendtGrunnbeløp = anvendtGrunnbeløp,
                årsinntekter = årsberegninger.map { Årsinntekt(it.år, it.beløp, it.snitt) },
                fastsattÅrsinntekt = årsberegninger.summer { it.grunnbeløpsjustertGjennomsnitt(grunnbeløp) },
                beregningsgrunnlag = årsberegninger.summer { it.beregningsgrunnlag(grunnbeløp) },
            )
        }

        fun fraLagring(
            søknad: Søknad,
            anvendtGrunnbeløp: BigDecimal,
            årsinntekter: List<Årsinntekt>,
            fastsattÅrsinntekt: BigDecimal,
            beregningsgrunnlag: BigDecimal,
        ) = Selvstendiginntekt(søknad, anvendtGrunnbeløp, årsinntekter, fastsattÅrsinntekt, beregningsgrunnlag)

        private fun List<Årsberegning>.summer(beløp: (Årsberegning) -> Double) = sumOf(beløp).toInt().toBigDecimal()
    }

    private class Årsberegning(
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

@JvmInline
internal value class SkjønnsfastsettelseId(
    val uuid: UUID,
)

/** Årsinntekten saksbehandler har fastsatt for én arbeidsgiver ved skjønnsfastsettelse etter § 8-30 andre ledd. */
internal data class Skjønnsfastsettelse(
    val orgnummer: Organisasjonsnummer,
    val årsinntekt: BigDecimal,
)

internal data class Skjønnsfastsatt(
    val skjønnsfastsettelseId: SkjønnsfastsettelseId,
    val årsinntekt: BigDecimal,
)

@JvmInline
internal value class OverstyringId(
    val uuid: UUID,
)

/** Årsinntekten saksbehandler har satt for én arbeidsgiver ved overstyring av inntekt. */
internal data class Inntektsoverstyring(
    val orgnummer: Organisasjonsnummer,
    val årsinntekt: BigDecimal,
)

internal data class OverstyrtInntekt(
    val overstyringId: OverstyringId,
    val årsinntekt: BigDecimal,
)

/** Saksbehandler har deaktivert eller aktivert ett arbeidsforhold i grunnlaget. */
internal data class Arbeidsforholdoverstyring(
    val orgnummer: Organisasjonsnummer,
    val deaktivert: Boolean,
)

internal data class Arbeidsgiverinntekt(
    val orgnummer: Organisasjonsnummer,
    val inntektskilde: Inntektskilde,
    val inntektsmeldingInternDokumentId: InternDokumentId?,
    val inntektsmeldingHentet: Instant?,
    val skatteopplysninger: List<Skatteopplysning>,
    val aOrdningsoppslagId: AOrdningsoppslag.Id?,
    val aOrdningenHentet: Instant?,
    val omregnetÅrsinntekt: BigDecimal,
    val overstyrtInntekt: OverstyrtInntekt?,
    val skjønnsfastsatt: Skjønnsfastsatt?,
    /** Saksbehandler har deaktivert arbeidsforholdet. Det er med i grunnlaget, men inntekten teller ikke med, som `deaktiverteArbeidsforhold` i Spleis. */
    val deaktivert: Boolean,
) {
    /** Det Spleis kaller `omregnetÅrsinntekt` i `ArbeidsgiverInntektsopplysning`: overstyrt inntekt, ellers omregnet årsinntekt. */
    val overstyrtEllerOmregnetÅrsinntekt = overstyrtInntekt?.årsinntekt ?: omregnetÅrsinntekt
    val fastsattÅrsinntekt = if (deaktivert) BigDecimal.ZERO else skjønnsfastsatt?.årsinntekt ?: overstyrtEllerOmregnetÅrsinntekt

    fun tilBehovsarbeidsgiver() = Sykepengegrunnlagsbehov.Arbeidsgiver(orgnummer, inntektskilde, inntektsmeldingInternDokumentId)

    companion object {
        fun fraInntektsmelding(inntektsmelding: Inntektsmelding) =
            Arbeidsgiverinntekt(
                orgnummer = inntektsmelding.orgnummer,
                inntektskilde = Inntektskilde.INNTEKTSMELDING,
                inntektsmeldingInternDokumentId = inntektsmelding.internDokumentId,
                inntektsmeldingHentet = inntektsmelding.hentet,
                skatteopplysninger = emptyList(),
                aOrdningsoppslagId = null,
                aOrdningenHentet = null,
                omregnetÅrsinntekt = inntektsmelding.beregnetInntektMånedlig * MÅNEDER_I_ÅRET,
                overstyrtInntekt = null,
                skjønnsfastsatt = null,
                deaktivert = false,
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
                aOrdningsoppslagId = oppslag.id,
                aOrdningenHentet = oppslag.hentet,
                omregnetÅrsinntekt = (gjennomsnittPerMåned * MÅNEDER_I_ÅRET).setScale(2, RoundingMode.HALF_UP),
                overstyrtInntekt = null,
                skjønnsfastsatt = null,
                deaktivert = false,
            )
        }

        fun beregningsperiodeForAOrdningen(skjæringstidspunkt: LocalDate): ClosedRange<YearMonth> {
            val utgangspunkt = YearMonth.from(skjæringstidspunkt)
            return utgangspunkt.minusMonths(MÅNEDER_I_BEREGNINGSPERIODEN)..utgangspunkt.minusMonths(1)
        }

        private const val MÅNEDER_I_BEREGNINGSPERIODEN = 3L

        /** Månedsinntekt regnes om til årsinntekt ved å gange med 12, som `Inntekt.månedlig` i Spleis. */
        val MÅNEDER_I_ÅRET = BigDecimal(12)
    }
}

internal enum class Inntektskilde {
    INNTEKTSMELDING,
    A_ORDNINGEN,
}

/** Det Spleis ber om i behovet `Sykepengegrunnlag`. */
internal class Sykepengegrunnlagsbehov(
    val fødselsnummer: Identitetsnummer,
    val skjæringstidspunkt: LocalDate,
    val rettighetskategoriId: RettighetskategoriId,
    val arbeidsgivere: List<Arbeidsgiver>,
    val søknadInternDokumentId: InternDokumentId?,
) {
    data class Arbeidsgiver(
        val orgnummer: Organisasjonsnummer,
        val inntektskilde: Inntektskilde,
        val inntektsmeldingInternDokumentId: InternDokumentId?,
    )
}

internal enum class Opphav {
    VILKÅRSPRØVING,
    OVERSTYRING_INNTEKT,
    OVERSTYRING_ARBEIDSFORHOLD,
    SKJØNNSFASTSETTELSE,
    GRUNNBELØPSREGULERING,
    BACKFILL_SPLEIS,
    BACKFILL_INFOTRYGD,
    ;

    val iMeldinger get() = name.lowercase()
}

/**
 * Reglene er kopiert fra `Inntektsgrunnlag`, `ArbeidsgiverInntektsopplysning` og `SelvstendigFaktaavklartInntekt` i Spleis, slik at
 * resultatet kan sammenlignes. Bare `fastsett`-funksjonene regner. Et lagret grunnlag gjenopprettes med beløpene og lovreferansene det
 * ble fastsatt med, slik at en senere endring i reglene ikke endrer et grunnlag som allerede er brukt.
 */
internal class Sykepengegrunnlag private constructor(
    val id: Id,
    val fødselsnummer: Identitetsnummer,
    val skjæringstidspunkt: LocalDate,
    val rettighetskategoriId: RettighetskategoriId,
    val opphav: Opphav,
    val arbeidsgivere: List<Arbeidsgiverinntekt>,
    val selvstendig: Selvstendiginntekt?,
    val grunnbeløp: BigDecimal,
    val seksG: BigDecimal,
    val beregningsgrunnlag: BigDecimal,
    val sykepengegrunnlag: BigDecimal,
    val er6GBegrenset: Boolean,
    val lovreferanser: List<Lovreferanse>,
) {
    @JvmInline
    value class Id(
        val uuid: UUID,
    )

    private val _hendelser = mutableListOf<EndretSykepengegrunnlag>()
    val hendelser: List<EndretSykepengegrunnlag> get() = _hendelser.toList()

    /** Arbeidsgiverne som teller med i grunnlaget, som `arbeidsgiverInntektsopplysninger` i Spleis. */
    val aktiveArbeidsgivere get() = arbeidsgivere.filterNot { it.deaktivert }

    val erSkjønnsfastsatt get() = arbeidsgivere.any { it.skjønnsfastsatt != null }

    /**
     * Kopiert fra `Inntektsgrunnlag.skjønnsmessigFastsettelse` i Spleis: alle de aktive arbeidsgiverne i grunnlaget må skjønnsfastsettes,
     * og grunnbeløpet beholdes. Deaktiverte arbeidsforhold skjønnsfastsettes ikke.
     */
    fun skjønnsfastsett(
        skjønnsfastsettelseId: SkjønnsfastsettelseId,
        fastsettelser: List<Skjønnsfastsettelse>,
    ): Sykepengegrunnlag {
        val aktive = aktiveArbeidsgivere
        check(fastsettelser.map { it.orgnummer }.toSet() == aktive.map { it.orgnummer }.toSet() && fastsettelser.size == aktive.size) {
            "Alle de aktive arbeidsgiverne i sykepengegrunnlaget må skjønnsfastsettes"
        }
        val fastsettelsePerOrgnummer = fastsettelser.associateBy { it.orgnummer }
        return endre(
            opphav = Opphav.SKJØNNSFASTSETTELSE,
            manuellVurdering = true,
            arbeidsgivere =
                arbeidsgivere.map {
                    if (it !in aktive) return@map it
                    it.copy(skjønnsfastsatt = Skjønnsfastsatt(skjønnsfastsettelseId, fastsettelsePerOrgnummer.getValue(it.orgnummer).årsinntekt))
                },
        )
    }

    /**
     * Kopiert fra `ArbeidsgiverInntektsopplysning.håndterKorrigerteInntekter` og `Inntektsgrunnlag.Utfall.bestem` i Spleis. Spesialist sender
     * inntekt og refusjon i samme melding, så en arbeidsgiver der overstyringen har samme beløp som før, er uendret. Er ingen arbeidsgivere
     * endret, blir det ikke noe nytt grunnlag. Endres beløpet for minst én arbeidsgiver, nullstilles skjønnsfastsettelsen for alle, fordi den
     * gjelder hele grunnlaget. Overstyringer for arbeidsgivere som ikke er i grunnlaget eller er deaktivert, ignoreres.
     */
    fun overstyrInntekt(
        overstyringId: OverstyringId,
        overstyringer: List<Inntektsoverstyring>,
    ): Sykepengegrunnlag? {
        val overstyringPerOrgnummer = overstyringer.associateBy { it.orgnummer }
        val endrede =
            aktiveArbeidsgivere
                .filter { arbeidsgiver ->
                    val overstyring = overstyringPerOrgnummer[arbeidsgiver.orgnummer] ?: return@filter false
                    overstyring.årsinntekt.compareTo(arbeidsgiver.overstyrtEllerOmregnetÅrsinntekt) != 0
                }.map { it.orgnummer }
                .toSet()
        if (endrede.isEmpty()) return null
        return endre(
            opphav = Opphav.OVERSTYRING_INNTEKT,
            manuellVurdering = true,
            arbeidsgivere =
                arbeidsgivere.map {
                    if (it.orgnummer !in endrede) return@map it.copy(skjønnsfastsatt = null)
                    it.copy(
                        overstyrtInntekt = OverstyrtInntekt(overstyringId, overstyringPerOrgnummer.getValue(it.orgnummer).årsinntekt),
                        skjønnsfastsatt = null,
                    )
                },
        )
    }

    /**
     * Kopiert fra `Inntektsgrunnlag.deaktiver` og `Inntektsgrunnlag.aktiver` i Spleis. Et arbeidsforhold som allerede er deaktivert eller
     * aktivert, er uendret. Er ingen arbeidsforhold endret, blir det ikke noe nytt grunnlag. Endres minst ett, nullstilles
     * skjønnsfastsettelsen for alle, fordi den gjelder hele grunnlaget. Arbeidsforhold som ikke er i grunnlaget, ignoreres.
     */
    fun overstyrArbeidsforhold(overstyringer: List<Arbeidsforholdoverstyring>): Sykepengegrunnlag? {
        val overstyringPerOrgnummer = overstyringer.associateBy { it.orgnummer }
        val endrede =
            arbeidsgivere
                .filter { arbeidsgiver ->
                    val overstyring = overstyringPerOrgnummer[arbeidsgiver.orgnummer] ?: return@filter false
                    overstyring.deaktivert != arbeidsgiver.deaktivert
                }.map { it.orgnummer }
                .toSet()
        if (endrede.isEmpty()) return null
        return endre(
            opphav = Opphav.OVERSTYRING_ARBEIDSFORHOLD,
            manuellVurdering = true,
            arbeidsgivere =
                arbeidsgivere.map {
                    if (it.orgnummer !in endrede) return@map it.copy(skjønnsfastsatt = null)
                    it.copy(deaktivert = !it.deaktivert, skjønnsfastsatt = null)
                },
        )
    }

    /**
     * Kopiert fra `Inntektsgrunnlag.grunnbeløpsregulering` i Spleis: grunnlaget fastsettes med grunnbeløpet som gjelder for
     * skjæringstidspunktet nå, og § 8-35 for selvstendig næringsdrivende regnes på nytt med det. Er 6G den samme som før, blir det
     * ikke noe nytt grunnlag.
     */
    fun grunnbeløpsreguler(nå: Instant): Sykepengegrunnlag? {
        val nyttGrunnbeløp = gjeldendeGrunnbeløp(skjæringstidspunkt, nå)
        val nySeksG = nyttGrunnbeløp * SEKS_G
        if (nySeksG.compareTo(seksG) == 0) return null
        return endre(
            opphav = Opphav.GRUNNBELØPSREGULERING,
            manuellVurdering = false,
            arbeidsgivere = arbeidsgivere,
            selvstendig = selvstendig?.let { Selvstendiginntekt.fastsett(it.søknad, anvendtGrunnbeløp = nyttGrunnbeløp) },
            grunnbeløp = nyttGrunnbeløp,
            seksG = nySeksG,
        )
    }

    /** Arbeidsforholdene i overstyringene som ikke er i grunnlaget. */
    fun ukjenteArbeidsforhold(overstyringer: List<Arbeidsforholdoverstyring>): List<Organisasjonsnummer> {
        val kjente = arbeidsgivere.map { it.orgnummer }.toSet()
        return overstyringer.map { it.orgnummer }.filterNot { it in kjente }
    }

    /**
     * Spleis sender behovet ved inngang til tilstanden og ved hver påminnelse. Et grunnlag fra vilkårsprøving med det samme innholdet som
     * behovet gjenbrukes, slik at det ikke blir et nytt grunnlag hver gang. Rekkefølgen på arbeidsgiverne spiller ingen rolle. Et grunnlag
     * fra en endring gjenbrukes aldri, fordi behovet betyr at Spleis vilkårsprøver på nytt uten endringene.
     */
    fun kanGjenbrukesFor(behov: Sykepengegrunnlagsbehov): Boolean {
        if (opphav != Opphav.VILKÅRSPRØVING) return false
        val egneArbeidsgivere = arbeidsgivere.map { it.tilBehovsarbeidsgiver() }
        return behov.fødselsnummer == fødselsnummer &&
            behov.skjæringstidspunkt == skjæringstidspunkt &&
            behov.rettighetskategoriId == rettighetskategoriId &&
            behov.søknadInternDokumentId == selvstendig?.søknad?.internDokumentId &&
            behov.arbeidsgivere.groupingBy { it }.eachCount() == egneArbeidsgivere.groupingBy { it }.eachCount()
    }

    private fun endre(
        opphav: Opphav,
        manuellVurdering: Boolean,
        arbeidsgivere: List<Arbeidsgiverinntekt>,
        selvstendig: Selvstendiginntekt? = this.selvstendig,
        grunnbeløp: BigDecimal = this.grunnbeløp,
        seksG: BigDecimal = this.seksG,
    ): Sykepengegrunnlag =
        nytt(
            fødselsnummer = fødselsnummer,
            skjæringstidspunkt = skjæringstidspunkt,
            rettighetskategoriId = rettighetskategoriId,
            opphav = opphav,
            arbeidsgivere = arbeidsgivere,
            selvstendig = selvstendig,
            grunnbeløp = grunnbeløp,
            seksG = seksG,
            lovreferanser = lovreferanser,
        ).also {
            it._hendelser.add(
                EndretSykepengegrunnlag(
                    fødselsnummer = fødselsnummer,
                    skjæringstidspunkt = skjæringstidspunkt,
                    sykepengegrunnlagId = it.id,
                    forrigeSykepengegrunnlagId = id,
                    opphav = opphav,
                    manuellVurdering = manuellVurdering,
                ),
            )
        }

    init {
        check(arbeidsgivere.isNotEmpty() != (selvstendig != null)) { "Sykepengegrunnlaget må ha enten arbeidsgivere eller selvstendig næringsdrivende" }
        check(arbeidsgivere.distinctBy { it.orgnummer }.size == arbeidsgivere.size) { "Sykepengegrunnlaget har duplikate orgnumre" }
    }

    companion object {
        fun fastsett(
            fødselsnummer: Identitetsnummer,
            skjæringstidspunkt: LocalDate,
            rettighetskategoriId: RettighetskategoriId,
            arbeidsgivere: List<Arbeidsgiverinntekt>,
            nå: Instant,
        ) = beregn(
            fødselsnummer = fødselsnummer,
            skjæringstidspunkt = skjæringstidspunkt,
            rettighetskategoriId = rettighetskategoriId,
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
            return beregn(
                fødselsnummer = fødselsnummer,
                skjæringstidspunkt = skjæringstidspunkt,
                rettighetskategoriId = rettighetskategoriId,
                arbeidsgivere = emptyList(),
                selvstendig = Selvstendiginntekt.fastsett(søknad, anvendtGrunnbeløp = grunnbeløp),
                grunnbeløp = grunnbeløp,
            )
        }

        fun fraLagring(
            id: Id,
            fødselsnummer: Identitetsnummer,
            skjæringstidspunkt: LocalDate,
            rettighetskategoriId: RettighetskategoriId,
            opphav: Opphav,
            arbeidsgivere: List<Arbeidsgiverinntekt>,
            selvstendig: Selvstendiginntekt?,
            grunnbeløp: BigDecimal,
            seksG: BigDecimal,
            beregningsgrunnlag: BigDecimal,
            sykepengegrunnlag: BigDecimal,
            er6GBegrenset: Boolean,
            lovreferanser: List<Lovreferanse>,
        ) = Sykepengegrunnlag(
            id = id,
            fødselsnummer = fødselsnummer,
            skjæringstidspunkt = skjæringstidspunkt,
            rettighetskategoriId = rettighetskategoriId,
            opphav = opphav,
            arbeidsgivere = arbeidsgivere,
            selvstendig = selvstendig,
            grunnbeløp = grunnbeløp,
            seksG = seksG,
            beregningsgrunnlag = beregningsgrunnlag,
            sykepengegrunnlag = sykepengegrunnlag,
            er6GBegrenset = er6GBegrenset,
            lovreferanser = lovreferanser,
        )

        private fun beregn(
            fødselsnummer: Identitetsnummer,
            skjæringstidspunkt: LocalDate,
            rettighetskategoriId: RettighetskategoriId,
            arbeidsgivere: List<Arbeidsgiverinntekt>,
            selvstendig: Selvstendiginntekt?,
            grunnbeløp: BigDecimal,
        ): Sykepengegrunnlag =
            nytt(
                fødselsnummer = fødselsnummer,
                skjæringstidspunkt = skjæringstidspunkt,
                rettighetskategoriId = rettighetskategoriId,
                opphav = Opphav.VILKÅRSPRØVING,
                arbeidsgivere = arbeidsgivere,
                selvstendig = selvstendig,
                grunnbeløp = grunnbeløp,
                seksG = grunnbeløp * SEKS_G,
                lovreferanser =
                    listOfNotNull(
                        Lovreferanse.`§ 8-28 tredje avsnitt, bokstav a`().takeIf { arbeidsgivere.any { it.inntektskilde == Inntektskilde.A_ORDNINGEN } },
                        Lovreferanse.`§ 8-10 andre avsnitt, første setning`(),
                        selvstendig?.let { Lovreferanse.`§ 8-35 andre avsnitt`() },
                    ),
            )

        private fun nytt(
            fødselsnummer: Identitetsnummer,
            skjæringstidspunkt: LocalDate,
            rettighetskategoriId: RettighetskategoriId,
            opphav: Opphav,
            arbeidsgivere: List<Arbeidsgiverinntekt>,
            selvstendig: Selvstendiginntekt?,
            grunnbeløp: BigDecimal,
            seksG: BigDecimal,
            lovreferanser: List<Lovreferanse>,
        ): Sykepengegrunnlag {
            val beregningsgrunnlag = selvstendig?.beregningsgrunnlag ?: arbeidsgivere.fold(BigDecimal.ZERO) { sum, arbeidsgiver -> sum + arbeidsgiver.fastsattÅrsinntekt }
            return Sykepengegrunnlag(
                id = Id(UUID.randomUUID()),
                fødselsnummer = fødselsnummer,
                skjæringstidspunkt = skjæringstidspunkt,
                rettighetskategoriId = rettighetskategoriId,
                opphav = opphav,
                arbeidsgivere = arbeidsgivere,
                selvstendig = selvstendig,
                grunnbeløp = grunnbeløp,
                seksG = seksG,
                beregningsgrunnlag = beregningsgrunnlag,
                sykepengegrunnlag = minOf(beregningsgrunnlag, seksG),
                er6GBegrenset = beregningsgrunnlag > seksG,
                lovreferanser = lovreferanser,
            )
        }

        /** Sykepengegrunnlaget begrenses til seks ganger grunnbeløpet, § 8-10 andre ledd første punktum. */
        private val SEKS_G = BigDecimal(6)

        private fun gjeldendeGrunnbeløp(
            skjæringstidspunkt: LocalDate,
            nå: Instant,
        ) = Grunnbeløp.gjeldende(skjæringstidspunkt, LocalDate.ofInstant(nå, ZoneId.of("Europe/Oslo")))
    }
}

internal data class EndretSykepengegrunnlag(
    val fødselsnummer: Identitetsnummer,
    val skjæringstidspunkt: LocalDate,
    val sykepengegrunnlagId: Sykepengegrunnlag.Id,
    val forrigeSykepengegrunnlagId: Sykepengegrunnlag.Id,
    val opphav: Opphav,
    val manuellVurdering: Boolean,
)

internal data class Lovreferanse(
    val lov: String,
    val paragraf: String,
    val avsnitt: Int?,
    val setning: Int?,
    val bokstav: String?,
    val iKraftFra: LocalDate,
    val utfall: String,
) {
    companion object {
        fun `§ 8-10 andre avsnitt, første setning`() =
            Lovreferanse(
                lov = "folketrygdloven",
                paragraf = "8-10",
                avsnitt = 2,
                setning = 1,
                bokstav = null,
                iKraftFra = LocalDate.of(2020, 1, 1),
                utfall = VILKÅR_BEREGNET,
            )

        fun `§ 8-28 tredje avsnitt, bokstav a`() =
            Lovreferanse(
                lov = "folketrygdloven",
                paragraf = "8-28",
                avsnitt = 3,
                setning = null,
                bokstav = "a",
                iKraftFra = LocalDate.of(2019, 1, 1),
                utfall = VILKÅR_BEREGNET,
            )

        fun `§ 8-35 andre avsnitt`() =
            Lovreferanse(
                lov = "folketrygdloven",
                paragraf = "8-35",
                avsnitt = 2,
                setning = null,
                bokstav = null,
                iKraftFra = LocalDate.of(2018, 12, 20),
                utfall = VILKÅR_BEREGNET,
            )

        private const val VILKÅR_BEREGNET = "VILKAR_BEREGNET"
    }
}
