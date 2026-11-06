-- How many CORUM Life contracts the last successful sync left unwritten because
-- a manual "CORUM Life" account could not be adopted. The panel names them
-- instead of reporting a plain success; zero for any other sync.
ALTER TABLE corum_session
    ADD COLUMN last_sync_life_ambiguous INTEGER NOT NULL DEFAULT 0
        CONSTRAINT ck_corum_session_life_ambiguous CHECK (last_sync_life_ambiguous >= 0);
