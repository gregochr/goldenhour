### Fixed — terms and privacy contradicted each other on Stripe

`terms.html` section 5 asserted "Payment is processed by Stripe" as present fact, while
`privacy.html` said "*When* Stripe is integrated for Pro subscriptions, Stripe *will*
process your payment card details directly". With Pro's price now stated as TBC, privacy
was the accurate one — and a contractual representation about who handles payment card
details should not be readable two ways.

Terms now carries privacy's sentence verbatim, so the two pages are byte-identical on
this point rather than merely consistent today. That also adopts privacy's stronger
commitment: *never see or store your full card number*, rather than terms' weaker "does
not store your payment card details".
