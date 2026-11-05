"""What differs between the BPCE banks served by this package: one frozen `Site`.

Caisse d'Epargne and Banque Populaire run the same login platform (icgauth,
virtual keypad, Sécur'Pass), the same OAuth2 PKCE + SAML chain and the same
data API, on their own hosts. Every module of this package reads the hosts, the
optional second OAuth apps and the region picker from the `Site` it is handed at
call time; nothing bank-specific is decided at import time.
"""

from dataclasses import dataclass, field


@dataclass(frozen=True)
class ChainProfile:
    """Which token a replay mints: the captured authorize query, with overrides.

    `overrides` replaces captured parameters, `drop` removes them. PKCE and the
    nonce are always fresh, whatever the profile.
    """

    name: str
    overrides: tuple[tuple[str, str], ...] = ()
    drop: tuple[str, ...] = ()

    def authorize_params(self, captured: dict[str, str]) -> dict[str, str]:
        params = {k: v for k, v in captured.items() if k not in self.drop}
        params.update(self.overrides)
        return params


@dataclass(frozen=True)
class HomeLoanApp:
    """A home-loan app minting its own token on its own authorization server."""

    chain: ChainProfile
    authorize_url: str
    token_url: str
    # The data host the loan detail is read from.
    base_url: str


@dataclass(frozen=True)
class PerApp:
    """The PER insurer app: its token chain, the bank gateway and the insurer host."""

    chain: ChainProfile
    gateway_host: str
    insurer_host: str


# The product families each optional app imports; without the app they are
# listed in `unsupported` (id and family code only) and never fetched.
PERSONAL_LOAN_FAMILY = ("6", "12")
HOME_LOAN_FAMILY = ("6", "13")
PER_FAMILY = ("4", "05")


@dataclass(frozen=True)
class Site:
    # Allow-list and session cookies: https `*.<domain>` (plus `extra_hosts`).
    domain: str
    login_url: str
    # The client space the login lands on once approved, and where the authorize
    # query the replay needs is captured.
    client_host: str
    authorize_url: str
    token_url: str
    # Synthesis and PFM transactions.
    data_base_url: str
    # Exact hosts outside `*.<domain>` (never a wildcard).
    extra_hosts: frozenset[str] = frozenset()
    client_path_prefix: str = "/espace-client/"
    # The establishment picker of the identifier page, as (request code, exact option
    # text). Empty: the page has none and `/initiate` takes no region.
    regions: tuple[tuple[str, str], ...] = ()
    # A cookie-consent button that may cover the identifier page; clicked if visible.
    cookie_banner_button: str | None = None
    # Whether the identifier may hold ASCII letters as well as digits. Banque Populaire
    # identifiers do (observed live 2026-11-05: a letter then seven digits).
    alphanumeric_identifier: bool = False
    # Whether the cards of a contract (`augmentedCards`) become accounts. Off for
    # Banque Populaire: its cards are not imported.
    cards: bool = True
    personal_loan: ChainProfile | None = None
    home_loan: HomeLoanApp | None = None
    per: PerApp | None = None
    _region_labels: dict[str, str] = field(init=False, repr=False, compare=False)

    def __post_init__(self) -> None:
        object.__setattr__(self, "_region_labels", dict(self.regions))

    def region_label(self, code: object) -> str | None:
        """The exact option text of a region code, or None for an unknown code."""
        return self._region_labels.get(code) if isinstance(code, str) else None

    @property
    def unsupported_families(self) -> frozenset[tuple[str, str]]:
        """Families this site reads no detail for: always unsupported, never fetched."""
        skipped = set()
        if self.personal_loan is None:
            skipped.add(PERSONAL_LOAN_FAMILY)
        if self.home_loan is None:
            skipped.add(HOME_LOAN_FAMILY)
        if self.per is None:
            skipped.add(PER_FAMILY)
        return frozenset(skipped)
