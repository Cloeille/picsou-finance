# Picsou iOS

Native SwiftUI client for a self-hosted [Picsou](../README.md) instance. Phase 1 covers
authentication (OAuth2 Authorization Code + PKCE) and a read-only dashboard.

## Requirements

- macOS with **Xcode 15+** (iOS 17 SDK)
- [XcodeGen](https://github.com/yonik/XcodeGen) — the `.xcodeproj` is generated, not committed:
  ```sh
  brew install xcodegen
  ```

## Generate & run

```sh
cd ios-app
xcodegen generate          # writes Picsou.xcodeproj from project.yml
open Picsou.xcodeproj
```

In Xcode: pick an iPhone simulator (or set your signing team to run on a device), then Run.

On first launch the app asks for your instance URL (e.g. `https://picsou.example.com`). It logs in
through your existing web login (password + TOTP) inside an in-app browser, stores the resulting
tokens in the Keychain, and gates access with Face ID.

> The backend must expose `/oauth2/**` (added to the bundled nginx config) over HTTPS. For local
> development against `http://…`, run the backend with `SECURE_COOKIES=false`.

## Demo mode (no backend)

Select the **`Picsou Demo`** scheme and Run. It boots straight into the dashboard backed by mock
data — no server URL, no login, no Face ID. This mirrors the web app's `VITE_DEMO_MODE` build flag:
the scheme defines the `DEMO` compilation condition, read by `AppConfig.isDemo`. Handy for
screenshots, demos, and UI work.

## Layout

```
Picsou/
  App/            @main entry + AppState (unconfigured → loggedOut → locked → ready)
  Core/
    Config/       ServerConfig (instance URL, health check)
    Networking/   APIClient (Bearer inject + refresh-on-401), APIError
    Auth/         OAuthService (ASWebAuthenticationSession + PKCE), TokenStore (Keychain),
                  BiometricGate (Face ID), PKCE, Keychain
    Formatting/   currency / percent formatters
  Models/         Codable mirror of the backend DTOs (money as Decimal)
  Features/       ServerSetup, Auth, Lock, Dashboard
PicsouTests/      PKCE, model decoding, API-client refresh
```

No third-party dependencies — URLSession, AuthenticationServices, LocalAuthentication, CryptoKit,
Security and Swift Charts only.
