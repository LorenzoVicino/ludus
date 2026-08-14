-- Games, stored as their starting position plus the moves played.
--
-- Not as a current position: a draw by repetition needs to know which positions have occurred and the
-- fifty-move counter needs the history that produced it, and a FEN on its own has forgotten both. The
-- fen column below is derived on every write and exists for reading the table by hand, never as the
-- source of truth.

CREATE TABLE games (
    id              UUID         PRIMARY KEY,
    start_fen       VARCHAR(100) NOT NULL,
    moves           TEXT         NOT NULL DEFAULT '',
    fen             VARCHAR(100) NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    difficulty      VARCHAR(16)  NOT NULL,
    human_is_white  BOOLEAN      NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,

    -- Optimistic locking. Two clicks arriving together must not both play a move; the second write
    -- fails on a stale version instead of overwriting the first.
    version         BIGINT       NOT NULL DEFAULT 0
);

-- The "recent games" list orders by this, and it is the only query that scans rather than looks up.
CREATE INDEX idx_games_updated_at ON games (updated_at DESC);

-- Statuses are read as an enum name by the application; the constraint keeps a typo in a migration or a
-- hand-written UPDATE from producing a row nothing can load.
ALTER TABLE games ADD CONSTRAINT chk_games_status CHECK (status IN (
    'IN_PROGRESS', 'WHITE_WON', 'BLACK_WON', 'DRAW_STALEMATE', 'DRAW_FIFTY_MOVE',
    'DRAW_REPETITION', 'DRAW_INSUFFICIENT_MATERIAL', 'RESIGNED'
));

ALTER TABLE games ADD CONSTRAINT chk_games_difficulty CHECK (difficulty IN (
    'BEGINNER', 'CASUAL', 'CLUB', 'STRONG', 'MAXIMUM'
));
