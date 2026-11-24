//! The words the shell shows itself (tray menu, startup failures, restore), in the app's four
//! languages, picked from the system locale. The page the backend serves and the loading page
//! (`ui/index.html`) carry their own translations.

use std::fmt::Display;
use std::sync::OnceLock;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Lang {
    En,
    Fr,
    Es,
    De,
}

/// One text per language, in the order of [`Lang`].
type Texts = [&'static str; 4];

impl Lang {
    /// The language for a locale tag such as `fr-FR`, `de_DE.UTF-8` or `es`; English otherwise.
    pub fn from_tag(tag: &str) -> Self {
        let primary = tag
            .split(['-', '_', '.', '@'])
            .next()
            .unwrap_or_default()
            .to_ascii_lowercase();
        match primary.as_str() {
            "fr" => Self::Fr,
            "es" => Self::Es,
            "de" => Self::De,
            _ => Self::En,
        }
    }

    /// The system's language, read once.
    pub fn system() -> Self {
        static LANG: OnceLock<Lang> = OnceLock::new();
        *LANG.get_or_init(|| sys_locale::get_locale().map_or(Self::En, |tag| Self::from_tag(&tag)))
    }

    fn pick(self, texts: Texts) -> &'static str {
        texts[self as usize]
    }
}

const TRAY_OPEN: Texts = [
    "Open Picsou",
    "Ouvrir Picsou",
    "Abrir Picsou",
    "Picsou öffnen",
];
const TRAY_LAUNCH_AT_LOGIN: Texts = [
    "Launch at login",
    "Lancer à l'ouverture de session",
    "Abrir al iniciar sesión",
    "Bei der Anmeldung starten",
];
const TRAY_QUIT: Texts = [
    "Quit Picsou",
    "Quitter Picsou",
    "Salir de Picsou",
    "Picsou beenden",
];
const TRAY_UP_TO_DATE: Texts = [
    "Picsou is up to date",
    "Picsou est à jour",
    "Picsou está actualizado",
    "Picsou ist aktuell",
];
const TRAY_INSTALL: Texts = [
    "Install Picsou {version}",
    "Installer Picsou {version}",
    "Instalar Picsou {version}",
    "Picsou {version} installieren",
];
const TRAY_INSTALLING: Texts = [
    "Installing update…",
    "Installation de la mise à jour…",
    "Instalando actualización…",
    "Update wird installiert…",
];
const TRAY_INSTALL_FAILED: Texts = [
    "Update failed — retry",
    "Échec de la mise à jour — réessayer",
    "Error al actualizar — reintentar",
    "Update fehlgeschlagen — erneut versuchen",
];
const UPDATE_INSTALL_FAILED: Texts = [
    "The Picsou update could not be installed ({reason}). You can retry from the tray menu.",
    "La mise à jour de Picsou n'a pas pu être installée ({reason}). Vous pouvez réessayer depuis le menu de la barre d'état.",
    "No se ha podido instalar la actualización de Picsou ({reason}). Puedes volver a intentarlo desde el menú de la bandeja.",
    "Das Picsou-Update konnte nicht installiert werden ({reason}). Du kannst es über das Menü in der Taskleiste erneut versuchen.",
];
const RESTORED: Texts = [
    "Your data from before the update is back. Reinstall the previous Picsou version, then open it.",
    "Vos données d'avant la mise à jour sont de retour. Réinstallez la version précédente de Picsou, puis ouvrez-la.",
    "Tus datos anteriores a la actualización se han restaurado. Reinstala la versión anterior de Picsou y ábrela.",
    "Deine Daten von vor dem Update sind wiederhergestellt. Installiere die vorherige Picsou-Version erneut und öffne sie.",
];
const DID_NOT_START: Texts = [
    "The backend did not start. See the logs folder.",
    "Le moteur local de Picsou n'a pas démarré. Consultez le dossier des journaux.",
    "El motor local de Picsou no se ha iniciado. Consulta la carpeta de registros.",
    "Der lokale Picsou-Dienst wurde nicht gestartet. Sieh im Protokollordner nach.",
];
const NEWER_DATA: Texts = [
    "Your data was last opened by Picsou {from}, which is newer than this one ({current}). Install Picsou {from} or later. Your data was not changed.",
    "Vos données ont été ouvertes en dernier par Picsou {from}, plus récent que cette version ({current}). Installez Picsou {from} ou une version ultérieure. Vos données n'ont pas été modifiées.",
    "Tus datos se abrieron por última vez con Picsou {from}, más reciente que esta versión ({current}). Instala Picsou {from} o una versión posterior. Tus datos no se han modificado.",
    "Deine Daten wurden zuletzt mit Picsou {from} geöffnet, das neuer ist als diese Version ({current}). Installiere Picsou {from} oder neuer. Deine Daten wurden nicht verändert.",
];
const NEWER_DATA_UNKNOWN: Texts = [
    "Your data was last opened by a newer Picsou than this one ({current}). Install the latest Picsou. Your data was not changed.",
    "Vos données ont été ouvertes en dernier par une version de Picsou plus récente que celle-ci ({current}). Installez la dernière version de Picsou. Vos données n'ont pas été modifiées.",
    "Tus datos se abrieron por última vez con una versión de Picsou más reciente que esta ({current}). Instala la última versión de Picsou. Tus datos no se han modificado.",
    "Deine Daten wurden zuletzt mit einer neueren Picsou-Version als dieser ({current}) geöffnet. Installiere die neueste Picsou-Version. Deine Daten wurden nicht verändert.",
];
const UPDATE_FAILED_WITH_COPY: Texts = [
    "The update from {from} to {current} could not update your data ({reason}). A copy from before the update is kept: restore it, then reinstall Picsou {from}.",
    "La mise à jour de {from} vers {current} n'a pas pu mettre à jour vos données ({reason}). Une copie d'avant la mise à jour est conservée : restaurez-la, puis réinstallez Picsou {from}.",
    "La actualización de {from} a {current} no ha podido actualizar tus datos ({reason}). Se conserva una copia anterior a la actualización: restáurala y vuelve a instalar Picsou {from}.",
    "Das Update von {from} auf {current} konnte deine Daten nicht aktualisieren ({reason}). Eine Kopie von vor dem Update ist vorhanden: Stelle sie wieder her und installiere dann Picsou {from} erneut.",
];
const DATA_UPDATE_FAILED: Texts = [
    "Your data could not be updated ({reason}). See the logs folder.",
    "Vos données n'ont pas pu être mises à jour ({reason}). Consultez le dossier des journaux.",
    "No se han podido actualizar tus datos ({reason}). Consulta la carpeta de registros.",
    "Deine Daten konnten nicht aktualisiert werden ({reason}). Sieh im Protokollordner nach.",
];
const STOPPED_DURING_UPDATE: Texts = [
    "{stopped} This happened during the update from {from}: a copy from before it is kept.",
    "{stopped} Cela s'est produit pendant la mise à jour depuis {from} : une copie d'avant la mise à jour est conservée.",
    "{stopped} Ha ocurrido durante la actualización desde {from}: se conserva una copia anterior.",
    "{stopped} Das ist während des Updates von {from} passiert: Eine Kopie von vorher ist vorhanden.",
];
const BACKUP_FAILED: Texts = [
    "Picsou could not copy your data before the update, so it did not start: {error}",
    "Picsou n'a pas pu copier vos données avant la mise à jour et n'a donc pas démarré : {error}",
    "Picsou no ha podido copiar tus datos antes de la actualización, por lo que no se ha iniciado: {error}",
    "Picsou konnte deine Daten vor dem Update nicht kopieren und wurde deshalb nicht gestartet: {error}",
];

