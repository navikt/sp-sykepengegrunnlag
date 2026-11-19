package no.nav.helse.sykepenger.sykepengegrunnlag

import kotliquery.TransactionalSession
import kotliquery.queryOf
import org.intellij.lang.annotations.Language
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode

internal class PgSykepengegrunnlagRepository(
    private val tx: TransactionalSession,
) : SykepengegrunnlagRepository {
    override fun lagre(
        grunnlag: Sykepengegrunnlag,
        inntektsmeldinger: List<Inntektsmelding>,
    ) {
        inntektsmeldinger.forEach { lagreInntektsmelding(grunnlag.fødselsnummer, it) }
        lagreGrunnlag(grunnlag)
        grunnlag.arbeidsgivere.forEach {
            lagreArbeidsgiver(grunnlag, it)
            it.skatteopplysninger.forEach { skatteopplysning -> lagreSkatteopplysning(grunnlag, it, skatteopplysning) }
        }
        grunnlag.selvstendig?.let {
            lagreSøknad(grunnlag.fødselsnummer, it.søknad)
            lagreSelvstendig(grunnlag, it)
        }
        grunnlag.subsumsjoner.forEach { lagreSubsumsjon(grunnlag, it) }
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

    private fun lagreSelvstendig(
        grunnlag: Sykepengegrunnlag,
        selvstendig: Selvstendiginntekt,
    ) {
        @Language("PostgreSQL")
        val sql =
            """
            INSERT INTO selvstendiginntekt (sykepengegrunnlag_id, søknad_intern_dokument_id, søknad_hentet, fastsatt_årsinntekt)
            VALUES (:sykepengegrunnlagId, :soknadInternDokumentId, :soknadHentet, :fastsattArsinntekt)
            """
        tx.run(
            queryOf(
                sql,
                mapOf(
                    "sykepengegrunnlagId" to grunnlag.id.uuid,
                    "soknadInternDokumentId" to selvstendig.søknad.internDokumentId.uuid,
                    "soknadHentet" to selvstendig.søknad.hentet,
                    "fastsattArsinntekt" to selvstendig.fastsattÅrsinntekt.tilDesimal(),
                ),
            ).asUpdate,
        )
    }

    private fun lagreGrunnlag(grunnlag: Sykepengegrunnlag) {
        @Language("PostgreSQL")
        val sql =
            """
            INSERT INTO sykepengegrunnlag (id, fødselsnummer, skjæringstidspunkt, rettighetskategori_id, opphav, beregningsgrunnlag,
                                           sykepengegrunnlag, grunnbeløp)
            VALUES (:id, :fodselsnummer, :skjaeringstidspunkt, :rettighetskategoriId, :opphav, :beregningsgrunnlag,
                    :sykepengegrunnlag, :grunnbelop)
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
                    "beregningsgrunnlag" to grunnlag.beregningsgrunnlag.tilDesimal(),
                    "sykepengegrunnlag" to grunnlag.sykepengegrunnlag.tilDesimal(),
                    "grunnbelop" to grunnlag.grunnbeløp.tilDesimal(),
                ),
            ).asUpdate,
        )
    }

    private fun lagreArbeidsgiver(
        grunnlag: Sykepengegrunnlag,
        arbeidsgiver: Arbeidsgiverinntekt,
    ) {
        @Language("PostgreSQL")
        val sql =
            """
            INSERT INTO arbeidsgiverinntekt (sykepengegrunnlag_id, orgnummer, inntektskilde, inntektsmelding_intern_dokument_id, inntektsmelding_hentet,
                                             a_ordningen_hentet, omregnet_årsinntekt)
            VALUES (:sykepengegrunnlagId, :orgnummer, :inntektskilde, :inntektsmeldingInternDokumentId, :inntektsmeldingHentet, :aOrdningenHentet,
                    :omregnetArsinntekt)
            """
        tx.run(
            queryOf(
                sql,
                mapOf(
                    "sykepengegrunnlagId" to grunnlag.id.uuid,
                    "orgnummer" to arbeidsgiver.orgnummer.verdi,
                    "inntektskilde" to arbeidsgiver.inntektskilde.name,
                    "inntektsmeldingInternDokumentId" to arbeidsgiver.inntektsmeldingInternDokumentId?.uuid,
                    "inntektsmeldingHentet" to arbeidsgiver.inntektsmeldingHentet,
                    "aOrdningenHentet" to arbeidsgiver.aOrdningenHentet,
                    "omregnetArsinntekt" to arbeidsgiver.omregnetÅrsinntekt.tilDesimal(),
                ),
            ).asUpdate,
        )
    }

    private fun lagreSkatteopplysning(
        grunnlag: Sykepengegrunnlag,
        arbeidsgiver: Arbeidsgiverinntekt,
        skatteopplysning: Skatteopplysning,
    ) {
        @Language("PostgreSQL")
        val sql =
            """
            INSERT INTO skatteopplysning (sykepengegrunnlag_id, orgnummer, måned, beløp, type, fordel, beskrivelse)
            VALUES (:sykepengegrunnlagId, :orgnummer, :maned, :belop, :type, :fordel, :beskrivelse)
            """
        tx.run(
            queryOf(
                sql,
                mapOf(
                    "sykepengegrunnlagId" to grunnlag.id.uuid,
                    "orgnummer" to arbeidsgiver.orgnummer.verdi,
                    "maned" to skatteopplysning.måned.atDay(1),
                    "belop" to skatteopplysning.beløp.tilDesimal(),
                    "type" to skatteopplysning.type.name,
                    "fordel" to skatteopplysning.fordel,
                    "beskrivelse" to skatteopplysning.beskrivelse,
                ),
            ).asUpdate,
        )
    }

    private fun lagreSubsumsjon(
        grunnlag: Sykepengegrunnlag,
        subsumsjon: Subsumsjon,
    ) {
        @Language("PostgreSQL")
        val sql =
            """
            INSERT INTO subsumsjon (sykepengegrunnlag_id, lovverk, versjon, paragraf, ledd, punktum, bokstav, utfall, input, output)
            VALUES (:sykepengegrunnlagId, :lovverk, :versjon, :paragraf, :ledd, :punktum, :bokstav, :utfall,
                    CAST(:input AS jsonb), CAST(:output AS jsonb))
            """
        tx.run(
            queryOf(
                sql,
                mapOf(
                    "sykepengegrunnlagId" to grunnlag.id.uuid,
                    "lovverk" to subsumsjon.lovverk,
                    "versjon" to subsumsjon.versjon,
                    "paragraf" to subsumsjon.paragraf,
                    "ledd" to subsumsjon.ledd,
                    "punktum" to subsumsjon.punktum,
                    "bokstav" to subsumsjon.bokstav,
                    "utfall" to subsumsjon.utfall,
                    "input" to objectMapper.writeValueAsString(subsumsjon.input.medAvrundedeBeløp()),
                    "output" to objectMapper.writeValueAsString(subsumsjon.output),
                ),
            ).asUpdate,
        )
    }

    private fun BigDecimal.tilDesimal() = setScale(2, RoundingMode.HALF_UP)

    private fun Map<String, Any>.medAvrundedeBeløp() = mapValues { (_, verdi) -> if (verdi is BigDecimal) verdi.tilDesimal() else verdi }

    private companion object {
        val objectMapper = jacksonObjectMapper()
    }
}
