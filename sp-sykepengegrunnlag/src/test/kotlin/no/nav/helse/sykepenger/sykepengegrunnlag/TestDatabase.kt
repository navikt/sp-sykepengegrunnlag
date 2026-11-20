package no.nav.helse.sykepenger.sykepengegrunnlag

import kotliquery.queryOf
import kotliquery.sessionOf
import org.intellij.lang.annotations.Language
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import javax.sql.DataSource
import no.nav.sykepenger.libs.postgres.TestDatabase as PostgresTestDatabase

internal object TestDatabase {
    private val database = PostgresTestDatabase.start().also { it.migrer() }

    val dataSource: DataSource = database.dataSource

    fun tøm() = database.tøm("sykepengegrunnlag", "a_ordningsoppslag", "inntektsmelding", "pensjonsgivende_inntekt", "soknad")

    data class Grunnlagsrad(
        val id: UUID,
        val fødselsnummer: String,
        val skjæringstidspunkt: LocalDate,
        val rettighetskategoriId: UUID,
        val opphav: String,
        val beregningsgrunnlag: BigDecimal,
        val sykepengegrunnlag: BigDecimal,
        val grunnbeløp: BigDecimal,
        val seksG: BigDecimal,
        val er6GBegrenset: Boolean,
        val opprettet: Instant,
    )

    data class Arbeidsgiverrad(
        val sykepengegrunnlagId: UUID,
        val orgnummer: String,
        val inntektskilde: String,
        val inntektsmeldingInternDokumentId: UUID?,
        val omregnetÅrsinntekt: BigDecimal,
        val aOrdningsoppslagId: UUID? = null,
    )

    data class AOrdningsoppslagrad(
        val id: UUID,
        val fødselsnummer: String,
        val førsteMåned: YearMonth,
        val sisteMåned: YearMonth,
        val ainntektsfilter: String,
        val svar: String,
    )

