# ADR: BNP Paribas and Hello bank! through a dedicated Camoufox sidecar

> Date: 2026-11-09
> Status: 🟡 Proposed - implemented, partly verified against one real account (issue #306)

## Context

Neither bank offers a PSD2 path that covers savings, securities and life insurance. The bank's own web
client does. Its login is a PingFederate OIDC page protected by Akamai Bot Manager, ThreatMetrix and a
nonce-based CSP, with a shuffled 10-button keypad whose clicked positions are written by the page's
JavaScript. A live recon of the public pages (no login) corrected the open-source woob module on the login
hosts, the keypad (text buttons, not images), the form fields, the error channel (`error_code`) and the
session-death signal (`codeRetour: "-10"`, a string).

## Decision

1. **A sidecar, `services/bnp-auth`, on the Camoufox base** (like Amex), because the login only works
   inside a real browser. It types the customer number, reads the ten key labels, and clicks the keys the
   member chose by position. It never synthesises the login POST.
2. **The member clicks the keypad in Picsou**; positions are the only thing that leaves the UI, and the
   backend and sidecar validate them (six integers in 0..9).
3. **Both second factors are implemented** (Cle Digitale approval and SMS code) because which one applies
   is unverified; the sidecar classifies the page and stops with `MFA_TYPE_UNSUPPORTED` when it cannot.
4. **Cookies only, encrypted, in `sidecar_session`** (migration V150, rebuilt from V149). Nothing the
   member typed or clicked is stored.
5. **Strict allow-list of six exact hosts plus the `.bnpparibas` dotBrand TLD**, per brand, every request and redirect hop; an unknown host on a
   navigation, a body-carrying request or a foreign frame = stop and log the host name only. Two
   refinements, because the login page itself loads ThreatMetrix tags: sub-resources the bank's page loads from ThreatMetrix (`*.online-metrix.net`, dot-bounded suffix, https,
   no credentials) are allowed, because an empty `pf.idTMX` would likely get the login refused; other
   passive third-party sub-resources (analytics) are aborted without stopping the login. Main-frame
   navigations, the form post and anything that could carry the identifier or code stay on the exact list.
   The WebRTC beacon stays off. Every stop logs a fixed stage name, and a stop after the login submit one
   structure-only line (host and path with digits masked, query keys, title length, form and field names).
   The second refinement: every https host under `.bnpparibas` (dot boundary, port 443) is a brand host for both
   brands, navigations included, because the post-login redirect chain goes through other BNP Paribas hosts
   (`mabanqueprivee.bnpparibas`, seen in a first owner-assisted run). `.bnpparibas` is a dotBrand gTLD: the IANA
   root zone database lists BNP Paribas as the sole manager of the TLD
   (https://www.iana.org/domains/root/db/bnpparibas.html, read 2026-11-09), so no third party can register a name
   under it. `hellobank.fr` is an ordinary domain and stays on its three exact hosts.
   The third refinement: the calls of the bank's cookie-consent vendor (OneTrust, three exact hosts:
   `geolocation.onetrust.com`, `cookies-data.onetrust.io`, `bnp-privacy.my.onetrust.com`) are aborted without
   stopping the login, body or not. Two of them are POSTs sent on every landing with a new profile, so "a
   third-party request with a body stops the attempt" stopped every login once the password was accepted
   (owner-assisted run, 2026-11-09). The hosts are exact because `my.onetrust.com` is shared by every
   OneTrust customer. Known vendors are one table, host or suffix -> allow or ignore; any other third party
   keeps the fail-closed rule.
6. **Fail closed**: unknown product kinds are listed, incomplete snapshots never replace data, unexpected
   pages stop the attempt.

## Consequences

- The first owner-assisted live run (2026-11-09) needed changes to `bank.py` and `parsers.py` only, not to
  the backend contract: the consent-vendor rule above, the dash date format of the operations, and what
  "signed in" means (`InfosClient` answers `codeRetour` 0 **with** data; an idled-out session answers 0
  without). The second-factor page, Hello bank! and the securities, life-insurance and loan endpoints are
  still unverified.
- A web session lasts between 12 and 24 idle minutes (same run) and nothing but a new login renews it, so a
  sync that runs later than that needs the member again. Unattended daily sync is not possible with this
  connector as designed.
- Akamai may refuse the sidecar's network (403 was seen from a datacenter IP). That is reported as
  `UPSTREAM_UNAVAILABLE`; no evasion is attempted.
- A Camoufox sidecar is heavier than an httpx one; it shares the base image with Amex and Revolut.
- Status moves to Accepted only after a real-account run by the maintainer or an owner.
