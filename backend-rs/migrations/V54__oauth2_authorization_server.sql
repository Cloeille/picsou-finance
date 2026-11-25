-- Spring Authorization Server 1.4.5 JDBC schema (resolved via
-- spring-boot-starter-oauth2-authorization-server:3.4.9 -> spring-security-oauth2-authorization-server:1.4.5).
--
-- Copied verbatim from the DDL shipped INSIDE that exact jar:
--   org/springframework/security/oauth2/server/authorization/client/oauth2-registered-client-schema.sql
--   org/springframework/security/oauth2/server/authorization/oauth2-authorization-schema.sql
--   org/springframework/security/oauth2/server/authorization/oauth2-authorization-consent-schema.sql
-- Column names, widths, and PKs are unchanged; only the dialect is adapted for PostgreSQL:
--   - `blob` -> `text`. This is the vendor's OWN documented Postgres substitution (see the comment
--     at the top of oauth2-authorization-schema.sql: "If using PostgreSQL, update ALL columns
--     defined with 'blob' to 'text', as PostgreSQL does not support the 'blob' data type").
--     It is also the ONLY substitution that works with JdbcOAuth2AuthorizationService's built-in
--     column-type auto-detection when constructed WITHOUT an explicit LobHandler (the constructor
--     Task 2 of this feature uses): that class inspects DatabaseMetaData.getColumns(...).DATA_TYPE
--     per column and only special-cases dataType == java.sql.Types.BLOB (2004) to convert a String
--     value to bytes before binding. Verified empirically against a real Postgres 16 container: the
--     PostgreSQL JDBC driver reports a `bytea` column as Types.BINARY (-2), never Types.BLOB, so a
--     `bytea` column here would make the framework bind a plain Java String as a BINARY parameter
--     and fail at write time. A `text` column reports as Types.VARCHAR (12), which the framework
--     binds correctly. Do NOT change these to `bytea`.
--   - `timestamp` kept as-is (PostgreSQL supports it directly; matches java.sql.Types.TIMESTAMP,
--     which is what the row mappers' rs.getTimestamp(...) / ps.setTimestamp(...) expect).
--   - `varchar(n)` kept as-is (PostgreSQL supports it directly).
-- No columns added, removed, or renamed relative to the jar's DDL.

CREATE TABLE oauth2_registered_client (
    id                             varchar(100) NOT NULL,
    client_id                      varchar(100) NOT NULL,
    client_id_issued_at            timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret                  varchar(200) DEFAULT NULL,
    client_secret_expires_at       timestamp DEFAULT NULL,
    client_name                    varchar(200) NOT NULL,
    client_authentication_methods  varchar(1000) NOT NULL,
    authorization_grant_types      varchar(1000) NOT NULL,
    redirect_uris                  varchar(1000) DEFAULT NULL,
    post_logout_redirect_uris      varchar(1000) DEFAULT NULL,
    scopes                         varchar(1000) NOT NULL,
    client_settings                varchar(2000) NOT NULL,
    token_settings                 varchar(2000) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE oauth2_authorization (
    id                              varchar(100) NOT NULL,
    registered_client_id            varchar(100) NOT NULL,
    principal_name                  varchar(200) NOT NULL,
    authorization_grant_type        varchar(100) NOT NULL,
    authorized_scopes               varchar(1000) DEFAULT NULL,
    attributes                      text DEFAULT NULL,
    state                           varchar(500) DEFAULT NULL,
    authorization_code_value        text DEFAULT NULL,
    authorization_code_issued_at    timestamp DEFAULT NULL,
    authorization_code_expires_at   timestamp DEFAULT NULL,
    authorization_code_metadata     text DEFAULT NULL,
    access_token_value              text DEFAULT NULL,
    access_token_issued_at          timestamp DEFAULT NULL,
    access_token_expires_at         timestamp DEFAULT NULL,
    access_token_metadata           text DEFAULT NULL,
    access_token_type               varchar(100) DEFAULT NULL,
    access_token_scopes             varchar(1000) DEFAULT NULL,
    oidc_id_token_value             text DEFAULT NULL,
    oidc_id_token_issued_at         timestamp DEFAULT NULL,
    oidc_id_token_expires_at        timestamp DEFAULT NULL,
    oidc_id_token_metadata          text DEFAULT NULL,
    refresh_token_value             text DEFAULT NULL,
    refresh_token_issued_at         timestamp DEFAULT NULL,
    refresh_token_expires_at        timestamp DEFAULT NULL,
    refresh_token_metadata          text DEFAULT NULL,
    user_code_value                 text DEFAULT NULL,
    user_code_issued_at             timestamp DEFAULT NULL,
    user_code_expires_at            timestamp DEFAULT NULL,
    user_code_metadata              text DEFAULT NULL,
    device_code_value               text DEFAULT NULL,
    device_code_issued_at           timestamp DEFAULT NULL,
    device_code_expires_at          timestamp DEFAULT NULL,
    device_code_metadata            text DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE oauth2_authorization_consent (
    registered_client_id  varchar(100) NOT NULL,
    principal_name        varchar(200) NOT NULL,
    authorities           varchar(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);
