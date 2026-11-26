// No console window next to the app on Windows.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod enable_banking;
mod external;
mod i18n;
mod sidecars;
mod supervisor;
mod update;
mod upgrade;
mod webview_bank;

use std::path::PathBuf;
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
    format!(
        "window.picsouFailed && window.picsouFailed({text}, {})",
        failed.can_restore
    )
}

fn layout(app: &tauri::AppHandle) -> tauri::Result<Layout> {
    // Local, not roaming: the embedded SQLite database must not sync with a roaming profile.
    // Tauri returns Windows paths as `\\?\C:\…`; simplify both folders to `C:\…` (a no-op elsewhere).
    let data = dunce::simplified(&app.path().app_local_data_dir()?).to_path_buf();
    Ok(Layout {
        runtime: dunce::simplified(&runtime_dir(app)).to_path_buf(),
        data,
    })
}

/// Called by the error page only: IPC is limited to the local `ui/` page, never the backend's.
/// Async, so the copy (seconds to minutes) runs off the main thread and the page stays responsive.
#[tauri::command]
async fn restore_backup(app: tauri::AppHandle) -> Result<String, String> {
    let layout = layout(&app).map_err(|e| e.to_string())?;
    supervisor::restore_backup(&layout, app.state::<Shared>().inner())?;
    // A reload must not offer the button again: a second restore would move the restored data aside.
    *app.state::<Failure>()
        .lock()
        .unwrap_or_else(|e| e.into_inner()) = None;
    Ok(i18n::restored(i18n::Lang::system()).to_owned())
}

/// `PICSOU_DESKTOP_RUNTIME` points a dev build at `desktop/runtime`; a bundle uses its resources.
fn runtime_dir(app: &tauri::AppHandle) -> PathBuf {
    std::env::var_os("PICSOU_DESKTOP_RUNTIME")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            app.path()
                .resource_dir()
                .expect("resource dir")
                .join("runtime")
        })
}

fn started_hidden(args: impl IntoIterator<Item = String>) -> bool {
    args.into_iter().any(|arg| arg == HIDDEN)
}

fn since_launch() -> u128 {
    picsou::PROCESS_START
        .get()
        .map_or(0, |t| t.elapsed().as_millis())
}

/// The canvas of the loading page (DESIGN.md: Vault Light, or the dark canvas), so the window
/// never flashes black or white before the page paints.
fn paint_canvas(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let dark = matches!(window.theme(), Ok(tauri::Theme::Dark));
        let _ = window.set_background_color(Some(if dark {
            Color(10, 10, 10, 255)
        } else {
            Color(255, 255, 255, 255)
        }));
    }
}

