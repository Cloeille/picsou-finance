# ADR: The integration switch shows the stored choice and stops only Picsou's own syncs

> Date: 2026-11-21
> Status: ✅ Active

## Context

In Admin > Integrations, each switch reported the stored `integration.{key}.enabled` flag
ORed with a check for credentials or a login session. Once an integration was set up, its
switch always read back on, and no code that syncs read the flag, so turning it off did
nothing (#666). V25, V62 and V95 had also seeded `false` for seven integrations on every
install, and those rows never had an effect.

## Decision

- The switch reports the stored flag (`IntegrationsService.isEnabled`). A missing row means
  Enabled, because installs configured through `.env` never wrote one; only an explicit
  `false` disables.
- `MemberSyncService.executeSource`, the one path of the scheduled, startup and assistant
  syncs, skips the sources of a Disabled integration and reports them `SKIPPED`
  "Disabled in Admin > Integrations" without calling the provider.
  `IntegrationsService.integrationOf` maps each source to the integration that owns it.
  Sources no switch controls always run.
- Pressing Sync, connecting an account and stored credentials are left alone.
- `V156` deletes every flag not stored as `true`, once.

## Alternatives considered

### Keep showing detected configuration, gate nothing

- **Pros**: no behaviour change on upgrade.
- **Cons**: the switch stays a control that does nothing, which is the bug.

### Gate every path, manual syncs and connections included

- **Pros**: Disabled would mean the provider is never called.
- **Cons**: every connector's endpoints and UI need a gate and a Disabled state; about five
  times the change for a switch that only needed to stop background work.

### Keep the seeded `false` rows

- **Pros**: no data migration.
- **Cons**: every install would silently stop syncing those seven integrations on upgrade,
  since no admin ever chose those values.

## Reasoning

The admin turns an integration off to stop Picsou from reaching that provider by itself.
Background syncs all pass through `MemberSyncService`, so one check there covers them. A
member who presses Sync is asking for it explicitly.

## Trade-offs accepted

- Disabled does not block a manual sync or a new connection.
- An admin who had stored a deliberate `false` sees the switch on again after V156 and turns
  it off once more. No such `false` ever took effect, so nothing they relied on changes.

## Consequences

`IntegrationsService` depends only on `AppSettingRepository`; the detection code
(`EnableBankingConfigProvider.isConfiguredLenient`, two `SidecarSessionRepository` queries)
is gone. A new connector that belongs to an integration adds its sources to
`integrationOf`. See [`GLOSSARY.md`](../../GLOSSARY.md) for Integration and Enabled.