pub fn tray_open(lang: Lang) -> &'static str {
    lang.pick(TRAY_OPEN)
}

pub fn tray_launch_at_login(lang: Lang) -> &'static str {
    lang.pick(TRAY_LAUNCH_AT_LOGIN)
}

pub fn tray_quit(lang: Lang) -> &'static str {
    lang.pick(TRAY_QUIT)
}

pub fn tray_up_to_date(lang: Lang) -> &'static str {
    lang.pick(TRAY_UP_TO_DATE)
}

pub fn tray_install(lang: Lang, version: &str) -> String {
    lang.pick(TRAY_INSTALL).replace("{version}", version)
}

pub fn tray_installing(lang: Lang) -> &'static str {
    lang.pick(TRAY_INSTALLING)
}

pub fn tray_install_failed(lang: Lang) -> &'static str {
    lang.pick(TRAY_INSTALL_FAILED)
}

pub fn update_install_failed(lang: Lang, reason: &str) -> String {
    lang.pick(UPDATE_INSTALL_FAILED).replace("{reason}", reason)
}

/// What the error page shows once the copy from before the update is back.
pub fn restored(lang: Lang) -> &'static str {
    lang.pick(RESTORED)
}

pub fn did_not_start(lang: Lang) -> &'static str {
    lang.pick(DID_NOT_START)
}

/// The data folder was last opened by a newer Picsou; `from` is that version when it is known.
pub fn newer_data(lang: Lang, from: Option<&str>, current: &str) -> String {
    match from {
        Some(from) => lang
            .pick(NEWER_DATA)
            .replace("{from}", from)
            .replace("{current}", current),
        None => lang.pick(NEWER_DATA_UNKNOWN).replace("{current}", current),
    }
}

