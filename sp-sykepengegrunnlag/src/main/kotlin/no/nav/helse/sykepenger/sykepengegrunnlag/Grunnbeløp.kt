package no.nav.helse.sykepenger.sykepengegrunnlag

import java.math.BigDecimal
import java.time.LocalDate
import java.time.Month.APRIL
import java.time.Month.DECEMBER
import java.time.Month.JANUARY
import java.time.Month.JULY
import java.time.Month.MAY
import java.time.Month.OCTOBER
import java.time.Month.SEPTEMBER
import kotlin.math.roundToInt

/**
 * Kopiert fra `Grunnbeløp` i Spleis. Et nytt grunnbeløp må legges inn begge steder så lenge begge beregner sykepengegrunnlaget.
 */
internal object Grunnbeløp {
    private val grunnbeløp =
        listOf(
            Historisk(136549, LocalDate.of(2026, MAY, 1)),
            Historisk(130160, LocalDate.of(2025, MAY, 1)),
            Historisk(124028, LocalDate.of(2024, MAY, 1)),
            Historisk(118620, LocalDate.of(2023, MAY, 1)),
            Historisk(111477, LocalDate.of(2022, MAY, 1)),
            Historisk(106399, LocalDate.of(2021, MAY, 1)),
            Historisk(101351, LocalDate.of(2020, MAY, 1), virkningsdato = LocalDate.of(2020, SEPTEMBER, 21)),
            Historisk(99858, LocalDate.of(2019, MAY, 1)),
            Historisk(96883, LocalDate.of(2018, MAY, 1)),
            Historisk(93634, LocalDate.of(2017, MAY, 1)),
            Historisk(92576, LocalDate.of(2016, MAY, 1)),
            Historisk(90068, LocalDate.of(2015, MAY, 1)),
            Historisk(88370, LocalDate.of(2014, MAY, 1)),
            Historisk(85245, LocalDate.of(2013, MAY, 1)),
            Historisk(82122, LocalDate.of(2012, MAY, 1)),
            Historisk(79216, LocalDate.of(2011, MAY, 1)),
            Historisk(75641, LocalDate.of(2010, MAY, 1)),
            Historisk(72881, LocalDate.of(2009, MAY, 1)),
            Historisk(70256, LocalDate.of(2008, MAY, 1)),
            Historisk(66812, LocalDate.of(2007, MAY, 1)),
            Historisk(62892, LocalDate.of(2006, MAY, 1)),
            Historisk(60699, LocalDate.of(2005, MAY, 1)),
            Historisk(58778, LocalDate.of(2004, MAY, 1)),
            Historisk(56861, LocalDate.of(2003, MAY, 1)),
            Historisk(54170, LocalDate.of(2002, MAY, 1)),
            Historisk(51360, LocalDate.of(2001, MAY, 1)),
            Historisk(49090, LocalDate.of(2000, MAY, 1)),
            Historisk(46950, LocalDate.of(1999, MAY, 1)),
            Historisk(45370, LocalDate.of(1998, MAY, 1)),
            Historisk(42500, LocalDate.of(1997, MAY, 1)),
            Historisk(41000, LocalDate.of(1996, MAY, 1)),
            Historisk(39230, LocalDate.of(1995, MAY, 1)),
            Historisk(38080, LocalDate.of(1994, MAY, 1)),
            Historisk(37300, LocalDate.of(1993, MAY, 1)),
            Historisk(36500, LocalDate.of(1992, MAY, 1)),
            Historisk(35500, LocalDate.of(1991, MAY, 1)),
            Historisk(34100, LocalDate.of(1990, DECEMBER, 1)),
            Historisk(34000, LocalDate.of(1990, MAY, 1)),
            Historisk(32700, LocalDate.of(1989, APRIL, 1)),
            Historisk(31000, LocalDate.of(1988, APRIL, 1)),
            Historisk(30400, LocalDate.of(1988, JANUARY, 1)),
            Historisk(29900, LocalDate.of(1987, MAY, 1)),
            Historisk(28000, LocalDate.of(1986, MAY, 1)),
            Historisk(26300, LocalDate.of(1986, JANUARY, 1)),
            Historisk(25900, LocalDate.of(1985, MAY, 1)),
            Historisk(24200, LocalDate.of(1984, MAY, 1)),
            Historisk(22600, LocalDate.of(1983, MAY, 1)),
            Historisk(21800, LocalDate.of(1983, JANUARY, 1)),
            Historisk(21200, LocalDate.of(1982, MAY, 1)),
            Historisk(19600, LocalDate.of(1981, OCTOBER, 1)),
            Historisk(19100, LocalDate.of(1981, MAY, 1)),
            Historisk(17400, LocalDate.of(1981, JANUARY, 1)),
            Historisk(16900, LocalDate.of(1980, MAY, 1)),
            Historisk(16100, LocalDate.of(1980, JANUARY, 1)),
            Historisk(15200, LocalDate.of(1979, JANUARY, 1)),
            Historisk(14700, LocalDate.of(1978, JULY, 1)),
            Historisk(14400, LocalDate.of(1977, DECEMBER, 1)),
            Historisk(13400, LocalDate.of(1977, MAY, 1)),
            Historisk(13100, LocalDate.of(1977, JANUARY, 1)),
            Historisk(12100, LocalDate.of(1976, MAY, 1)),
            Historisk(11800, LocalDate.of(1976, JANUARY, 1)),
            Historisk(11000, LocalDate.of(1975, MAY, 1)),
            Historisk(10400, LocalDate.of(1975, JANUARY, 1)),
            Historisk(9700, LocalDate.of(1974, MAY, 1)),
            Historisk(9200, LocalDate.of(1974, JANUARY, 1)),
            Historisk(8500, LocalDate.of(1973, JANUARY, 1)),
            Historisk(7900, LocalDate.of(1972, JANUARY, 1)),
            Historisk(7500, LocalDate.of(1971, MAY, 1)),
            Historisk(7200, LocalDate.of(1971, JANUARY, 1)),
            Historisk(6800, LocalDate.of(1970, JANUARY, 1)),
            Historisk(6400, LocalDate.of(1969, JANUARY, 1)),
            Historisk(5900, LocalDate.of(1968, JANUARY, 1)),
            Historisk(5400, LocalDate.of(1967, JANUARY, 1)),
        )

