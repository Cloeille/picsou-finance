# banque-populaire-auth (browser login, session replay, accounts)

FastAPI sidecar for Banque Populaire. The bank runs the BPCE platform
Caisse d'Epargne runs, so everything but the routes and the bank's `Site`
(`main.SITE`) is the shared `bpce` package (`services/base/bpce`, shipped by the
base image) that `caisse-epargne-auth` uses too. Read
[`../caisse-epargne-auth/README.md`](../caisse-epargne-auth/README.md) for the
flow, the replay chain, the session state and the safety rules: they are the
same here. This file lists what differs and what was, or was not, observed.

## API

Same routes, codes and bodies as `caisse-epargne-auth`, except `POST /initiate`:

```
POST /initiate {"customerId": "<ASCII letters and digits>", "region": "<code>"}
```

The body is strict: an unknown field, a missing or unknown `region`, or a value of
the wrong type answers `400 {"detail": "INVALID_REQUEST"}` without echoing it.
`region` is one of the 12 establishments of the login page:

| code | exact option text |
| --- | --- |
| `ALSACE_LORRAINE_CHAMPAGNE` | `ALSACE LORRAINE CHAMPAGNE` |
| `AQUITAINE_CENTRE_ATLANTIQUE` | `AQUITAINE CENTRE ATLANTIQUE` |
| `AUVERGNE_RHONE_ALPES` | `AUVERGNE RHONE ALPES` |
| `BOURGOGNE_FRANCHE_COMTE` | `BOURGOGNE FRANCHE-COMTÉ` |
| `BRED` | `BRED` |
| `GRAND_OUEST` | `GRAND OUEST` |
| `MEDITERRANEE` | `MEDITERRANEE` |
| `NORD` | `NORD` |
| `OCCITANE` | `OCCITANE` |
| `RIVES_DE_PARIS` | `RIVES DE PARIS` |
| `SUD` | `SUD` |
| `VAL_DE_FRANCE` | `VAL DE FRANCE` |

`POST /keypad`, `POST /complete`, `POST /token-check` and `POST /accounts` are
unchanged. The container listens on port 8001.

## Site

| | |
| --- | --- |
| login | `https://www.icgauth.banquepopulaire.fr/se-connecter/sso?service=cyber` |
| client space | `www.banquepopulaire.fr`, `/espace-client/` |
| authorize / token | `https://www.as-ext-bad-ib.banquepopulaire.fr/api/oauth/v2/{authorize,token}` |
| data | `https://www.rs-ext-bad-ib.banquepopulaire.fr` (synthesis and PFM transactions, same paths and queries as Caisse d'Epargne) |
| allow-list | https `*.banquepopulaire.fr`, plus exactly `www.as-ext-bad-me.bpce.fr` and `www.rs-ext-bad-me.bpce.fr` (`extra_hosts`, no wildcard): no other `bpce.fr`, no `caisse-epargne.fr` host |
| session cookies | the `banquepopulaire.fr` domain family only |
| authorize / token, personal-loan app | the main hosts, client `4107d671-…`, scope `managePersonalLoanPrd readPersonalLoanPrd readUser`, redirect `/espace-gestion/pret-personnel/postMessage/index.html`, no `claims` (public config, not observed live) |
| home-loan app | `https://www.as-ext-bad-me.bpce.fr/api/oauth/v2/{authorize,token}`, client `873a10ce-…`, empty scope, redirect `/gestion-client/credit-immobilier/postMessage/index.html`, no `claims`; data `https://www.rs-ext-bad-me.bpce.fr` (public config, not observed live) |
| PER app | `espace-assurances-de-personnes.banquepopulaire.fr` (gateway `www.rs-ext-bad-ib.banquepopulaire.fr`) |

## Login

1. Open the login URL. A consent banner may cover the page: its
   `#no_consent_btn` ("Continuer sans accepter") is clicked when visible, and its
   absence is fine.
2. Pick the establishment: the trigger is the one
   `neo-select-simple button[role="combobox"]` of the identifier page (never matched
   by its numbered id), the option the one `neo-select-simple-option[role="option"]`
   whose trimmed text equals the region's label exactly. The picker must then show
   that label. A missing picker, no or several matching options, or a picker that
   does not show the choice stops the login with `502 UPSTREAM_FORMAT_CHANGED`:
   another establishment is never picked.
3. From here the Caisse d'Epargne flow, unchanged: identifier in
   `input[type="text"][id^="neo-input-"]`, the form's `Valider`, the 10
   `.keyboard-button` keys at `/se-connecter/icg/mot-de-passe`, the user's clicks,
   `Valider`, the Sécur'Pass wait (`(modal:icg/cloudcard)`), the client space.
4. The authorize query is captured from the first authorize request made from the
   client space. The login pages' own authorize (another client, implicit flow, made
   from icgauth) is ignored.

