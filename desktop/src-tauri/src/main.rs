// No console window next to the app on Windows.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod supervisor;

use std::path::PathBuf;
use supervisor::{Layout, Shared};
use std::sync::{Arc, Mutex};
use tauri::webview::PageLoadEvent;
use tauri::{Manager, RunEvent, Url};

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

fn show_error(app: &tauri::AppHandle, message: &str) {
    *app.state::<Failure>().lock().unwrap_or_else(|e| e.into_inner()) = Some(message.to_owned());
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.eval(failure_script(message));
    }
}

fn main() {
    let stack: Shared = Default::default();
    let app = tauri::Builder::default()
        .manage(stack.clone())
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

    // ponytail: closing the window quits for now; the tray keeps Picsou running in the next PR (#587).
    app.run(move |_, event| {
        if let RunEvent::Exit = event {
            supervisor::stop(&stack);
        }
    });
}
