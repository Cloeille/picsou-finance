"""Synthetic XHTML fixtures modelled on La Banque Postale's login and account pages.

The structure follows the pages the sidecar parses; identifiers, balances, labels
and account numbers are invented. This module is test-only and is not copied into
the production image.
"""

KEYS = ["5", "3", "9", "8", "6", "1", "0", "7", "2", "4"]

LOGIN_HTML = """
<form id="form-xo-1" method="post" action="/wsost/OstBrokerWeb/auth"
      name="formAccesCompte" onSubmit="return false;">
  <div id="hidden">
    <input type="hidden" name="urlbackend" value="%2Fvoscomptes%2FcanalXHTML%2Fidentif.ea%3Forigin%3Dparticuliers" />
    <input type="hidden" name="origin" value="particuliers" />
    <input type="hidden" name="cv" value="true" />
    <input type="hidden" name="cvvs" value="" />
    <input type="hidden" id="iscd" name="iscdName" value="0" />
    <input type="hidden" id="clt" name="cltName" value="0" />
  </div>
  <input type="text" id="identifiant" maxlength="10" name="username">
  <input type="text" id="password" name="password" maxlength=6>
  <div class="flex-container flex-center mt-16" data-tb-cvd-id="password">
    <div data-tb-cvd-keys>
      <button type="button">5</button>
      <button type="button">3</button>
      <button type="button">9</button>
      <button type="button">8</button>
      <button type="button">6</button>
      <button type="button">1</button>
      <button type="button">0</button>
      <button type="button">7</button>
      <button type="button">2</button>
      <button type="button">4</button>
      <span></span>
    </div>
  </div>
  <button data-tb-cvd-valider id="btnConfirmer" type="submit" onclick="submitFormulaire()">Se connecter</button>
</form>
"""

CHALLENGE_HTML = """
<html><head><title>Votre appareil de confiance - La Banque Postale</title></head>
<body>
<h2>Vous n'&ecirc;tes pas sur votre appareil de confiance</h2>
<p>Une notification a &eacute;t&eacute; envoy&eacute;e vers SM-S921B</p>
<script>
var ANNULER_AF = "loginform?TAM_OP=login&phase=4&cu=annulerAF";
var origin = "particuliers";
var PPH = "https://voscomptesenligne.labanquepostale.fr/wsost";
var URL_AF_POLLING = "/OstBrokerWeb/loginform?TAM_OP=login&phase=3b&cu=attenteAF";
</script>
</body></html>
"""

SYNTHESE_CCP_HTML = """
<ul>
<li>
<div class="account-resume2020 account-resume--ccp">
  <div class="cartridge">
    <div class="account-data">
      <h3 class="title">
        <a href="https://voscomptesenligne.labanquepostale.fr/voscomptes/canalXHTML/CCP/releves_ccp/frame-releve_ccp.ea?compte.numero=11111111-1111-1111-1111-111111111111&amp;typeRecherche=5">
          <span class="pseudo-h3" role="presentation">Compte Bancaire&nbsp;</span>
          <span role="presentation">N&deg;&nbsp;00000000001</span>
        </a>
      </h3>
    </div>
    <div class="account-amount">
      <div class="account-amount-group">
        <p class="amount-euro"><span class="lib sr-only">Solde </span> + 1 234,56&nbsp;&euro;</p>
      </div>
    </div>
  </div>
</div>
</li>
</ul>
"""

SYNTHESE_EP_HTML = """
<ul>
<li>
<div class="account-resume2020 account-resume--cne">
  <div class="cartridge">
    <div class="account-data">
      <h3 class="title">
        <a href="https://voscomptesenligne.labanquepostale.fr/voscomptes/canalXHTML/CNE/releveCNE/frame-releve_cne.ea?compte.numero=22222222-2222-2222-2222-222222222222&amp;typeRecherche=15">
          <span class="pseudo-h3" role="presentation">Livret A&nbsp;</span>
          <span role="presentation">N&deg;&nbsp;00000000002</span>
        </a>
      </h3>
    </div>
    <div class="account-amount">
      <div class="account-amount-group">
        <p class="amount-euro"><span class="lib sr-only">Solde </span> + 10 000,00&nbsp;&euro;</p>
      </div>
    </div>
  </div>
</div>
</li>
<li>
<div class="account-resume2020 account-resume--cne">
  <div class="cartridge">
    <div class="account-data">
      <h3 class="title">
        <a href="https://voscomptesenligne.labanquepostale.fr/voscomptes/canalXHTML/CNE/releveCNE/frame-releve_cne.ea?compte.numero=33333333-3333-3333-3333-333333333333&amp;typeRecherche=15">
          <span class="pseudo-h3" role="presentation">Livret d'&Eacute;pargne Populaire&nbsp;</span>
          <span role="presentation">N&deg;&nbsp;00000000003</span>
        </a>
      </h3>
    </div>
    <div class="account-amount">
      <div class="account-amount-group">
        <p class="amount-euro"><span class="lib sr-only">Solde </span> + 5 000,00&nbsp;&euro;</p>
      </div>
    </div>
  </div>
</div>
</li>
</ul>
"""

STATEMENT_HTML = """
<table class="list operations table-version2020" id="mouvementsTable">
  <tr class="row head">
    <th class="col">Date</th><th class="col">Libell&eacute;</th><th class="col">Montant</th>
  </tr>
  <tr class="row btn-full-lightbox">
    <td class="col label col-date" data-label="Date"><span class="sorted-value">02/10/2026</span></td>
    <td class="col label col-libelle" data-label="Libell&eacute;">VIREMENT<br/>VIREMENT POUR M TEST</td>
    <td class="col montant cell-right col-montant" data-label="Montant"><span>&minus; 350,00&nbsp;&euro;</span></td>
    <td class="col label col-montant-brut sr-only" aria-hidden="true"> -350.0 </td>
  </tr>
  <tr class="row btn-full-lightbox">
    <td class="col label col-date" data-label="Date"><span class="sorted-value">01/10/2026</span></td>
    <td class="col label col-libelle" data-label="Libell&eacute;">VIREMENT<br/>VIREMENT DE MR TEST</td>
    <td class="col montant cell-right col-montant" data-label="Montant"><span class="amount_positif">+ 205,00&nbsp;&euro;</span></td>
    <td class="col label col-montant-brut sr-only" aria-hidden="true"> 205.0 </td>
  </tr>
</table>
"""
