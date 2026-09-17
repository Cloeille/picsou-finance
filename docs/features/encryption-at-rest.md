# Feature: Encryption at Rest

> Last updated: 2026-04-08

## Context

Picsou stores sensitive credentials in PostgreSQL: crypto exchange API keys/secrets, Trade Republic session/refresh tokens, and bank sync session IDs. A database compromise (backup leak, SQL injection, host access) would expose these credentials, granting attackers access to financial accounts. All sensitive fields are encrypted at the application layer using AES-256-GCM before storage.

## How it works

### CryptoEncryption component

`CryptoEncryption` is a Spring `@Component` that provides `encrypt(plainText)` and `decrypt(cipherText)` methods. It is injected into any service that handles sensitive credentials.

- **Algorithm**: `AES/GCM/NoPadding` -- authenticated encryption (confidentiality + integrity)
- **IV**: 12-byte random IV generated per encryption call
- **Tag**: 128-bit GCM authentication tag (tamper detection)
- **Storage format**: `Base64(IV || ciphertext || tag)` -- single string, stored in a `TEXT` column when a third party controls the plaintext length and in a sized `VARCHAR` when a known format bounds it (see *Column widths* below)
- **Key**: 256-bit symmetric key from `CRYPTO_ENCRYPTION_KEY` env var (Base64-encoded)
- **Startup behavior**: The application **refuses to start** if the key is not set. No plaintext fallback.

### What is encrypted

| Data | Entity | Column | Encrypted since |
|------|--------|--------|-----------------|
| Crypto exchange API key | `CryptoExchangeSession` | `api_key` | V15 (2026-04-08) |
| Crypto exchange API secret | `CryptoExchangeSession` | `api_secret` | V9 (initial) |
| Trade Republic session token | `TradeRepublicSession` | `session_token` | V15 (2026-04-08), `TEXT` since V86 |
| Trade Republic refresh token | `TradeRepublicSession` | `refresh_token` | V15 (2026-04-08), `TEXT` since V86 |
| DEGIRO session blob | `DegiroSession` | `session_blob` | V71 (2026-08-10), `TEXT` since V86 |

### What is NOT encrypted (and why)

- **Bank sync session IDs** (`requisition.requisition_id`): These are opaque references to Enable Banking sessions, not credentials. They cannot be reused to initiate new bank connections.
- **Wallet addresses**: Public blockchain data by nature.
- **User password**: BCrypt-hashed (not encrypted) -- correct approach for passwords.
- **Finary credentials**: Stored in environment variables, not in the database. Out of scope for DB-level encryption.

### Key files

- `backend/src/main/java/com/picsou/config/CryptoEncryption.java` -- AES-256-GCM encrypt/decrypt, key validation at startup
- `backend/src/main/java/com/picsou/service/CryptoExchangeSyncService.java` -- Encrypts apiKey + apiSecret on store, decrypts on read
- `backend/src/main/java/com/picsou/service/TradeRepublicSyncService.java` -- Encrypts session/refresh tokens on store, decrypts on read
- `backend/src/main/resources/db/migration/V15__widen_encrypted_columns.sql` -- Widens columns for encrypted values, truncates legacy plaintext

### Flow

```
Store credential:
  plainText --> CryptoEncryption.encrypt()
                  |
                  v
                generate random 12-byte IV
                  |
                  v
                AES-GCM encrypt with IV + key --> ciphertext + tag
                  |
                  v
                Base64(IV || ciphertext || tag) --> TEXT or VARCHAR column

Read credential:
  TEXT or VARCHAR column --> CryptoEncryption.decrypt()
                       |
                       v
                     Base64 decode --> IV || ciphertext || tag
                       |
                       v
                     AES-GCM decrypt with IV + key --> plainText
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Application-level encryption | Granular, works with any PostgreSQL, protects against DB dumps | pgcrypto (key in SQL), LUKS (only protects against disk theft) |
| AES-256-GCM | Authenticated encryption, standard, no padding oracle | AES-CBC (no integrity check), ChaCha20 (less Java support) |
| Mandatory key at startup | Prevents silent plaintext storage in misconfigured deployments | Optional key with warning (easy to miss, critical for finance app) |
| Truncate legacy data in V15 | Clean break; old plaintext values cannot be decrypted by new code | Graceful migration (complex, needs key in SQL migration) |
| Single shared key | Simple; sufficient for single-user self-hosted app | Per-field or per-entity keys (unnecessary complexity) |

## Gotchas / Pitfalls

- **Key is mandatory**: The app will not start without `CRYPTO_ENCRYPTION_KEY`. Generate with: `openssl rand -base64 32`.
- **Lost key = re-enter credentials**: If the encryption key is lost, encrypted data cannot be recovered. The user must re-add crypto exchanges and re-authenticate Trade Republic.
- **V15 truncates existing sessions**: After deploying V15, all crypto exchange sessions and TR sessions are cleared. Users must re-enter API keys and re-authenticate TR. This is a one-time migration cost.
- **Column widths**: An encrypted value is `4 * ceil((n + 28) / 3)` characters for `n` plaintext bytes (Base64 of a 12-byte IV + ciphertext + 16-byte tag), so a `VARCHAR(2000)` silently caps the plaintext at 1472 bytes and a `VARCHAR(4000)` at 2972. That cap fails the INSERT *after* a successful login once a third party grows its token (#115), so the columns whose plaintext length a third party controls are `TEXT` since V86: `trade_republic_session.session_token` / `refresh_token` and `degiro_session.session_blob` (Amundi, Bourse Direct and BoursoBank session state already were). Only values bounded by a known format stay sized: `api_key` 500 and `api_secret` 500 (largest known exchange key ~230 chars, ~350 once encrypted). Do not reintroduce a `length` on a third-party token column.
- **No key rotation**: A single key is used for all encryption. If compromised, all secrets must be re-encrypted. No versioning mechanism exists yet.
- **Decryption failure on corrupt data**: If a stored value is not valid AES-GCM ciphertext (e.g., legacy plaintext), `decrypt()` throws `RuntimeException`. Callers should handle this gracefully (crypto sync sets status to ERROR; TR treats it as expired session).

## Tests

- No dedicated tests for the encryption-at-rest integration (CryptoEncryption is tested via `CryptoEncryptionTest` for roundtrip correctness)
- Manual verification: add an exchange, inspect the `api_key` and `api_secret` columns in the database -- both should be Base64 strings, not readable keys

## Links

- Related ADR: [AES-256-GCM for crypto secrets](../decisions/2026-03-01-aes-gcm-crypto-secrets.md)
- Related feature: [Crypto tracking](./crypto-tracking.md)
- Related feature: [Trade Republic](./trade-republic.md)
