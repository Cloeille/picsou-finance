"""Synthetic pages shaped like the saver's site, with made-up names and amounts.

The markup keeps the classes, ids and nesting the parsers rely on, copied from
real pages; nothing else of them is kept.
"""

from euro_information.testing import transaction_state, wrong_password_page  # noqa: F401

LOGOUT_LINK = '<a href="/epargne-salariale/fr/epargnants/deconnexion/deconnexion.html">Déconnexion</a>'

LOGIN_PAGE = """
<html><body>
<form method="post" autocomplete="off" name="bloc_ident" id="bloc_ident">
  <input type="text" id="_userid" name="_cm_user" value="" />
  <input type="hidden" name="flag" value="password">
  <input type="hidden" name="_charset_">
  <input type="password" name="_cm_pwd" id="_pwduser" />
</form>
<form method="post" name="bloc_ident_safetrans" id="bloc_ident_safetrans">
  <input type="hidden" name="flag" value="safetrans">
</form>
</body></html>
"""

LOGIN_PAGE_WRONG_PASSWORD = wrong_password_page(LOGIN_PAGE)


def fund_row(index: int, label: str, value: str, gain: str, sign: str = "pos") -> str:
    return f"""
<tr id="I0:P3:F_{index}.L10:root">
  <td class="i eir_tblcaption">
    <a id="I0:P3:F_{index}.L0" href="#" class="_c1 es_fund-clickable _c1 needscript">{label}</a>
    <input type="submit" value="{label}" name="_FID_GoFundSummary_selectedFundIndex:{index}" class="hideifscript" />
  </td>
  <td class="i eir_tblline d mntt"><div class="eir_tblcnt">
    <span class="eir_tblcnt_header"><span aria-hidden="true">Montant total estim&#233;</span></span>
    <span class="_c1 nowrap _c1">{value} &#8364; <a href="#1" class="renvoi"><span class="ei_sronly">Note de bas de page :</span> 1</a></span>
  </div></td>
  <td class="i eir_tblline d mntt"><div class="eir_tblcnt">
    <span class="eir_tblcnt_header"><span aria-hidden="true">Plus ou moins values</span></span>
    <span class="ei_hdet_itemtext ei_iblock"><span class="_c1 {sign} nowrap es_pvl _c1">{gain} %</span></span>
  </div></td>
  <td class="i a_actions nowrap nowrap c"><div class="ei_actionsblock"></div></td>
</tr>"""


def plan_table(label: str, total: str, rows: str) -> str:
    return f"""
<table class="_c1 tb_accordeon _c1 liste ei_cellspacing" style="width:100%;">
<caption class="invisible"><span class="invisible">Situation d&#233;taill&#233;e - {label}</span></caption>
<thead><tr class="_c1 invisible _c1"><th scope="col">Plan</th><th>Disponible</th><th>Bloqu&#233;</th><th>Montant total estim&#233;</th><th>Bouton d&#233;pliant</th></tr></thead>
<tbody>
<tr id="I0:P3:T0" class="_c1 ei_row_show pointer _c1 ___TrExpander">
  <th class="rupture _c1 nowrap _c1"><div class="_c1 ei_flex _c1"><span aria-hidden="true" class="ei_gly_peg"></span> {label} </div></th>
  <td class="datarupture i"><div><span>Disponible</span><br /><span class="_c1 em nowrap _c1">0,00 &#8364;</span></div></td>
  <td class="datarupture i"><div><span>Bloqu&#233;</span><br /><span class="_c1 em nowrap _c1">{total} &#8364;</span></div></td>
  <td class="datarupture i _c1 eir_td es_gp_d _c1"><span>Montant total estim&#233;</span><br /><span class="_c1 em nowrap _c1">{total} &#8364;</span></td>
  <td class="datarupture i ei_tdexpander"><a href="javascript:void(0);" class="masquer">Masquer</a></td>
</tr>
<tr id="I0:P3:T1"><td colspan="5" class="decalrupt1 i">
<table id="I0:P3:L:root" class="eir_xs_listtable liste ei_cellspacing" style="width:100%;">
<caption class="invisible">Liste des suports</caption>
<thead><tr><th scope="col">Nom du support</th><th>Montant total estim&#233;</th><th>Plus ou moins values</th><th>Actions</th></tr></thead>
<tbody>{rows}</tbody>
</table>
<table class="liste ei_cellspacing"><caption>Echéances du support</caption>
<tr><th>Echéance</th><th>Montant estimé</th></tr><tr><td>Disponibilités 01/06/2030</td><td>9,99 €</td></tr>
</table>
</td></tr>
</tbody>
</table>"""


