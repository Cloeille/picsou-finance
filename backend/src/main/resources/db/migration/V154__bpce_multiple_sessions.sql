-- A member can hold several Caisse d'Epargne and several Banque Populaire connections, one
-- sidecar_session row each (a row costs a real bank login, so there is no cap). Every other bank
-- keeps one session per member: its lookups return a single row and rely on this.
ALTER TABLE sidecar_session DROP CONSTRAINT uq_sidecar_session_bank_member;

CREATE UNIQUE INDEX uq_sidecar_session_bank_member ON sidecar_session (bank, member_id)
    WHERE bank NOT IN ('CAISSE_EPARGNE', 'BANQUE_POPULAIRE');
