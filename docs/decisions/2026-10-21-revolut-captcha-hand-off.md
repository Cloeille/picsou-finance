# ADR: Revolut captcha — hand it to the member through a remote view

> Date: 2026-10-21
> Status: ✅ Active

## Context

On 2026-10-21 the Revolut sidecar submitted a phone number and a passcode, then waited its full 300 s
for a mobile approval that never came. Revolut had shown an hCaptcha image challenge to the automated
browser, and no push is sent until it is answered. The detection added in October (a CSS selector on
`iframe[src*='hcaptcha.com']`, evaluated once, before the passcode) did not fire. The challenge also
appears on "Continue with passkey" at `sso.revolut.com/signin`.

Constraint: no captcha solving by AI or by a paid service (2Captcha, CapSolver…). The way forward is to
trigger the challenge less and to show it to the member when it does appear.

## Decision

1. **Detect** by listing `page.frames` and checking that a frame of an hCaptcha host is really on
   screen (size, clipping, hidden / transparent ancestors across frames), on every poll of every wait
   — including the approval wait.
2. **Hand off** to the member: open a remote view of the sidecar's virtual display (x11vnc +
   websockify + noVNC, port 6080, up only during the hand-off, fresh password each time), publish its
   port and password on `/progress`, and let the member solve the captcha in their own browser. The
   session is saved in the persistent profile and reused by later syncs.
3. **Trigger it less**: one pinned browser fingerprint per member, stored beside the profile and reused
   at every launch; at most one failed automated login per member per day (`429 LOGIN_PACED`).

## Alternatives considered

### Solver service or AI
- **Pros**: fully automatic.
- **Cons**: forbidden by the project constraint; also fragile and a policy risk for the account.

### Report the captcha and ask the member to retry later (what #243 did)
- **Pros**: nothing new to run.
- **Cons**: the captcha is not going away on its own; the member is locked out of the sync.

### Headful login mode on the member's own machine (export the cookies)
- **Pros**: no remote display.
- **Cons**: a session created on another machine and fingerprint is the thing Revolut invalidates; the
  profile and its identity must be created where they are used.

### Chrome DevTools screencast streamed to the Picsou page
- **Pros**: no VNC stack.
- **Cons**: has to relay mouse and keyboard events, which is the part captcha widgets are sensitive to;
  a custom protocol to write and maintain.

### Debian's `novnc` package
- **Pros**: no download at build time.
- **Cons**: depends on nodejs (~55 MB) for a folder of static files. The pinned upstream release
  (SHA-256 checked) is used instead.

## Reasoning

x11vnc + websockify + noVNC is a standard, maintained chain: real mouse and keyboard input into the very
browser that holds the profile, with a browser client and no code of ours in the input path. Detection
by frame geometry is needed because the sign-in page always carries two hCaptcha frames (an invisible
checkbox and a parked challenge); presence alone says nothing.

## Trade-offs accepted

- Port 6080 of the sidecar is published. It listens only during a hand-off, behind a random one-time
  password, and the password reaches only the signed-in member through the progress endpoint. It is
  plain HTTP/WebSocket (like Picsou's own port 8080 without the optional `tls` profile): a member who
  exposes Picsou beyond the home network must put the port behind the same reverse proxy or not use
  the hand-off remotely.
- One display, so one hand-off at a time; a second member meeting a captcha meanwhile gets the plain
  `HUMAN_VERIFICATION_REQUIRED`.
- A failed login blocks fresh logins for that member until the next day (Europe/Paris), even if the
  cause was a wrong attempt. Deleting `<member>.login-failed` lifts it.
- The pinned fingerprint cannot be proven to lower the captcha rate from the code; it removes one known
  cause (a new device at every launch). Measured in production, not asserted here.

## Consequences

- `services/revolut-auth`: new `captcha.py`, `member_state.py`, `viewer.py`, `fetch_novnc.py`; image adds
  `x11vnc`, `websockify`, noVNC; Xvfb now 1600×900.
- Backend: `AWAITING_HUMAN` phase, `SyncProgress.viewerPort/viewerPassword`, `LOGIN_PACED` mapping,
  adapter timeout 480 s → 840 s.
- Frontend: a link to the view while the phase is `AWAITING_HUMAN`; two new messages in four locales.
- CI: the sidecar's pytest suite now runs (`revolut-sidecar` job); it did not run anywhere before.
