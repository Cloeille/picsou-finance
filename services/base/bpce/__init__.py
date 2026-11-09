"""The BPCE online-banking client shared by Caisse d'Epargne and Banque Populaire.

Browser login (identifier, virtual keypad, Sécur'Pass), session replay of the
OAuth2 PKCE + SAML chain, and the read-only accounts import. What differs per
bank is a frozen `site.Site`; each sidecar keeps its own routes.
"""
