package no.nav.helse.sykepenger.sykepengegrunnlag

import kotliquery.TransactionalSession
import kotliquery.queryOf
import org.intellij.lang.annotations.Language
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

internal class PgSykepengegrunnlagRepository(
    private val tx: TransactionalSession,
) : SykepengegrunnlagRepository {
    override fun lagre(
        grunnlag: Sykepengegrunnlag,
        inntektsmeldinger: List<Inntektsmelding>,
        aOrdningsoppslag: AOrdningsoppslag?,
    ) {
        inntektsmeldinger.forEach { lagreInntektsmelding(grunnlag.fødselsnummer, it) }
        aOrdningsoppslag?.let { lagreAOrdningsoppslag(grunnlag.fødselsnummer, it) }
        grunnlag.selvstendig?.let { lagreSøknad(grunnlag.fødselsnummer, it.søknad) }
        lagreGrunnlag(grunnlag)
    }

    override fun finnNyesteUtenomBackfillOrNull(
        fødselsnummer: Identitetsnummer,
        skjæringstidspunkt: LocalDate,
    ): Sykepengegrunnlag? {
        @Language("PostgreSQL")
        val sql =
            """
            SELECT id, fødselsnummer, skjæringstidspunkt, rettighetskategori_id, opphav, data
            FROM sykepengegrunnlag
            WHERE fødselsnummer = :fodselsnummer
              AND skjæringstidspunkt = :skjaeringstidspunkt
              AND opphav NOT IN (:backfillSpleis, :backfillInfotrygd)
            ORDER BY opprettet DESC
            LIMIT 1
            """
        return tx.run(
            queryOf(
                sql,
                mapOf(
                    "fodselsnummer" to fødselsnummer.verdi,
                    "skjaeringstidspunkt" to skjæringstidspunkt,
                    "backfillSpleis" to Opphav.BACKFILL_SPLEIS.name,
                    "backfillInfotrygd" to Opphav.BACKFILL_INFOTRYGD.name,
                ),
            ).map { row ->
                val data = objectMapper.readValue<SykepengegrunnlagDto>(row.string("data"))
                Sykepengegrunnlag.fraLagring(
                    id = Sykepengegrunnlag.Id(row.uuid("id")),
                    fødselsnummer = Identitetsnummer.fraString(row.string("fødselsnummer")),
                    skjæringstidspunkt = row.localDate("skjæringstidspunkt"),
                    rettighetskategoriId = RettighetskategoriId(row.uuid("rettighetskategori_id")),
                    opphav = Opphav.valueOf(row.string("opphav")),
                    arbeidsgivere = data.arbeidsgivere.map { it.tilArbeidsgiverinntekt() },
                    selvstendig = data.selvstendig?.tilSelvstendiginntekt(data),
                    grunnbeløp = data.grunnbeløp,
                    seksG = data.seksG,
                    beregningsgrunnlag = data.beregningsgrunnlag,
                    sykepengegrunnlag = data.sykepengegrunnlag,
                    er6GBegrenset = data.er6GBegrenset,
                    lovreferanser = data.lovreferanser.map { it.tilLovreferanse() },
                )
            }.asSingle,
        )
    }

    private fun ArbeidsgiverinntektDto.tilArbeidsgiverinntekt() =
        Arbeidsgiverinntekt(
            orgnummer = Organisasjonsnummer.fraString(orgnummer),
            inntektskilde = Inntektskilde.valueOf(inntektskilde),
            inntektsmeldingInternDokumentId = inntektsmeldingInternDokumentId?.let(::InternDokumentId),
            inntektsmeldingHentet = inntektsmeldingHentet,
            skatteopplysninger =
                skatteopplysninger.map {
                    Skatteopplysning(
                        måned = it.måned,
                        beløp = it.beløp,
                        type = Skatteopplysning.Inntekttype.valueOf(it.type),
                        fordel = it.fordel,
                        beskrivelse = it.beskrivelse,
                    )
                },
            aOrdningsoppslagId = aOrdningsoppslagId?.let { AOrdningsoppslag.Id(it) },
            aOrdningenHentet = aOrdningenHentet,
            omregnetÅrsinntekt = omregnetÅrsinntekt,
        )

    private fun SelvstendiginntektDto.tilSelvstendiginntekt(grunnlag: SykepengegrunnlagDto) =
        Selvstendiginntekt.fraLagring(
            søknad = hentSøknad(this),
            anvendtGrunnbeløp = grunnlag.grunnbeløp,
            årsinntekter = pensjonsgivendeInntekter.map { Selvstendiginntekt.Årsinntekt(år = it.inntektsår, beløp = it.beløp, gjennomsnittligG = it.gjennomsnittligG) },
            fastsattÅrsinntekt = fastsattÅrsinntekt,
            // For selvstendig næringsdrivende er beregningsgrunnlaget i sykepengegrunnlaget det samme som etter § 8-35.
            beregningsgrunnlag = grunnlag.beregningsgrunnlag,
        )

    private fun LovreferanseDto.tilLovreferanse() =
        Lovreferanse(
            lov = lov,
            paragraf = paragraf,
            avsnitt = avsnitt,
            setning = setning,
            bokstav = bokstav,
            iKraftFra = iKraftFra,
            utfall = utfall,
        )

    private fun hentSøknad(selvstendig: SelvstendiginntektDto): Søknad {
        val internDokumentId = InternDokumentId(selvstendig.søknadInternDokumentId)

        @Language("PostgreSQL")
        val søknadSql = "SELECT søknad_id FROM soknad WHERE søknad_intern_dokument_id = :soknadInternDokumentId"

        @Language("PostgreSQL")
        val inntektSql =
            """
            SELECT år, er_ferdig_lignet, lønnsinntekt, lønnsinntekt_bare_pensjonsdel, næringsinntekt,
                   næringsinntekt_fra_fiske_fangst_eller_familiebarnehage
            FROM pensjonsgivende_inntekt
            WHERE søknad_intern_dokument_id = :soknadInternDokumentId
            ORDER BY år
            """
        val parametere = mapOf("soknadInternDokumentId" to internDokumentId.uuid)
        val søknadId =
            checkNotNull(tx.run(queryOf(søknadSql, parametere).map { SøknadId(it.uuid("søknad_id")) }.asSingle)) {
                "Fant ikke søknaden sykepengegrunnlaget bygger på"
            }
        val pensjonsgivendeInntekter =
            tx
                .run(
                    queryOf(inntektSql, parametere)
                        .map { row ->
                            PensjonsgivendeInntekt(
                                år = row.int("år"),
                                erFerdigLignet = row.boolean("er_ferdig_lignet"),
                                lønnsinntekt = row.bigDecimal("lønnsinntekt").intValueExact(),
                                lønnsinntektBarePensjonsdel = row.bigDecimal("lønnsinntekt_bare_pensjonsdel").intValueExact(),
                                næringsinntekt = row.bigDecimal("næringsinntekt").intValueExact(),
                                næringsinntektFraFiskeFangstEllerFamiliebarnehage = row.bigDecimal("næringsinntekt_fra_fiske_fangst_eller_familiebarnehage").intValueExact(),
                            )
                        }.asList,
                )
        return Søknad(
            internDokumentId = internDokumentId,
            søknadId = søknadId,
            pensjonsgivendeInntekter = pensjonsgivendeInntekter,
            hentet = selvstendig.søknadHentet,
        )
    }

    private fun lagreInntektsmelding(
        fødselsnummer: Identitetsnummer,
        inntektsmelding: Inntektsmelding,
    ) {
        @Language("PostgreSQL")
        val sql =
            """
            INSERT INTO inntektsmelding (intern_dokument_id, inntektsmelding_id, fødselsnummer, orgnummer, beregnet_inntekt, mottatt_dato)
            VALUES (:internDokumentId, :inntektsmeldingId, :fodselsnummer, :orgnummer, :beregnetInntekt, :mottattDato)
            ON CONFLICT (intern_dokument_id) DO NOTHING
            """
        tx.run(
            queryOf(
                sql,
                mapOf(
                    "internDokumentId" to inntektsmelding.internDokumentId.uuid,
                    "inntektsmeldingId" to inntektsmelding.inntektsmeldingId.uuid,
                    "fodselsnummer" to fødselsnummer.verdi,
                    "orgnummer" to inntektsmelding.orgnummer.verdi,
                    "beregnetInntekt" to inntektsmelding.beregnetInntektMånedlig.tilDesimal(),
                    "mottattDato" to inntektsmelding.mottattDato,
                ),
            ).asUpdate,
        )
    }

    private fun lagreAOrdningsoppslag(
        fødselsnummer: Identitetsnummer,
        oppslag: AOrdningsoppslag,
    ) {
        @Language("PostgreSQL")
        val sql =
            """
            INSERT INTO a_ordningsoppslag (id, fødselsnummer, første_måned, siste_måned, ainntektsfilter, hentet, svar)
            VALUES (:id, :fodselsnummer, :forsteManed, :sisteManed, :ainntektsfilter, :hentet, CAST(:svar AS jsonb))
            """
        tx.run(
            queryOf(
                sql,
                mapOf(
                    "id" to oppslag.id.uuid,
                    "fodselsnummer" to fødselsnummer.verdi,
                    "forsteManed" to oppslag.måneder.start.atDay(1),
                    "sisteManed" to oppslag.måneder.endInclusive.atDay(1),
                    "ainntektsfilter" to oppslag.ainntektsfilter,
                    "hentet" to oppslag.hentet,
                    "svar" to oppslag.svar,
                ),
            ).asUpdate,
        )
    }

    private fun lagreSøknad(
        fødselsnummer: Identitetsnummer,
        søknad: Søknad,
    ) {
        @Language("PostgreSQL")
        val søknadSql =
            """
            INSERT INTO soknad (søknad_intern_dokument_id, søknad_id, fødselsnummer)
            VALUES (:soknadInternDokumentId, :soknadId, :fodselsnummer)
            ON CONFLICT (søknad_intern_dokument_id) DO NOTHING
            """
        tx.run(
            queryOf(
                søknadSql,
                mapOf(
                    "soknadInternDokumentId" to søknad.internDokumentId.uuid,
                    "soknadId" to søknad.søknadId.uuid,
                    "fodselsnummer" to fødselsnummer.verdi,
                ),
            ).asUpdate,
        )

        @Language("PostgreSQL")
        val inntektSql =
            """
            INSERT INTO pensjonsgivende_inntekt (søknad_intern_dokument_id, år, er_ferdig_lignet, lønnsinntekt, lønnsinntekt_bare_pensjonsdel,
                                                 næringsinntekt, næringsinntekt_fra_fiske_fangst_eller_familiebarnehage)
            VALUES (:soknadInternDokumentId, :ar, :erFerdigLignet, :lonnsinntekt, :lonnsinntektBarePensjonsdel, :naringsinntekt,
                    :naringsinntektFraFiskeFangstEllerFamiliebarnehage)
            ON CONFLICT (søknad_intern_dokument_id, år) DO NOTHING
            """
        søknad.pensjonsgivendeInntekter.forEach { inntekt ->
            tx.run(
                queryOf(
                    inntektSql,
                    mapOf(
                        "soknadInternDokumentId" to søknad.internDokumentId.uuid,
                        "ar" to inntekt.år,
                        "erFerdigLignet" to inntekt.erFerdigLignet,
                        "lonnsinntekt" to inntekt.lønnsinntekt,
                        "lonnsinntektBarePensjonsdel" to inntekt.lønnsinntektBarePensjonsdel,
                        "naringsinntekt" to inntekt.næringsinntekt,
                        "naringsinntektFraFiskeFangstEllerFamiliebarnehage" to inntekt.næringsinntektFraFiskeFangstEllerFamiliebarnehage,
                    ),
                ).asUpdate,
            )
        }
    }

    private fun lagreGrunnlag(grunnlag: Sykepengegrunnlag) {
        @Language("PostgreSQL")
        val sql =
            """
            INSERT INTO sykepengegrunnlag (id, fødselsnummer, skjæringstidspunkt, rettighetskategori_id, opphav, data)
            VALUES (:id, :fodselsnummer, :skjaeringstidspunkt, :rettighetskategoriId, :opphav, CAST(:data AS jsonb))
            """
        tx.run(
            queryOf(
                sql,
                mapOf(
                    "id" to grunnlag.id.uuid,
                    "fodselsnummer" to grunnlag.fødselsnummer.verdi,
                    "skjaeringstidspunkt" to grunnlag.skjæringstidspunkt,
                    "rettighetskategoriId" to grunnlag.rettighetskategoriId.uuid,
                    "opphav" to grunnlag.opphav.name,
                    "data" to objectMapper.writeValueAsString(grunnlag.tilDto()),
                ),
            ).asUpdate,
        )
    }

    private fun Sykepengegrunnlag.tilDto() =
        SykepengegrunnlagDto(
            grunnbeløp = grunnbeløp.tilDesimal(),
            beregningsgrunnlag = beregningsgrunnlag.tilDesimal(),
            sykepengegrunnlag = sykepengegrunnlag.tilDesimal(),
            seksG = seksG.tilDesimal(),
            er6GBegrenset = er6GBegrenset,
            arbeidsgivere = arbeidsgivere.map { it.tilDto() },
            selvstendig = selvstendig?.tilDto(),
            lovreferanser = lovreferanser.map { it.tilDto() },
        )

    private fun Arbeidsgiverinntekt.tilDto() =
        ArbeidsgiverinntektDto(
            orgnummer = orgnummer.verdi,
            inntektskilde = inntektskilde.name,
            inntektsmeldingInternDokumentId = inntektsmeldingInternDokumentId?.uuid,
            inntektsmeldingHentet = inntektsmeldingHentet,
            aOrdningsoppslagId = aOrdningsoppslagId?.uuid,
            aOrdningenHentet = aOrdningenHentet,
            omregnetÅrsinntekt = omregnetÅrsinntekt.tilDesimal(),
            skatteopplysninger =
                skatteopplysninger.map {
                    SkatteopplysningDto(
                        måned = it.måned,
                        beløp = it.beløp.tilDesimal(),
                        type = it.type.name,
                        fordel = it.fordel,
                        beskrivelse = it.beskrivelse,
                    )
                },
        )

    private fun Selvstendiginntekt.tilDto() =
        SelvstendiginntektDto(
            søknadInternDokumentId = søknad.internDokumentId.uuid,
            søknadHentet = søknad.hentet,
            pensjonsgivendeInntekter = årsinntekter.map { PensjonsgivendeInntektDto(inntektsår = it.år, beløp = it.beløp, gjennomsnittligG = it.gjennomsnittligG) },
            fastsattÅrsinntekt = fastsattÅrsinntekt.tilDesimal(),
        )

    private fun Lovreferanse.tilDto() =
        LovreferanseDto(
            lov = lov,
            paragraf = paragraf,
            avsnitt = avsnitt,
            setning = setning,
            bokstav = bokstav,
            iKraftFra = iKraftFra,
            utfall = utfall,
        )

    private fun BigDecimal.tilDesimal() = setScale(2, RoundingMode.HALF_UP)

    private companion object {
        val objectMapper = jacksonObjectMapper()
    }
}

