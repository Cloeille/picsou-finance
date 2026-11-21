export type WhatsNewLocale = 'fr' | 'en' | 'de' | 'es'

export interface WhatsNewRelease {
  version: string
  date: string
  summary: Record<WhatsNewLocale, string>
  highlights: Record<WhatsNewLocale, string[]>
}

export const PICSOU_GITHUB_URL = 'https://github.com/Cloeille/picsou-finance'
export const PICSOU_RELEASES_URL = 'https://github.com/Cloeille/picsou-finance/releases'
export const PICSOU_UPDATE_COMMAND = 'docker compose pull && docker compose up -d'

/**
 * Curated release highlights displayed in the sidebar "Nouveautés" / "What's new" card
 * and changelog modal. Add each new release at the top of this array when cutting a version
 * so every user automatically sees the latest highlights and unseen badge.
 */
export const WHATS_NEW_RELEASES: readonly WhatsNewRelease[] = [
  {
    version: '1.2.2',
    date: '2026-11-21',
    summary: {
      fr: 'Louve Invest, crypto Trade Republic à part, PER BoursoBank et finitions du design.',
      en: 'Louve Invest, Trade Republic crypto on its own, BoursoBank PER, and design polish.',
      de: 'Louve Invest, Trade-Republic-Krypto als eigenes Konto, BoursoBank-PER und Design-Feinschliff.',
      es: 'Louve Invest, cripto de Trade Republic aparte, PER de BoursoBank y pulido del diseño.',
    },
    highlights: {
      fr: [
        'Nouvelle synchronisation Louve Invest : vos parts de SCPI importées chaque jour, sans mot de passe stocké.',
        'Trade Republic : vos cryptos dans un compte TR Crypto avec cours et logos, et des obligations correctement valorisées.',
        'Banques : PER BoursoBank, prêts Banque Populaire, contrats non importés nommés chez BPCE, connexion American Express plus fiable.',
        'Budget : virements entre vos propres comptes reconnus, page des paiements récurrents repensée, synchronisation manquée rattrapée au démarrage.',
        'Design : nouvelles icônes, cartes plus compactes, réorganisation des comptes, simulateur de rendement dans la projection.',
      ],
      en: [
        'New Louve Invest sync: your SCPI shares imported daily, with no password stored.',
        'Trade Republic: your crypto in a TR Crypto account with prices and logos, and bonds valued correctly.',
        'Banks: BoursoBank PER, Banque Populaire loans, BPCE contracts not imported are named, more reliable American Express login.',
        'Budget: transfers between your own accounts recognised, redesigned recurring payments page, a missed sync made up at startup.',
        'Design: new icons, more compact cards, account reordering, and a return-rate simulator in the projection.',
      ],
      de: [
        'Neue Louve-Invest-Synchronisierung: Ihre SCPI-Anteile täglich importiert, ohne gespeichertes Passwort.',
        'Trade Republic: Ihre Kryptowährungen in einem eigenen TR-Crypto-Konto mit Kursen und Logos, Anleihen korrekt bewertet.',
        'Banken: BoursoBank-PER, Kredite der Banque Populaire, nicht importierte BPCE-Verträge benannt, zuverlässigere American-Express-Anmeldung.',
        'Budget: Überweisungen zwischen eigenen Konten erkannt, neu gestaltete Seite für wiederkehrende Zahlungen, verpasste Synchronisierung beim Start nachgeholt.',
        'Design: neue Symbole, kompaktere Karten, Konten neu anordnen und ein Renditesimulator in der Prognose.',
      ],
      es: [
        'Nueva sincronización de Louve Invest: tus participaciones en SCPI importadas cada día, sin guardar contraseña.',
        'Trade Republic: tus criptomonedas en una cuenta TR Crypto con cotizaciones y logotipos, y bonos bien valorados.',
        'Bancos: PER de BoursoBank, préstamos de Banque Populaire, contratos BPCE no importados identificados, inicio de sesión de American Express más fiable.',
        'Presupuesto: transferencias entre tus propias cuentas reconocidas, página de pagos recurrentes rediseñada, sincronización perdida recuperada al arrancar.',
        'Diseño: iconos nuevos, tarjetas más compactas, reordenación de cuentas y un simulador de rentabilidad en la proyección.',
      ],
    },
  },
  {
    version: '1.2.1',
    date: '2026-11-20',
    summary: {
      fr: 'Refonte visuelle complète, navigation affinée et budget repensé.',
      en: 'Complete visual polish, refined navigation, and redesigned budget tools.',
      de: 'Umfassender visueller Feinschliff, klarere Navigation und neues Budget-Design.',
      es: 'Renovación visual completa, navegación refinada y herramientas de presupuesto mejoradas.',
    },
    highlights: {
      fr: [
        'Nouvelle identité visuelle : typographie Plus Jakarta Sans, mise en page plus aérée sur grand écran, menus déroulants et sections dépliables 100 % sur mesure.',
        'Navigation et profil : barre latérale plus compacte, menu de compte unifié, photo de profil personnalisable et suivi des nouveautés.',
        'Comptes et tableau de bord : barre de catégories dynamique avec menu « Plus », édition déplacée dans la fiche du compte et sélecteurs de période harmonisés (24H, 7J, 1M, 3M, YTD, 1A, Tout).',
        'Budget et objectifs : refonte complète des vues À catégoriser et Gérer (libellés bancaires clarifiés, cycle budgétaire instantané) et répartition équitable en un clic dans les objectifs.',
        'Paramètres et administration : pages Apparence, Espace et système et configuration du fournisseur repensées, taux marginal d’imposition libre multi-pays et vérification des mises à jour.',
        'Fiabilité générale : défilement propre sur toutes les modales, états vides encadrés, mode confidentialité renforcé, logo et connexion La Banque Postale fiabilisés et exclusion des prêts entre particuliers du patrimoine financier.',
      ],
      en: [
        'Refreshed visual identity: unified Plus Jakarta Sans typography, wider desktop layout, and custom dropdown menus and collapsible sections everywhere.',
        'Navigation & profile: more compact sidebar, single unified account menu, custom profile photo upload, and release highlights.',
        'Accounts & dashboard: dynamic category bar with a More menu, account editing moved to the detail page, and standardized time-range selectors.',
        'Budget & goals: redesigned Categorize and Manage views with cleaner transaction labels, instant cycle-start picker, and one-click equal allocation splitting.',
        'Settings & admin: redesigned Appearance, Space & system, and provider configuration cards, country-neutral marginal tax rate input, and live update checks.',
        'Reliability & connectors: smooth scrolling and overflow protection across every modal, framed empty states, complete privacy-mode masking, bundled La Banque Postale mark with resilient login, and peer loans excluded from financial assets.',
      ],
      de: [
        'Überarbeitetes Design: einheitliche Plus-Jakarta-Sans-Typografie, großzügigeres Desktop-Layout sowie vollständig eigene Auswahlmenüs und Klappbereiche.',
        'Navigation & Profil: kompaktere Seitenleiste, zentrales Kontomenü, eigenes Profilbild und Versions-Neuigkeiten.',
        'Konten & Dashboard: dynamische Kategorieleiste mit Mehr-Menü, Kontobearbeitung direkt in der Detailansicht und einheitliche Zeitraum-Auswahl.',
        'Budget & Ziele: neu gestaltete Ansichten Kategorisieren und Verwalten mit klareren Buchungstiteln, sofortiger Zykluswahl und gleichmäßiger Zielaufteilung per Klick.',
        'Einstellungen & Administration: überarbeitete Karten für Darstellung, Bereich & System sowie Anbieterkonfiguration, länderunabhängiger Steuersatz und Update-Prüfung.',
        'Zuverlässigkeit & Banken: sauberes Scrollen in allen Dialogen, einheitliche Leerzustände, lückenloser Privatsphäre-Modus, La-Banque-Postale-Logo und stabilerer Login sowie korrekte Finanzvermögens-Berechnung.',
      ],
      es: [
        'Identidad visual renovada: tipografía Plus Jakarta Sans, diseño más amplio en escritorio y menús desplegables y secciones plegables a medida.',
        'Navegación y perfil: barra lateral más compacta, menú de cuenta unificado, foto de perfil personalizable y panel de novedades.',
        'Cuentas y panel principal: barra dinámica de categorías con menú «Más», edición trasladada a la ficha de la cuenta y selectores de periodo unificados.',
        'Presupuesto y objetivos: vistas Por categorizar y Gestionar rediseñadas con conceptos bancarios claros, selector instantáneo de ciclo y reparto equitativo en un clic.',
        'Ajustes y administración: tarjetas rediseñadas en Apariencia, Espacio y sistema y configuración de proveedor, tipo marginal libre para cualquier país y comprobación de actualizaciones.',
        'Fiabilidad y bancos: desplazamiento correcto en todos los modales, estados vacíos enmarcados, modo privacidad completo, logo y acceso mejorado de La Banque Postale y préstamos entre particulares excluidos del patrimonio financiero.',
      ],
    },
  },
  {
    version: '1.2.0',
    date: '2026-11-12',
    summary: {
      fr: 'Synchronisation BNP Paribas / Hello bank!, PER Banque Populaire et positions SimpleFIN.',
      en: 'BNP Paribas / Hello bank! sync, Banque Populaire PER, and SimpleFIN brokerage positions.',
      de: 'BNP-Paribas- und Hello-bank!-Sync, Banque-Populaire-PER sowie SimpleFIN-Positionen.',
      es: 'Sincronización con BNP Paribas / Hello bank!, PER de Banque Populaire y posiciones SimpleFIN.',
    },
    highlights: {
      fr: [
        'Synchronisation BNP Paribas et Hello bank! avec clavier virtuel sécurisé.',
        'Import des PER Banque Populaire et mémorisation chiffrée de l’identifiant.',
        'Affichage des banques et positions détaillées pour les comptes SimpleFIN.',
        'Édition du partage de propriété directement depuis la fiche d’un emprunt.',
      ],
      en: [
        'BNP Paribas and Hello bank! sync with secure virtual keypad.',
        'Banque Populaire PER import and encrypted identifier recall.',
        'Bank attribution and detailed brokerage positions for SimpleFIN accounts.',
        'Co-ownership split editing directly from mortgage account sheets.',
      ],
      de: [
        'BNP-Paribas- und Hello-bank!-Synchronisation mit sicherer virtueller Tastatur.',
        'Import von Banque-Populaire-PER-Konten und verschlüsselte Kennungsmerkung.',
        'Bankzuordnung und detaillierte Depotpositionen für SimpleFIN-Konten.',
        'Bearbeitung von Eigentumsanteilen direkt in der Kreditansicht.',
      ],
      es: [
        'Sincronización con BNP Paribas y Hello bank! mediante teclado virtual seguro.',
        'Importación de planes PER en Banque Populaire y recuerdo cifrado del identificador.',
        'Visualización del banco y posiciones detalladas en cuentas SimpleFIN.',
        'Edición de la copropiedad directamente desde la ficha de cada préstamo.',
      ],
    },
  },
]

export function resolveWhatsNewLocale(language: string | undefined): WhatsNewLocale {
  const code = (language ?? 'en').toLowerCase().slice(0, 2)
  if (code === 'fr' || code === 'en' || code === 'de' || code === 'es') return code
  return 'en'
}

export function getLatestWhatsNew(): WhatsNewRelease {
  return WHATS_NEW_RELEASES[0]
}
