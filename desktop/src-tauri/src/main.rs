// No console window next to the app on Windows.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod sidecars;
mod supervisor;
mod update;
mod upgrade;

use std::fs::OpenOptions;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;
use supervisor::{Failed, Layout, Shared};
use tauri::menu::{CheckMenuItem, Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::webview::PageLoadEvent;
use tauri::window::Color;
use tauri::{Manager, RunEvent, Url, WindowEvent};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};

/// The argument the login item passes: start in the tray, window closed.
const HIDDEN: &str = "--hidden";

/// False when no tray could be made: closing the window then quits, or Picsou would run unseen.
static TRAY_OK: AtomicBool = AtomicBool::new(false);

/// A startup error, kept so a page that finishes loading after it still shows it.
type Failure = Arc<Mutex<Option<Failed>>>;

fn failure_script(failed: &Failed) -> String {
    let text = serde_json::to_string(&failed.message).unwrap_or_else(|_| "\"\"".to_owned());
    format!("window.picsouFailed && window.picsouFailed({text}, {})", failed.can_restore)
}

fn layout(app: &tauri::AppHandle) -> tauri::Result<Layout> {
    // Local, not roaming: a PostgreSQL cluster must not sync with a roaming profile.
    // Tauri returns Windows paths as `\\?\C:\…`; initdb cannot find postgres.exe next to itself
    // with that form, so both folders are simplified to `C:\…` (a no-op elsewhere).
    let data = dunce::simplified(&app.path().app_local_data_dir()?).to_path_buf();
    Ok(Layout { runtime: dunce::simplified(&runtime_dir(app)).to_path_buf(), data })
}

/// Called by the error page only: IPC is limited to the local `ui/` page, never the backend's.
/// Async, so the copy (seconds to minutes) runs off the main thread and the page stays responsive.
#[tauri::command]
async fn restore_backup(app: tauri::AppHandle) -> Result<String, String> {
    let layout = layout(&app).map_err(|e| e.to_string())?;
    supervisor::restore_backup(&layout, app.state::<Shared>().inner())?;
    // A reload must not offer the button again: a second restore would move the restored data aside.
    *app.state::<Failure>().lock().unwrap_or_else(|e| e.into_inner()) = None;
    Ok("Your data from before the update is back. Reinstall the previous Picsou version, then open it.".to_owned())
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

fn since_launch() -> u128 {
    picsou::PROCESS_START.get().map_or(0, |t| t.elapsed().as_millis())
}

/// The canvas of the loading page (DESIGN.md: Vault Light, or the dark canvas), so the window
/// never flashes black or white before the page paints.
fn paint_canvas(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let dark = matches!(window.theme(), Ok(tauri::Theme::Dark));
        let _ = window.set_background_color(Some(if dark { Color(10, 10, 10, 255) } else { Color(255, 255, 255, 255) }));
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
    let data = app.path().app_local_data_dir()?;
    std::fs::create_dir_all(&data)?;
    let marker = data.join("autostart-default");
    if can_launch_at_login() && first_start(&marker).unwrap_or(false) && autolaunch.enable().is_err() {
        // Retried on the next start.
        let _ = std::fs::remove_file(&marker);
    }
    let open = MenuItem::with_id(app, "open", "Open Picsou", true, None::<&str>)?;
    let login = CheckMenuItem::with_id(app, "login", "Launch at login", can_launch_at_login(), autolaunch.is_enabled().unwrap_or(false), None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit Picsou", true, None::<&str>)?;
    let mut items: Vec<&dyn tauri::menu::IsMenuItem<tauri::Wry>> = vec![&open, &login];
    // Only a release build (updater key in its config) checks for updates.
    let install = MenuItem::with_id(app, "update", "Picsou is up to date", false, None::<&str>)?;
    if update::enabled(app.config()) {
        *app.state::<update::TrayItem>().0.lock().unwrap_or_else(|e| e.into_inner()) = Some(install.clone());
        items.push(&install);
    }
    items.push(&quit);
    let menu = Menu::with_items(app, &items)?;
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
            "update" => update::install(app),
            "quit" => app.exit(0),
            _ => {}
        })
        .build(app)?;
    TRAY_OK.store(true, Ordering::SeqCst);
    Ok(())
}

