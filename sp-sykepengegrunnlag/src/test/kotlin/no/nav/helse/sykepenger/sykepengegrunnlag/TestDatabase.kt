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

    fun tøm() = database.tøm("subsumsjon", "arbeidsgiverinntekt", "selvstendiginntekt", "sykepengegrunnlag", "inntektsmelding", "pensjonsgivende_inntekt", "soknad")

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

    data class Selvstendigrad(
        val sykepengegrunnlagId: UUID,
        val søknadInternDokumentId: UUID,
        val søknadHentet: Instant,
        val fastsattÅrsinntekt: BigDecimal,
    )

    data class Søknadsrad(
        val internDokumentId: UUID,
        val søknadId: UUID,
    )

    data class PensjonsgivendeInntektsrad(
        val søknadInternDokumentId: UUID,
        val år: Int,
        val erFerdigLignet: Boolean,
        val lønnsinntekt: Int,
        val lønnsinntektBarePensjonsdel: Int,
        val næringsinntekt: Int,
        val næringsinntektFraFiskeFangstEllerFamiliebarnehage: Int,
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

    fun selvstendiginntekter(): List<Selvstendigrad> =
        liste("SELECT * FROM selvstendiginntekt ORDER BY søknad_hentet") { row ->
            Selvstendigrad(
                sykepengegrunnlagId = row.uuid("sykepengegrunnlag_id"),
                søknadInternDokumentId = row.uuid("søknad_intern_dokument_id"),
                søknadHentet = row.instant("søknad_hentet"),
                fastsattÅrsinntekt = row.bigDecimal("fastsatt_årsinntekt"),
            )
        }

    fun søknader(): List<Søknadsrad> =
        liste("SELECT * FROM soknad ORDER BY intern_dokument_id") { row ->
            Søknadsrad(internDokumentId = row.uuid("intern_dokument_id"), søknadId = row.uuid("søknad_id"))
        }

    fun pensjonsgivendeInntekter(): List<PensjonsgivendeInntektsrad> =
        liste("SELECT * FROM pensjonsgivende_inntekt ORDER BY søknad_intern_dokument_id, år") { row ->
            PensjonsgivendeInntektsrad(
                søknadInternDokumentId = row.uuid("søknad_intern_dokument_id"),
                år = row.int("år"),
                erFerdigLignet = row.boolean("er_ferdig_lignet"),
                lønnsinntekt = row.bigDecimal("lønnsinntekt").intValueExact(),
                lønnsinntektBarePensjonsdel = row.bigDecimal("lønnsinntekt_bare_pensjonsdel").intValueExact(),
                næringsinntekt = row.bigDecimal("næringsinntekt").intValueExact(),
                næringsinntektFraFiskeFangstEllerFamiliebarnehage = row.bigDecimal("næringsinntekt_fra_fiske_fangst_eller_familiebarnehage").intValueExact(),
            )
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