/// The main window, built here rather than by Tauri (`"create": false` in tauri.conf.json): only
/// a builder can attach the new-window handler. A link the page opens in a new window goes to
/// the system browser, and the app never gets a second window of its own.
fn main_window(app: &tauri::App) -> tauri::Result<()> {
    if app.get_webview_window("main").is_some() {
        return Ok(());
    }
    let config = app
        .config()
        .app
        .windows
        .iter()
        .find(|window| window.label == "main")
        .cloned()
        .ok_or(tauri::Error::WindowNotFound)?;
    tauri::WebviewWindowBuilder::from_config(app.handle(), &config)?
        .on_new_window(|url, _features| {
            external::open_in_browser(&url);
            tauri::webview::NewWindowResponse::Deny
        })
        .build()?;
    Ok(())
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
    // Launch at login is the user's choice, off until they tick it: nothing is added to the
    // system's login items on their behalf.
    let autolaunch = app.autolaunch();
    let lang = i18n::Lang::system();
    let open = MenuItem::with_id(app, "open", i18n::tray_open(lang), true, None::<&str>)?;
    let login = CheckMenuItem::with_id(
        app,
        "login",
        i18n::tray_launch_at_login(lang),
        can_launch_at_login(),
        autolaunch.is_enabled().unwrap_or(false),
        None::<&str>,
    )?;
    let quit = MenuItem::with_id(app, "quit", i18n::tray_quit(lang), true, None::<&str>)?;
    let mut items: Vec<&dyn tauri::menu::IsMenuItem<tauri::Wry>> = vec![&open, &login];
    // Only a release build (updater key in its config) checks for updates.
    let install = MenuItem::with_id(
        app,
        "update",
        i18n::tray_up_to_date(lang),
        false,
        None::<&str>,
    )?;
    if update::enabled(app.config()) {
        *app.state::<update::TrayItem>()
            .0
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = Some(install.clone());
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
                let _ = if autolaunch.is_enabled().unwrap_or(false) {
                    autolaunch.disable()
                } else {
                    autolaunch.enable()
                };
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
    *app.state::<Failure>()
        .lock()
        .unwrap_or_else(|e| e.into_inner()) = Some(failed.clone());
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
        .plugin(tauri_plugin_autostart::init(
            MacosLauncher::LaunchAgent,
            Some(vec![HIDDEN]),
        ))
        .manage(stack.clone())
        .manage(update::TrayItem::default())
        .manage(update::UpdateState::default())
        .manage(enable_banking::ActiveAuthWindow::default())
        .on_window_event(|window, event| {
            // Closing the main window keeps Picsou running in the tray; bank login windows close normally.
            if window.label() != "main" {
                return;
            }
            if let WindowEvent::CloseRequested { api, .. } = event {
                if !TRAY_OK.load(Ordering::SeqCst) {
                    return;
                }
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .manage(Failure::default())
        .invoke_handler(tauri::generate_handler![
            restore_backup,
            enable_banking::open_enable_banking_auth,
            update::get_desktop_update_status,
            update::check_desktop_update,
            update::install_desktop_update
        ])
        .on_page_load(|webview, payload| {
            if webview.label() == "main" && payload.event() == PageLoadEvent::Finished {
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
            main_window(app)?;
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
                app.handle()
                    .plugin(tauri_plugin_updater::Builder::new().build())?;
                update::start_renderer(app.handle());
            }
            let stack = handle.state::<Shared>().inner().clone();
            // The window shows ui/index.html while the embedded SQLite backend starts, then the app.
            std::thread::spawn(move || {
                match supervisor::start(&layout, &stack, Some(handle.clone())) {
                    Ok(ready) => {
                        match (handle.get_webview_window("main"), Url::parse(&ready.url)) {
                            (Some(window), Ok(url)) => {
                                let _ = window.navigate(url);
                                if update::enabled(handle.config()) {
                                    update::watch(&handle);
                                }
                                ready.serve();
                            }
                            _ => {
                                show_error(&handle, Failed::from("The window is gone.".to_owned()))
                            }
                        }
                    }
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
        RunEvent::Exit => {
            app.state::<enable_banking::ActiveAuthWindow>()
                .close_active(app);
            supervisor::stop(&stack);
        }
        // macOS: a click on the Dock icon of a running Picsou whose window is closed.
        #[cfg(target_os = "macos")]
        RunEvent::Reopen {
            has_visible_windows: false,
            ..
        } => show_window(app),
        _ => {
            let _ = app;
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_main_window_is_left_to_the_builder_that_routes_external_links() {
        // Tauri must not create it: a window made from the config has no new-window handler, and
        // every link the page opens in a new window would then do nothing.
        let config: serde_json::Value =
            serde_json::from_str(include_str!("../tauri.conf.json")).unwrap();
        let windows = config["app"]["windows"].as_array().unwrap();
        assert_eq!(windows.len(), 1);
        assert_eq!(windows[0]["label"], "main");
        assert_eq!(windows[0]["create"], false);
    }

    #[test]
    fn the_loading_page_speaks_the_four_languages() {
        let page = include_str!("../../ui/index.html");
        for key in ["waiting", "failed", "restore", "restoring", "restoreFailed"] {
            let entries = page.matches(&format!("{key}: \"")).count();
            assert_eq!(entries, 4, "{key} should be translated once per language");
        }
        for lang in ["en: {", "fr: {", "es: {", "de: {"] {
            assert!(page.contains(lang), "{lang} block missing");
        }
    }

    #[test]
    fn only_the_login_item_starts_hidden() {
        assert!(started_hidden(["picsou".to_owned(), "--hidden".to_owned()]));
        assert!(!started_hidden(["picsou".to_owned()]));
        assert!(!started_hidden([
            "picsou".to_owned(),
            "--hidden-not".to_owned()
        ]));
    }
}