fn show_error(app: &tauri::AppHandle, failed: Failed) {
    // Even after a hidden start: an error nobody sees is a Picsou that silently never works.
    show_window(app);
    // Stored first: a page that finishes loading in between replays it.
    *app.state::<Failure>().lock().unwrap_or_else(|e| e.into_inner()) = Some(failed.clone());
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.eval(failure_script(&failed));
    }
}

fn main() {
    let _ = picsou::PROCESS_START.set(Instant::now());
    #[cfg(windows)]
    supervisor::kill_children_with_the_app();
    let stack: Shared = Default::default();
    let app = tauri::Builder::default()
        // First, so a second launch only brings the running window forward.
        .plugin(tauri_plugin_single_instance::init(|app, argv, _| {
            // `picsou --install-update` does what the tray's Install entry does, scriptably.
            if update::enabled(app.config()) && update::wants_install(argv.iter().cloned()) {
                update::install(app);
            } else if !started_hidden(argv) {
                show_window(app);
            }
        }))
        .plugin(tauri_plugin_autostart::init(MacosLauncher::LaunchAgent, Some(vec![HIDDEN])))
        .manage(stack.clone())
        .manage(update::TrayItem::default())
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
        .invoke_handler(tauri::generate_handler![restore_backup])
        .on_page_load(|webview, payload| {
            if payload.event() == PageLoadEvent::Finished {
                if payload.url().scheme() == "http" {
                    tracing::info!("the app page loaded {} ms after launch", since_launch());
                }
                let state = webview.state::<Failure>();
                let failure = state.lock().unwrap_or_else(|e| e.into_inner());
                if let Some(failed) = failure.as_ref() {
                    let _ = webview.eval(failure_script(failed));
                }
            }
        })
        .setup(|app| {
            let handle = app.handle().clone();
            let layout = layout(&handle)?;
            // The shell and the linked backend log to the same rolling file.
            picsou::logging::init(Some(&layout.data.join("logs").join("backend.log")));
            paint_canvas(&handle);
            // libappindicator panics when its library is missing: the app then runs without a tray.
            match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| tray(app))) {
                Ok(Ok(())) => {}
                Ok(Err(e)) => eprintln!("no tray icon: {e}"),
                Err(_) => eprintln!("no tray icon: libayatana-appindicator3 is missing"),
            }
            if !started_hidden(std::env::args()) || !TRAY_OK.load(Ordering::SeqCst) {
                show_window(app.handle());
                tracing::info!("window shown {} ms after launch", since_launch());
            }
            if update::enabled(app.config()) {
                app.handle().plugin(tauri_plugin_updater::Builder::new().build())?;
            }
            let stack = handle.state::<Shared>().inner().clone();
            // The window shows ui/index.html while PostgreSQL starts, then the app.
            std::thread::spawn(move || {
                match supervisor::start(&layout, &stack) {
                    Ok(ready) => match (handle.get_webview_window("main"), Url::parse(&ready.url)) {
                        (Some(window), Ok(url)) => {
                            let _ = window.navigate(url);
                            if update::enabled(handle.config()) {
                                update::watch(&handle);
                            }
                            ready.serve();
                        }
                        _ => show_error(&handle, Failed::from("The window is gone.".to_owned())),
                    },
                    Err(failed) => show_error(&handle, failed),
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
    app.run(move |app, event| match event {
        RunEvent::Exit => supervisor::stop(&stack),
        // macOS: a click on the Dock icon of a running Picsou whose window is closed.
        #[cfg(target_os = "macos")]
        RunEvent::Reopen { has_visible_windows: false, .. } => show_window(app),
        _ => {
            let _ = app;
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
