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
        grunnlag.arbeidsgivere.forEach { lagreArbeidsgiver(grunnlag, it) }
        grunnlag.subsumsjoner.forEach { lagreSubsumsjon(grunnlag, it) }
    }

    private fun lagreInntektsmelding(
        fødselsnummer: Identitetsnummer,
        inntektsmelding: Inntektsmelding,
    ) {
        @Language("PostgreSQL")
        val sql =
            """
            INSERT INTO inntektsmelding (intern_dokument_id, fødselsnummer, orgnummer, beregnet_inntekt, mottatt_dato)
            VALUES (:internDokumentId, :fodselsnummer, :orgnummer, :beregnetInntekt, :mottattDato)
            ON CONFLICT (intern_dokument_id) DO NOTHING
            """
        tx.run(
            queryOf(
                sql,
                mapOf(
                    "internDokumentId" to inntektsmelding.internDokumentId.uuid,
                    "fodselsnummer" to fødselsnummer.verdi,
                    "orgnummer" to inntektsmelding.orgnummer.verdi,
                    "beregnetInntekt" to inntektsmelding.beregnetInntektMånedlig.tilDesimal(),
                    "mottattDato" to inntektsmelding.mottattDato,
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
            INSERT INTO arbeidsgiverinntekt (sykepengegrunnlag_id, orgnummer, inntektskilde, inntektsmelding_id, inntektsmelding_hentet,
                                             omregnet_årsinntekt)
            VALUES (:sykepengegrunnlagId, :orgnummer, :inntektskilde, :inntektsmeldingId, :inntektsmeldingHentet, :omregnetArsinntekt)
            """
        tx.run(
            queryOf(
                sql,
                mapOf(
                    "sykepengegrunnlagId" to grunnlag.id.uuid,
                    "orgnummer" to arbeidsgiver.orgnummer.verdi,
                    "inntektskilde" to arbeidsgiver.inntektskilde.name,
                    "inntektsmeldingId" to arbeidsgiver.inntektsmeldingId?.uuid,
                    "inntektsmeldingHentet" to arbeidsgiver.inntektsmeldingHentet,
                    "omregnetArsinntekt" to arbeidsgiver.omregnetÅrsinntekt.tilDesimal(),
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
