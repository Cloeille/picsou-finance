"""Synthetic pages shaped like the CIC customer site, with made-up names and amounts.

The markup keeps the classes, captions and nesting the parsers rely on, copied
from real pages; nothing else of them is kept.
"""

LOGOUT_LINK = '<a href="/fr/identification/deconnexion/deconnexion.cgi">Déconnexion</a>'

LOGIN_PAGE = """
<html><body>
<form method="post" autocomplete="off" name="bloc_ident" id="bloc_ident">
  <input type="text" id="_userid" name="_cm_user" value="" />
  <input type="hidden" name="flag" value="password">
  <input type="hidden" name="_charset_">
  <input type="password" name="_cm_pwd" id="_pwduser" />
</form>
</body></html>
"""

LOGIN_PAGE_WRONG_PASSWORD = LOGIN_PAGE.replace(
    "<body>",
    '<body><div class="blocmsg err"><p>Votre identifiant ou mot de passe est incorrect.</p></div>',
)


def account_row(index: int, title: str, number: str, amount: str) -> str:
    return f"""
<tr id="I0:ac.s1.A1:al.A:F_{index}.A1:T0" class="_c1 _c1">
<td colspan="1" class="_c1 decal1 _c1"><a href="https://www.cic.fr/fr/banque/mouvements.html?webid=0a1b2c{index}" target="_self">
<span><span class="_c1 ei_sdsf_title _c1">{title}</span> <span class="_c1 nowrap _c1">M JOHN DOE</span> <span>-</span>
<span class="_c1 nowrap doux _c1">{number}</span></span></a></td>
<td class="d nowrap "></td><td class="d nowrap ei_sdsf_solde"><span class="ei_sdsf_montant pos">{amount}</span></td>
<td class="ei_actionsblock"><button type="button" title="Actions liées au {title}">Actions</button></td>
</tr>"""


def accounts_table(category: str, rows: str) -> str:
    return f"""
<table id="I0:ac.T" class="_c1 ei_comptescontrats _c1 liste ei_cellspacing" style="width:100%;">
<caption class="invisible"><span class="invisible">Liste des comptes li&#233;s &#224; la cat&#233;gorie : {category}</span></caption>
<thead><tr class="_c1 ei_headList-invisible _c1"><th>Contrat</th><th>D&#233;bit</th><th>Cr&#233;dit</th><th>Actions</th></tr></thead>
<tbody>{rows}</tbody></table>"""


OTHER_CONTRACTS = """
<table class="_c1 ei_comptescontrats _c1 liste ei_cellspacing">
<caption class="invisible"><span class="invisible">Liste des comptes li&#233;s &#224; la cat&#233;gorie : Autres contrats li&#233;s</span></caption>
<tbody><tr id="x"><td><a href="https://www.cic.fr/fr/banque/lien_inter_sites_multibanque.html?action=access&amp;d=CICCMESPAR">XXXX1234 Épargne Retraite Entreprises</a></td><td></td></tr></tbody></table>"""

ACCOUNTS_PAGE = f"""
<html><body>{LOGOUT_LINK}
<form id="I0:P:F" action="/fr/banque/comptes-et-contrats.html?_tabi=I0&amp;_pid=AccountsAndContracts" method="post">
{accounts_table("Comptes courants", account_row(0, "COMPTE COURANT", "10000 000123456 01", "+1&nbsp;234,56 EUR"))}
{accounts_table("Livrets et comptes d&#39;épargne",
    account_row(1, "LIVRET A", "10000 000123456 02", "+5&nbsp;000,00 EUR")
    + account_row(2, "LIVRET DE DEVELOPPEMENT DURABLE SOLIDAIRE", "10000 000123456 03", "+800,00 EUR")
    + account_row(3, "PLAN EPARGNE LOGEMENT", "10000 000123456 04", "+3&nbsp;100,50 EUR"))}
{OTHER_CONTRACTS}
</form></body></html>"""

MOBILE_CONFIRMATION_PAGE = """
<html><body>
<form id="C:P:F" action="/fr/banque/validation.aspx?_tabi=C&amp;_pid=OtpValidationPage" method="post">
  <input name="$CPT" type="hidden" value="opaque-page-state" />
  <div id="C:inMobileAppMessage"><h2>Une demande de confirmation mobile a &#233;t&#233; transmise &#224; votre appareil.</h2>
  <p>D&#233;marrez votre application mobile CIC pour v&#233;rifier et confirmer cette op&#233;ration.</p></div>
</form>
<script type="text/javascript">
  $dv.O('C:P:polling', 'OtpPolling', {transactionId: '0123456789abcdef0123456789abcdef0123456789abcdef0', getTransactionState: true});
</script>
</body></html>
"""

SMS_CONFIRMATION_PAGE = """
<html><body>
<form id="C:P:F" action="/fr/banque/validation.aspx?_tabi=C&amp;_pid=OtpValidation" method="post">
  <div id="C:OTPDeliveryChannelText">Un code de confirmation vous a été envoyé par SMS au 06 XX XX XX 12.</div>
  <input type="hidden" name="otp_hidden" value="opaque-otp-state" />
  <input type="text" name="otp_password" maxlength="6" />
  <input type="submit" name="_FID_CheckOrDeliverBackup" value="Valider" />
  <input name="$CPT" type="hidden" value="opaque-page-state" />
</form>
</body></html>
"""

AUTHORITY_MANAGEMENT_PAGE = """
<html><body>
<form id="C:P:F" action="/fr/banque/migr_gestion_pouvoirs.html?_tabi=C" method="post">
  <p>Votre contrat de Banque à distance évolue et vous permet désormais de gérer électroniquement les pouvoirs.</p>
  <input name="$CPT" type="hidden" value="opaque-page-state" />
  <input type="submit" name="_FID_DoStopPaciAndRemind" value="Plus tard" />
</form>
</body></html>
"""


def transaction_state(state: str) -> str:
    return f'<?xml version="1.0" encoding="utf-8"?><root><transactionState>{state}</transactionState></root>'
