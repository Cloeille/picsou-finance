// No console window next to the app on Windows.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod supervisor;

use std::fs::OpenOptions;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use supervisor::{Layout, Shared};
use tauri::menu::{CheckMenuItem, Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::webview::PageLoadEvent;
use tauri::{Manager, RunEvent, Url, WindowEvent};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};

/// The argument the login item passes: start in the tray, window closed.
const HIDDEN: &str = "--hidden";

/// False when no tray could be made: closing the window then quits, or Picsou would run unseen.
static TRAY_OK: AtomicBool = AtomicBool::new(false);

/// A startup error, kept so a page that finishes loading after it still shows it.
type Failure = Arc<Mutex<Option<String>>>;

fn failure_script(message: &str) -> String {
    let text = serde_json::to_string(message).unwrap_or_else(|_| "\"\"".to_owned());
    format!("window.picsouFailed && window.picsouFailed({text})")
}

/// `PICSOU_DESKTOP_RUNTIME` points a dev build at `desktop/runtime`; a bundle uses its resources.
fn runtime_dir(app: &tauri::AppHandle) -> PathBuf {
    std::env::var_os("PICSOU_DESKTOP_RUNTIME")
        .map(PathBuf::from)
        .unwrap_or_else(|| app.path().resource_dir().expect("resource dir").join("runtime"))
}

fn started_hidden(args: impl IntoIterator<Item = String>) -> bool {
    args.into_iter().any(|arg| arg == HIDDEN)
}

/// True once per data folder: launch at login is switched on by default on the first start
/// only, so a user who turns it off keeps it off.
fn first_start(marker: &Path) -> std::io::Result<bool> {
    match OpenOptions::new().write(true).create_new(true).open(marker) {
        Ok(_) => Ok(true),
        Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => Ok(false),
        Err(e) => Err(e),
    }
}

fn show_window(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

/// An AppImage runs from a mount that is gone after exit, so a login item would point nowhere.
fn can_launch_at_login() -> bool {
    std::env::var_os("APPIMAGE").is_none()
}

fn tray(app: &tauri::App) -> tauri::Result<()> {
    let autolaunch = app.autolaunch();
    let data = app.path().app_data_dir()?;
    std::fs::create_dir_all(&data)?;
    let marker = data.join("autostart-default");
    if can_launch_at_login() && first_start(&marker).unwrap_or(false) && autolaunch.enable().is_err() {
        // Retried on the next start.
        let _ = std::fs::remove_file(&marker);
    }
    let open = MenuItem::with_id(app, "open", "Open Picsou", true, None::<&str>)?;
    let login = CheckMenuItem::with_id(app, "login", "Launch at login", can_launch_at_login(), autolaunch.is_enabled().unwrap_or(false), None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit Picsou", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open, &login, &quit])?;
    let toggle = login.clone();
    let mut builder = TrayIconBuilder::with_id("main").tooltip("Picsou");
    if let Some(icon) = app.default_window_icon().cloned() {
        builder = builder.icon(icon);
    }
    builder
        .menu(&menu)
        .on_menu_event(move |app, event| match event.id().as_ref() {
            "open" => show_window(app),
            "login" => {
                // The OS login item is the truth: read it back rather than trusting the toggle.
                let autolaunch = app.autolaunch();
                let _ = if autolaunch.is_enabled().unwrap_or(false) { autolaunch.disable() } else { autolaunch.enable() };
                let _ = toggle.set_checked(autolaunch.is_enabled().unwrap_or(false));
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .build(app)?;
    TRAY_OK.store(true, Ordering::SeqCst);
    Ok(())
}

fn show_error(app: &tauri::AppHandle, message: &str) {
    // Even after a hidden start: an error nobody sees is a Picsou that silently never works.
    show_window(app);
    *app.state::<Failure>().lock().unwrap_or_else(|e| e.into_inner()) = Some(message.to_owned());
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.eval(failure_script(message));
    }
}

fn main() {
    let stack: Shared = Default::default();
    let app = tauri::Builder::default()
        // First, so a second launch only brings the running window forward.
        .plugin(tauri_plugin_single_instance::init(|app, argv, _| {
            if !started_hidden(argv) {
                show_window(app);
            }
        }))
        .plugin(tauri_plugin_autostart::init(MacosLauncher::LaunchAgent, Some(vec![HIDDEN])))
        .manage(stack.clone())
        .on_window_event(|window, event| {
            // Closing the window keeps Picsou running in the tray; only Quit stops it.
            if let WindowEvent::CloseRequested { api, .. } = event {
                if !TRAY_OK.load(Ordering::SeqCst) {
                    return;
                }
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .manage(Failure::default())
        .on_page_load(|webview, payload| {
            if payload.event() == PageLoadEvent::Finished {
                let failure = webview.state::<Failure>().lock().unwrap_or_else(|e| e.into_inner()).clone();
                if let Some(message) = failure {
                    let _ = webview.eval(failure_script(&message));
                }
            }
        })
        .setup(|app| {
            // libappindicator panics when its library is missing: the app then runs without a tray.
            match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| tray(app))) {
                Ok(Ok(())) => {}
                Ok(Err(e)) => eprintln!("no tray icon: {e}"),
                Err(_) => eprintln!("no tray icon: libayatana-appindicator3 is missing"),
            }
            if !started_hidden(std::env::args()) || !TRAY_OK.load(Ordering::SeqCst) {
                show_window(app.handle());
            }
            let handle = app.handle().clone();
            let layout = Layout { runtime: runtime_dir(&handle), data: handle.path().app_data_dir()? };
            let stack = handle.state::<Shared>().inner().clone();
            // Startup takes tens of seconds (migrations); the window shows ui/index.html meanwhile.
            std::thread::spawn(move || {
                match supervisor::start(&layout, &stack) {
                    Ok(url) => match (handle.get_webview_window("main"), Url::parse(&url)) {
                        (Some(window), Ok(url)) => {
                            let _ = window.navigate(url);
                        }
                        _ => show_error(&handle, "The window is gone."),
                    },
                    Err(message) => show_error(&handle, &message),
                }
                // PR_SET_PDEATHSIG fires when the spawning thread ends, not the process: this
                // thread must outlive the children, or Linux stops them as soon as start returns.
                loop {
                    std::thread::park();
                }
            });
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("Picsou could not start its window");

    // Quit, logout and shutdown all end here or in a signal; PR_SET_PDEATHSIG covers the signal.
    app.run(move |_, event| {
        if let RunEvent::Exit = event {
            supervisor::stop(&stack);
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_the_login_item_starts_hidden() {
        assert!(started_hidden(["picsou".to_owned(), "--hidden".to_owned()]));
        assert!(!started_hidden(["picsou".to_owned()]));
        assert!(!started_hidden(["picsou".to_owned(), "--hidden-not".to_owned()]));
    }

    #[test]
    fn launch_at_login_is_defaulted_once() {
        let dir = std::env::temp_dir().join(format!("picsou-desktop-first-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let marker = dir.join("autostart-default");

        assert!(first_start(&marker).unwrap());
        assert!(!first_start(&marker).unwrap());
    }
}
