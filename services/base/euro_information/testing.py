"""Fixtures both sidecars' fake sites share. Only tests import this module."""


def transaction_state(state: str) -> str:
    return f'<?xml version="1.0" encoding="utf-8"?><root><transactionState>{state}</transactionState></root>'


def wrong_password_page(login_page: str) -> str:
    return login_page.replace(
        "<body>",
        '<body><div class="blocmsg err"><p>Votre identifiant ou mot de passe est incorrect.</p></div>',
    )