pub fn update_failed_with_copy(lang: Lang, from: &str, current: &str, reason: &str) -> String {
    lang.pick(UPDATE_FAILED_WITH_COPY)
        .replace("{from}", from)
        .replace("{current}", current)
        .replace("{reason}", reason)
}

pub fn data_update_failed(lang: Lang, reason: &str) -> String {
    lang.pick(DATA_UPDATE_FAILED).replace("{reason}", reason)
}

pub fn stopped_during_update(lang: Lang, stopped: &str, from: &str) -> String {
    lang.pick(STOPPED_DURING_UPDATE)
        .replace("{stopped}", stopped)
        .replace("{from}", from)
}

pub fn backup_failed(lang: Lang, error: impl Display) -> String {
    lang.pick(BACKUP_FAILED)
        .replace("{error}", &error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    const ALL: [Lang; 4] = [Lang::En, Lang::Fr, Lang::Es, Lang::De];

    #[test]
    fn a_locale_tag_maps_to_one_of_the_four_languages() {
        assert_eq!(Lang::from_tag("fr-FR"), Lang::Fr);
        assert_eq!(Lang::from_tag("fr_CA.UTF-8"), Lang::Fr);
        assert_eq!(Lang::from_tag("ES"), Lang::Es);
        assert_eq!(Lang::from_tag("de_DE@euro"), Lang::De);
        assert_eq!(Lang::from_tag("en-GB"), Lang::En);
        assert_eq!(Lang::from_tag("it-IT"), Lang::En);
        assert_eq!(Lang::from_tag("C"), Lang::En);
        assert_eq!(Lang::from_tag(""), Lang::En);
    }

    #[test]
    fn every_language_keeps_the_placeholders_of_the_english_text() {
        let templates: [(Texts, &[&str]); 8] = [
            (TRAY_INSTALL, &["{version}"]),
            (UPDATE_INSTALL_FAILED, &["{reason}"]),
            (NEWER_DATA, &["{from}", "{current}"]),
            (NEWER_DATA_UNKNOWN, &["{current}"]),
            (
                UPDATE_FAILED_WITH_COPY,
                &["{from}", "{current}", "{reason}"],
            ),
            (DATA_UPDATE_FAILED, &["{reason}"]),
            (STOPPED_DURING_UPDATE, &["{stopped}", "{from}"]),
            (BACKUP_FAILED, &["{error}"]),
        ];
        for (texts, placeholders) in templates {
            for text in texts {
                for placeholder in placeholders {
                    assert!(
                        text.contains(placeholder),
                        "{placeholder} missing in: {text}"
                    );
                }
            }
        }
        for texts in [
            TRAY_OPEN,
            TRAY_LAUNCH_AT_LOGIN,
            TRAY_QUIT,
            TRAY_UP_TO_DATE,
            TRAY_INSTALLING,
            TRAY_INSTALL_FAILED,
            RESTORED,
            DID_NOT_START,
        ] {
            for text in texts {
                assert!(!text.is_empty() && !text.contains('{'), "{text}");
            }
        }
    }

    #[test]
    fn a_filled_message_leaves_no_placeholder_behind() {
        for lang in ALL {
            for message in [
                tray_install(lang, "1.3.0"),
                update_install_failed(lang, "network error"),
                newer_data(lang, Some("1.4.0"), "1.3.0"),
                newer_data(lang, None, "1.3.0"),
                update_failed_with_copy(lang, "1.2.0", "1.3.0", "V2 failed"),
                data_update_failed(lang, "V2 failed"),
                stopped_during_update(lang, did_not_start(lang), "1.2.0"),
                backup_failed(lang, "disk full"),
            ] {
                assert!(
                    !message.contains('{') && !message.contains('}'),
                    "{message}"
                );
            }
        }
        assert_eq!(tray_install(Lang::Fr, "1.3.0"), "Installer Picsou 1.3.0");
        assert_eq!(tray_installing(Lang::En), "Installing update…");
        assert_eq!(tray_install_failed(Lang::En), "Update failed — retry");
        assert_eq!(tray_open(Lang::De), "Picsou öffnen");
        assert_eq!(tray_quit(Lang::Es), "Salir de Picsou");
        assert_eq!(tray_launch_at_login(Lang::En), "Launch at login");
        assert_eq!(tray_up_to_date(Lang::Fr), "Picsou est à jour");
        assert!(restored(Lang::Fr).starts_with("Vos données"));
    }
}