## Accounts

Imported: the current account, `3/01` Livret A, `3/01` Livret Jeune
(`LIVRET_JEUNE`, told apart by a `productLabel` with the word `JEUNE`), `3/28` LDDS,
`3/02` Compte sur Livret (`SAVINGS`, provisional) and `3/04` PEL (`PEL`, provisional),
all with their PFM transactions, plus `4/09` term deposit (`TERM_DEPOSIT`, provisional)
and `4/10` cooperative shares (`COOPERATIVE_SHARES`, provisional) from their synthesis
balance (without PFM transactions). A `3/01` label that names a LEP (`LEP`,
`EPARGNE POPULAIRE`) is imported as `LEP`.

Cards are not imported (`Site.cards=False`): the `augmentedCards` list of a
contract is checked for shape, then ignored. The list of a Compte sur Livret, a PEL
or a LEP is not checked at all (a savings book has no card), so a malformed value
there is accepted. No card account, no card operation read, nothing listed in
`unsupported` (a card is not a contract).

Personal loans `6/12` and home loans `6/13` are imported as `LOAN`, each reading its detail
with its own OAuth token minted on demand (like Caisse d'Epargne). `LOAN` is a provisional
kind: the clients come from the bank's public web app configuration and were never
observed in a live sync, so a refusal of a loan app (token or detail, even a 401) never
ends the session, and a loan whose detail cannot be read is imported with its synthesis
balance and `loan: null`. The log says which loan app and which field:
`loan detail unreadable: product=… code=… rejected=<field path>`,
`loan detail unavailable: product=… code=…`, `loan imported without detail: product=…`.

Listed in `unsupported` (id, family and sub-family code only), never read, never fatal:
the revolving credit `2/11` ("Credit Renouvelable Facelia", null balance), and any family
not listed above. The PER `4/05` is read from the insurer app (minting a third OAuth
token on demand, like Caisse d'Epargne; pinned to `PERIB`).

## Observed live (2026-11-05, 2026-11-08) and not

Observed on a Rives de Paris account (2026-11-05) and a Val de France account (2026-11-08):

- the entry URL (`service=dei` lands on `/se-connecter/erreur`), the establishment
  picker and its 12 options, the consent banner button, the identifier, keypad,
  `Valider`, carousel `Suivant` and Sécur'Pass selectors;
- the authorize request of the client space (its own `client_id`, redirect
  `https://www.banquepopulaire.fr/espace-client/postMessage/index.html`, empty scope,
  `cdetab`, `claims`, `bpcesta`, `login_hint`, `display`, PKCE), the SAMLRequest on
  icgauth with its 303, `consume` and `token`: the chain Caisse d'Epargne replays;
- the synthesis and transactions endpoints and their envelopes, a current account
  with an immediate-debit card, a Livret A and a Livret Jeune both `3/01`, an LDDS
  `3/28`, a Compte sur Livret `3/02` and a PEL `3/04` (both with `contractPfmId`),
  a revolving credit `2/11`, and `4/05` (PER), `4/09` (term deposit) and `4/10`
  (cooperative shares) without `contractPfmId`.

Run live end to end on 2026-11-05 (Rives de Paris) and 2026-11-08 (Val de France):
region, identifier, keypad, Sécur'Pass, session replay and `/accounts`. A wrong
identifier or password was seen too: the bank shows a keypad for any identifier,
then a modal "Identifiant ou mot de passe erroné", read as `INVALID_CREDENTIALS`.

Not observed: the revolving-credit detail, LEP, and the personal and home loans (`6/12`, `6/13`): their clients were read from the public web app configuration on 2026-11-14, not seen in a live sync.

## Tests

```
cd services/banque-populaire-auth && APP_SIDECAR_API_KEY=test-key \
  PYTHONPATH=../base "$TMPDIR/ce-venv/bin/python" -m unittest discover -v
```

(the virtual environment of the Caisse d'Epargne README). Stdlib `unittest` only,
synthetic data only. The login runs against the shared fake Playwright
(`bpce/testing.py`, mounted read-only in CI); `test_region_selectors.py` drives the
picker selectors in real Chromium on a synthetic page; the data calls use
`httpx.MockTransport`. `test_dockerfile.py` checks the image ships every module
`main.py` needs.
