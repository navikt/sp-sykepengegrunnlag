-- Sammenligningen i skyggedrift slår opp det nyeste grunnlaget for fødselsnummeret og skjæringstidspunktet.
CREATE INDEX idx_sykepengegrunnlag_fødselsnummer_skjæringstidspunkt ON sykepengegrunnlag (fødselsnummer, skjæringstidspunkt);
