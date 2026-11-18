package no.nav.helse.sykepenger.sykepengegrunnlag

import kotliquery.queryOf
import kotliquery.sessionOf
import org.intellij.lang.annotations.Language
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import no.nav.sykepenger.libs.postgres.TestDatabase as PostgresTestDatabase

internal object TestDatabase {
    private val database = PostgresTestDatabase.start().also { it.migrer() }

    val dataSource: DataSource = database.dataSource

    fun tøm() = database.tøm("subsumsjon", "arbeidsgiverinntekt", "sykepengegrunnlag", "inntektsmelding")

    data class Grunnlagsrad(
        val id: UUID,
        val fødselsnummer: String,
        val skjæringstidspunkt: LocalDate,
        val rettighetskategoriId: UUID,
        val opphav: String,
        val beregningsgrunnlag: BigDecimal,
        val sykepengegrunnlag: BigDecimal,
        val grunnbeløp: BigDecimal,
        val opprettet: Instant,
    )

    data class Arbeidsgiverrad(
        val sykepengegrunnlagId: UUID,
        val orgnummer: String,
        val inntektskilde: String,
        val inntektsmeldingInternDokumentId: UUID?,
        val omregnetÅrsinntekt: BigDecimal,
    )

    data class Inntektsmeldingrad(
        val internDokumentId: UUID,
        val inntektsmeldingId: UUID,
        val orgnummer: String,
        val beregnetInntekt: BigDecimal,
        val mottattDato: Instant,
    )

    data class Subsumsjonsrad(
        val sykepengegrunnlagId: UUID,
        val paragraf: String,
        val ledd: Int?,
        val punktum: Int?,
        val utfall: String,
        val input: String,
        val output: String,
    )

    fun grunnlag(): List<Grunnlagsrad> =
        liste("SELECT * FROM sykepengegrunnlag ORDER BY opprettet") { row ->
            Grunnlagsrad(
                id = row.uuid("id"),
                fødselsnummer = row.string("fødselsnummer"),
                skjæringstidspunkt = row.localDate("skjæringstidspunkt"),
                rettighetskategoriId = row.uuid("rettighetskategori_id"),
                opphav = row.string("opphav"),
                beregningsgrunnlag = row.bigDecimal("beregningsgrunnlag"),
                sykepengegrunnlag = row.bigDecimal("sykepengegrunnlag"),
                grunnbeløp = row.bigDecimal("grunnbeløp"),
                opprettet = row.instant("opprettet"),
            )
        }

    fun arbeidsgivere(): List<Arbeidsgiverrad> =
        liste("SELECT * FROM arbeidsgiverinntekt ORDER BY løpenummer") { row ->
            Arbeidsgiverrad(
                sykepengegrunnlagId = row.uuid("sykepengegrunnlag_id"),
                orgnummer = row.string("orgnummer"),
                inntektskilde = row.string("inntektskilde"),
                inntektsmeldingInternDokumentId = row.uuidOrNull("inntektsmelding_intern_dokument_id"),
                omregnetÅrsinntekt = row.bigDecimal("omregnet_årsinntekt"),
            )
        }

    fun inntektsmeldinger(): List<Inntektsmeldingrad> =
        liste("SELECT * FROM inntektsmelding ORDER BY mottatt_dato") { row ->
            Inntektsmeldingrad(
                internDokumentId = row.uuid("intern_dokument_id"),
                inntektsmeldingId = row.uuid("inntektsmelding_id"),
                orgnummer = row.string("orgnummer"),
                beregnetInntekt = row.bigDecimal("beregnet_inntekt"),
                mottattDato = row.instant("mottatt_dato"),
            )
        }

    fun inntektsmeldingoppslag(): List<Pair<UUID, Instant>> =
        liste("SELECT sykepengegrunnlag_id, inntektsmelding_hentet FROM arbeidsgiverinntekt ORDER BY løpenummer") { row ->
            row.uuid("sykepengegrunnlag_id") to row.instant("inntektsmelding_hentet")
        }

    fun subsumsjoner(): List<Subsumsjonsrad> =
        liste("SELECT * FROM subsumsjon ORDER BY løpenummer") { row ->
            Subsumsjonsrad(
                sykepengegrunnlagId = row.uuid("sykepengegrunnlag_id"),
                paragraf = row.string("paragraf"),
                ledd = row.intOrNull("ledd"),
                punktum = row.intOrNull("punktum"),
                utfall = row.string("utfall"),
                input = row.string("input"),
                output = row.string("output"),
            )
        }

    private fun <T> liste(
        @Language("PostgreSQL") sql: String,
        mapper: (kotliquery.Row) -> T,
    ): List<T> =
        sessionOf(dataSource).use { session ->
            session.run(queryOf(sql).map(mapper).asList)
        }
}