PEG_ROWS = (
    fund_row(0, "FONDS EQUILIBRE SOLIDAIRE", "1&nbsp;000,00", "+ 10,00")
    + fund_row(1, "FONDS ACTIONS MONDE", "500,50", "-2,50", sign="neg")
)


def situation_page(plans: str, *, logged: bool = True) -> str:
    return f"""
<html><head><style>table.tb_accordeon.liste table.liste tr td.eir_tblline {{background: #fff;}}</style></head>
<body>
<div class="_c1 ei_tpl_profil_content _c1">
  <p class="_c1 ei_profil_name _c1">M DOE <span>John</span></p>
  <p class="_c1 ei_profil_entrep _c1">ACME INDUSTRIES <span>12345</span></p>
</div>
{LOGOUT_LINK if logged else ""}
<form id="I0:P3:F" action="/epargne-salariale/fr/epargnants/mon-epargne/situation-financiere-detaillee/index.html?_tabi=I0&amp;_pid=FinancialSituation" method="post" class="_devb_act ___Form">
  <input name="Data_PegDetail_IsBlockExpanded" type="hidden" value="true" />
  <input name="$CPT" type="hidden" value="opaque-page-state" />
  {plans}
</form>
</body></html>"""


SITUATION_PAGE = situation_page(plan_table("Plan d&#39;&#233;pargne groupe", "1&nbsp;500,50", PEG_ROWS))


def fund_summary_page(index: int, code: str = "1234") -> str:
    base = (
        "/epargne-salariale/fr/epargnants/supports/synthese-du-support.html"
        f"?_tabi=C&amp;a_page_=support_detail&amp;_pid=FundSummary&amp;k_fundIndex={index}"
    )
    return f"""
<html><body>
<nav><a href="/epargne-salariale/fr/epargnants/mon-epargne/index.html">Mon &#233;pargne</a></nav>
{LOGOUT_LINK}
<ul id="C:T3:tabslist" class="ei_newtabs">
  <li class="sel ei_tabslist__item"><span>Synth&#232;se</span></li>
  <li class="ei_tabslist__item"><a id="C:T1:link" href="{base}&amp;_fid=GoMyAsset">Mon &#233;pargne</a></li>
  <li class="ei_tabslist__item"><a id="C:T2:link" href="{base}&amp;_fid=GoFundAlert">Mes alertes</a></li>
</ul>
<table class="fiche"><tbody>
<tr><th scope="row">Code du support</th><td>{code}</td></tr>
<tr><th scope="row">Niveau de risque</th><td>4 /7</td></tr>
<tr><th scope="row">Derni&#232;re valeur liquidative</th><td>20,0000 &#8364; au 22/10/2026</td></tr>
</tbody></table>
</body></html>"""


