package no.nav.helse.sykepenger.sykepengegrunnlag

internal interface SykepengegrunnlagRepository {
    fun lagre(
        grunnlag: Sykepengegrunnlag,
        inntektsmeldinger: List<Inntektsmelding>,
    )
}