private data class SykepengegrunnlagDto(
    val grunnbeløp: BigDecimal,
    val beregningsgrunnlag: BigDecimal,
    val sykepengegrunnlag: BigDecimal,
    val seksG: BigDecimal,
    val er6GBegrenset: Boolean,
    val arbeidsgivere: List<ArbeidsgiverinntektDto>,
    val selvstendig: SelvstendiginntektDto?,
    val lovreferanser: List<LovreferanseDto>,
)

private data class ArbeidsgiverinntektDto(
    val orgnummer: String,
    val inntektskilde: String,
    val inntektsmeldingInternDokumentId: UUID?,
    val inntektsmeldingHentet: Instant?,
    val aOrdningsoppslagId: UUID?,
    val aOrdningenHentet: Instant?,
    val omregnetÅrsinntekt: BigDecimal,
    val skatteopplysninger: List<SkatteopplysningDto>,
)

private data class SkatteopplysningDto(
    val måned: YearMonth,
    val beløp: BigDecimal,
    val type: String,
    val fordel: String,
    val beskrivelse: String,
)

private data class SelvstendiginntektDto(
    val søknadInternDokumentId: UUID,
    val søknadHentet: Instant,
    val pensjonsgivendeInntekter: List<PensjonsgivendeInntektDto>,
    val fastsattÅrsinntekt: BigDecimal,
)

private data class PensjonsgivendeInntektDto(
    val inntektsår: Int,
    val beløp: Int,
    val gjennomsnittligG: Int,
)

private data class LovreferanseDto(
    val lov: String,
    val paragraf: String,
    val avsnitt: Int?,
    val setning: Int?,
    val bokstav: String?,
    val iKraftFra: LocalDate,
    val utfall: String,
)