    data class Skatteopplysningsrad(
        val sykepengegrunnlagId: UUID,
        val orgnummer: String,
        val måned: YearMonth,
        val beløp: BigDecimal,
        val type: String,
        val fordel: String,
        val beskrivelse: String,
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
        val pensjonsgivendeInntekter: String,
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

    data class Lovreferanserad(
        val sykepengegrunnlagId: UUID,
        val lov: String,
        val paragraf: String,
        val avsnitt: Int?,
        val setning: Int?,
        val bokstav: String?,
        val iKraftFra: LocalDate,
        val utfall: String,
    )

    fun grunnlag(): List<Grunnlagsrad> =
        liste(
            """
            SELECT id, fødselsnummer, skjæringstidspunkt, rettighetskategori_id, opphav, opprettet,
                   (data ->> 'beregningsgrunnlag')::numeric AS beregningsgrunnlag,
                   (data ->> 'sykepengegrunnlag')::numeric AS sykepengegrunnlag,
                   (data ->> 'grunnbeløp')::numeric AS grunnbeløp,
                   (data ->> 'seksG')::numeric AS seks_g,
                   (data ->> 'er6GBegrenset')::boolean AS er_6g_begrenset
            FROM sykepengegrunnlag
            ORDER BY opprettet
            """,
        ) { row ->
            Grunnlagsrad(
                id = row.uuid("id"),
                fødselsnummer = row.string("fødselsnummer"),
                skjæringstidspunkt = row.localDate("skjæringstidspunkt"),
                rettighetskategoriId = row.uuid("rettighetskategori_id"),
                opphav = row.string("opphav"),
                beregningsgrunnlag = row.bigDecimal("beregningsgrunnlag"),
                sykepengegrunnlag = row.bigDecimal("sykepengegrunnlag"),
                grunnbeløp = row.bigDecimal("grunnbeløp"),
                seksG = row.bigDecimal("seks_g"),
                er6GBegrenset = row.boolean("er_6g_begrenset"),
                opprettet = row.instant("opprettet"),
            )
        }

    fun arbeidsgivere(): List<Arbeidsgiverrad> =
        liste("SELECT * FROM $ARBEIDSGIVERE a ORDER BY opprettet, rekkefølge") { row ->
            Arbeidsgiverrad(
                sykepengegrunnlagId = row.uuid("sykepengegrunnlag_id"),
                orgnummer = row.string("orgnummer"),
                inntektskilde = row.string("inntektskilde"),
                inntektsmeldingInternDokumentId = row.uuidOrNull("inntektsmelding_intern_dokument_id"),
                omregnetÅrsinntekt = row.bigDecimal("omregnet_årsinntekt"),
                aOrdningsoppslagId = row.uuidOrNull("a_ordningsoppslag_id"),
            )
        }

    fun skatteopplysninger(): List<Skatteopplysningsrad> =
        liste(
            """
            SELECT a.sykepengegrunnlag_id, a.orgnummer, s.*
            FROM $ARBEIDSGIVERE a,
                 ROWS FROM (jsonb_to_recordset(a.skatteopplysninger) AS (måned TEXT, beløp NUMERIC, type TEXT, fordel TEXT, beskrivelse TEXT))
                     WITH ORDINALITY AS s(måned, beløp, type, fordel, beskrivelse, rekkefølge)
            ORDER BY a.opprettet, a.rekkefølge, s.rekkefølge
            """,
        ) { row ->
            Skatteopplysningsrad(
                sykepengegrunnlagId = row.uuid("sykepengegrunnlag_id"),
                orgnummer = row.string("orgnummer"),
                måned = YearMonth.parse(row.string("måned")),
                beløp = row.bigDecimal("beløp"),
                type = row.string("type"),
                fordel = row.string("fordel"),
                beskrivelse = row.string("beskrivelse"),
            )
        }

    fun aOrdningsoppslag(): List<Pair<UUID, String>> =
        liste("SELECT sykepengegrunnlag_id, orgnummer FROM $ARBEIDSGIVERE a WHERE a_ordningen_hentet IS NOT NULL ORDER BY opprettet, rekkefølge") { row ->
            row.uuid("sykepengegrunnlag_id") to row.string("orgnummer")
        }

    fun aOrdningsoppslagrader(): List<AOrdningsoppslagrad> =
        liste("SELECT * FROM a_ordningsoppslag ORDER BY hentet") { row ->
            AOrdningsoppslagrad(
                id = row.uuid("id"),
                fødselsnummer = row.string("fødselsnummer"),
                førsteMåned = YearMonth.from(row.localDate("første_måned")),
                sisteMåned = YearMonth.from(row.localDate("siste_måned")),
                ainntektsfilter = row.string("ainntektsfilter"),
                svar = row.string("svar"),
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
        liste("SELECT sykepengegrunnlag_id, inntektsmelding_hentet FROM $ARBEIDSGIVERE a ORDER BY opprettet, rekkefølge") { row ->
            row.uuid("sykepengegrunnlag_id") to Instant.parse(row.string("inntektsmelding_hentet"))
        }

    fun selvstendiginntekter(): List<Selvstendigrad> =
        liste(
            """
            SELECT id, (data -> 'selvstendig' ->> 'søknadInternDokumentId')::uuid AS søknad_intern_dokument_id,
                   data -> 'selvstendig' ->> 'søknadHentet' AS søknad_hentet,
                   data -> 'selvstendig' -> 'pensjonsgivendeInntekter' AS pensjonsgivende_inntekter,
                   (data -> 'selvstendig' ->> 'fastsattÅrsinntekt')::numeric AS fastsatt_årsinntekt
            FROM sykepengegrunnlag
            WHERE jsonb_typeof(data -> 'selvstendig') = 'object'
            ORDER BY opprettet
            """,
        ) { row ->
            Selvstendigrad(
                sykepengegrunnlagId = row.uuid("id"),
                søknadInternDokumentId = row.uuid("søknad_intern_dokument_id"),
                søknadHentet = Instant.parse(row.string("søknad_hentet")),
                pensjonsgivendeInntekter = row.string("pensjonsgivende_inntekter"),
                fastsattÅrsinntekt = row.bigDecimal("fastsatt_årsinntekt"),
            )
        }

    fun søknader(): List<Søknadsrad> =
        liste("SELECT * FROM soknad ORDER BY søknad_intern_dokument_id") { row ->
            Søknadsrad(internDokumentId = row.uuid("søknad_intern_dokument_id"), søknadId = row.uuid("søknad_id"))
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

    fun lovreferanser(): List<Lovreferanserad> =
        liste(
            """
            SELECT g.id, l.*
            FROM sykepengegrunnlag g,
                 ROWS FROM (jsonb_to_recordset(g.data -> 'lovreferanser')
                     AS (lov TEXT, paragraf TEXT, avsnitt INT, setning INT, bokstav TEXT, "iKraftFra" DATE, utfall TEXT))
                     WITH ORDINALITY AS l(lov, paragraf, avsnitt, setning, bokstav, i_kraft_fra, utfall, rekkefølge)
            ORDER BY g.opprettet, l.rekkefølge
            """,
        ) { row ->
            Lovreferanserad(
                sykepengegrunnlagId = row.uuid("id"),
                lov = row.string("lov"),
                paragraf = row.string("paragraf"),
                avsnitt = row.intOrNull("avsnitt"),
                setning = row.intOrNull("setning"),
                bokstav = row.stringOrNull("bokstav"),
                iKraftFra = row.localDate("i_kraft_fra"),
                utfall = row.string("utfall"),
            )
        }

    private const val ARBEIDSGIVERE = """
        (SELECT g.id AS sykepengegrunnlag_id, g.opprettet, e.rekkefølge, a.orgnummer, a.inntektskilde,
                a."inntektsmeldingInternDokumentId" AS inntektsmelding_intern_dokument_id,
                a."inntektsmeldingHentet" AS inntektsmelding_hentet, a."aOrdningenHentet" AS a_ordningen_hentet,
                a."aOrdningsoppslagId" AS a_ordningsoppslag_id,
                a."omregnetÅrsinntekt" AS omregnet_årsinntekt, a.skatteopplysninger
         FROM sykepengegrunnlag g,
              jsonb_array_elements(g.data -> 'arbeidsgivere') WITH ORDINALITY AS e(arbeidsgiver, rekkefølge),
              jsonb_to_record(e.arbeidsgiver) AS a(orgnummer TEXT, inntektskilde TEXT, "inntektsmeldingInternDokumentId" UUID,
                                                   "inntektsmeldingHentet" TEXT, "aOrdningenHentet" TEXT, "aOrdningsoppslagId" UUID, "omregnetÅrsinntekt" NUMERIC,
                                                   skatteopplysninger JSONB))
    """

    fun lagreGrunnlagFraBackfill(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
        opphav: Opphav,
    ): UUID {
        val id = UUID.randomUUID()
        sessionOf(dataSource).use { session ->
            session.run(
                queryOf(
                    """
                    INSERT INTO sykepengegrunnlag (id, fødselsnummer, skjæringstidspunkt, rettighetskategori_id, opphav, data)
                    VALUES (:id, :fodselsnummer, :skjaeringstidspunkt, :rettighetskategoriId, :opphav, '{}'::jsonb)
                    """,
                    mapOf(
                        "id" to id,
                        "fodselsnummer" to fødselsnummer,
                        "skjaeringstidspunkt" to skjæringstidspunkt,
                        "rettighetskategoriId" to UUID.randomUUID(),
                        "opphav" to opphav.name,
                    ),
                ).asUpdate,
            )
        }
        return id
    }

    fun overskrivLagretFelt(
        sykepengegrunnlagId: UUID,
        sti: String,
        verdi: String,
    ) {
        sessionOf(dataSource).use { session ->
            session.run(
                queryOf(
                    "UPDATE sykepengegrunnlag SET data = jsonb_set(data, CAST(:sti AS text[]), CAST(:verdi AS jsonb)) WHERE id = :id",
                    mapOf("id" to sykepengegrunnlagId, "sti" to sti, "verdi" to verdi),
                ).asUpdate,
            )
        }
    }

    private fun <T> liste(
        @Language("PostgreSQL") sql: String,
        mapper: (kotliquery.Row) -> T,
    ): List<T> =
        sessionOf(dataSource).use { session ->
            session.run(queryOf(sql).map(mapper).asList)
        }
}