    fun gjeldende(
        dato: LocalDate,
        virkningFra: LocalDate,
    ): BigDecimal {
        val virkningsdato = maxOf(dato, virkningFra)
        return grunnbeløp
            .filter { virkningsdato >= it.virkningsdato && dato >= it.gyldigFra }
            .maxByOrNull { it.virkningsdato }
            ?.beløp
            ?: throw NoSuchElementException("Finner ingen grunnbeløp etter $dato")
    }

    /** Kopiert fra `Grunnbeløp.virkningstidspunktFor` i Spleis: datoen grunnbeløpet med dette beløpet gjelder fra. */
    fun virkningstidspunktFor(beløp: BigDecimal): LocalDate =
        grunnbeløp.find { it.beløp.compareTo(beløp) == 0 }?.virkningsdato
            ?: throw IllegalArgumentException("$beløp er ikke et grunnbeløp")

    /** Gjennomsnittlig grunnbeløp i året, vektet med antall måneder hvert grunnbeløp gjaldt. Regnes med `Double` som i Spleis. */
    fun snitt(år: Int): Int =
        grunnbeløp
            .sumOf { it.beløp.toDouble() * it.antallMånederGyldigI(år) / 12 }
            .roundToInt()

    private class Historisk(
        beløp: Int,
        val gyldigFra: LocalDate,
        val virkningsdato: LocalDate = gyldigFra,
    ) {
        val beløp: BigDecimal = beløp.toBigDecimal()

        fun antallMånederGyldigI(år: Int): Int {
            val gyldigTil =
                grunnbeløp
                    .filter { it.gyldigFra > gyldigFra }
                    .minOfOrNull { it.gyldigFra }
                    ?.minusDays(1)
                    ?: gyldigFra.withMonth(12).withDayOfMonth(31)
            val fom = maxOf(gyldigFra, LocalDate.of(år, JANUARY, 1))
            val tom = minOf(gyldigTil, LocalDate.of(år, DECEMBER, 31))
            if (fom > tom) return 0
            return tom.monthValue - fom.monthValue + 1
        }
    }
}