def fund_savings_page(value: str, gain: str, units: str, price: str) -> str:
    return f"""
<html><body>
{LOGOUT_LINK}
<table class="_c1 saisie eir_sm_tableinput _c1 fiche ei_cellspacing" style="width:100%;">
<caption class="invisible"><span class="invisible">Synth&#232;se</span></caption>
<tbody>
<tr><th scope="row">R&#233;partition</th><td class="_c1 nowrap _c1"><em>66,64 % de mon &#233;pargne est investie sur ce support</em></td></tr>
<tr><th scope="row">Montant estim&#233;</th><td class="_c1 mntt es_ges_strong nowrap _c1">{value} &#160; &#8364;</td></tr>
<tr><th scope="row">Dont plus ou moins value</th><td class="_c1 mntt _c1"><em><span class="_c1 es_pvl pos nowrap _c1">{gain} &#160; &#8364;</span> <span class="_c1 es_pvl pos _c1">(soit+10,00&#160;%)</span></em></td></tr>
<tr><th scope="row">Nombre de parts</th><td><em>{units}</em></td></tr>
<tr><th scope="row">Valeur de la part au 22/10/2026</th><td><em>{price} &#160; &#8364;</em></td></tr>
</tbody>
</table>
<table class="fiche ei_cellspacing"><thead><tr><th scope="col">Ech&#233;ance</th><th>Montant estim&#233;</th><th>Nombre de parts</th></tr></thead>
<tbody><tr><td scope="row">Disponibilit&#233;s 01/06/2030</td><td><span class="nowrap">{value} &#8364;</span></td><td>{units}</td></tr></tbody></table>
</body></html>"""


FUND_SAVINGS = {
    0: fund_savings_page("1&nbsp;000,00", "+90,91", "50,0000", "20,0000"),
    1: fund_savings_page("500,50", "-12,83", "25,0250", "20,0000"),
}

MOBILE_CONFIRMATION_PAGE = """
<html><body>
<form id="C:P:F" action="/epargne-salariale/fr/epargnants/premiers-pas/authentification-forte/index.html?_tabi=C&amp;_pid=AuthentForte" method="post">
  <input name="$CPT" type="hidden" value="opaque-page-state" />
  <input type="submit" name="_FID_DoValidate" value="Continuer" class="hideifscript" />
  <div id="C:inMobileAppMessage"><h2>Une demande de confirmation mobile a &#233;t&#233; transmise &#224; votre appareil.</h2>
  <p>D&#233;marrez votre application mobile pour v&#233;rifier et confirmer cette op&#233;ration.</p></div>
</form>
<script type="text/javascript">
  $dv.O('C:P:polling', 'OtpPolling', {transactionId: '0123456789abcdef0123456789abcdef0123456789abcdef0', getTransactionStateUrl: '/epargne-salariale/fr/async/otp/SOSD_OTP_GetTransactionState.htm', cancelUrl: '/epargne-salariale/fr/async/otp/SOSD_OTP_CancelTransaction.htm'});
</script>
</body></html>
"""

SMS_CONFIRMATION_PAGE = """
<html><body>
<form id="C:P:F" action="/epargne-salariale/fr/epargnants/premiers-pas/authentification-forte/index.html?_tabi=C&amp;_pid=OtpValidation" method="post">
  {error}
  <div id="C:OTPDeliveryChannelText">Un code de confirmation vous a été envoyé par SMS au 06 XX XX XX 12.</div>
  <input type="hidden" name="otp_hidden" value="opaque-otp-state" />
  <input type="text" name="otp_password" id="C:otp" maxlength="6" />
  <input type="hidden" name="global_backup_hidden_key" value="" />
  <input type="hidden" name="InputHiddenKeySMSSendNew1" value="" />
  <input type="submit" name="_FID_CheckOrDeliverBackup" value="Valider" />
  <input name="$CPT" type="hidden" value="opaque-page-state" />
  <input name="_wxf2_cc" type="hidden" value="fr-FR" />
</form>
</body></html>
""".replace("{error}", "")

SMS_WRONG_CODE_PAGE = SMS_CONFIRMATION_PAGE.replace(
    '<div id="C:OTPDeliveryChannelText">',
    '<div class="bloctxt err">Le code de confirmation saisi est erroné.</div><div id="C:OTPDeliveryChannelText">',
)

SKIP_LATER_PAGE = """
<html><body>
<ul><li>Si vous préférez confirmer votre identité plus tard : <a href="/epargne-salariale/fr/epargnants/premiers-pas/authentification-forte/Bypass.html">cliquez ici</a>.</li></ul>
</body></html>
"""

