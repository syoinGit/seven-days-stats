-- Core Keeper connections read from the server log (docs/OCTARINE.md).
-- steam_id is the raw SteamID64. It is used only to recognise the same member across names and
-- games; pages, APIs, AI prompts and logs show the name instead (admins see the last 4 digits).
CREATE TABLE M_CK_PLAYER (
    id BIGSERIAL PRIMARY KEY,
    steam_id VARCHAR(20) NOT NULL UNIQUE,
    -- The 7DTD player with the same SteamID, when one exists.
    player_id BIGINT REFERENCES M_PLAYER(id) ON DELETE SET NULL,
    -- A portal account linked by an admin, for members who do not play 7DTD.
    web_account_id BIGINT UNIQUE REFERENCES M_WEB_ACCOUNT(id) ON DELETE SET NULL,
    player_name VARCHAR(120) NOT NULL,
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_m_ck_player_player ON M_CK_PLAYER (player_id);

CREATE TABLE T_CK_SESSION (
    id BIGSERIAL PRIMARY KEY,
    ck_player_id BIGINT NOT NULL REFERENCES M_CK_PLAYER(id) ON DELETE CASCADE,
    player_name VARCHAR(120) NOT NULL,
    connected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- NULL while connected.
    disconnected_at TIMESTAMP WITH TIME ZONE,
    -- The game's reason (App_Min, ...), or RECONNECTED / SERVER_STOPPED when the app closed it.
    end_reason VARCHAR(40),
    -- The log stream re-reads the last hour on reconnect; the same connect line is stored once.
    CONSTRAINT uq_t_ck_session_start UNIQUE (ck_player_id, connected_at)
);

CREATE INDEX idx_t_ck_session_connected ON T_CK_SESSION (connected_at);
CREATE INDEX idx_t_ck_session_open ON T_CK_SESSION (disconnected_at);
