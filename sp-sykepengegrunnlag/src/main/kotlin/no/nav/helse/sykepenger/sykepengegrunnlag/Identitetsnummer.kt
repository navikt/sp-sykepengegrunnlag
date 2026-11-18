package no.nav.helse.sykepenger.sykepengegrunnlag

@JvmInline
internal value class Identitetsnummer private constructor(
    val verdi: String,
) {
    companion object {
        fun fraString(identitetsnummer: String): Identitetsnummer {
            require(identitetsnummer.matches(Regex("\\d{11}"))) { "Identitetsnummer må bestå av nøyaktig 11 siffer" }
            return Identitetsnummer(identitetsnummer)
        }
    }
}

@JvmInline
internal value class Organisasjonsnummer private constructor(
    val verdi: String,
) {
    companion object {
        fun fraString(organisasjonsnummer: String): Organisasjonsnummer {
            require(organisasjonsnummer.matches(Regex("\\d{9}"))) { "Organisasjonsnummer må bestå av nøyaktig 9 siffer" }
            return Organisasjonsnummer(organisasjonsnummer)
        }
    }
}
