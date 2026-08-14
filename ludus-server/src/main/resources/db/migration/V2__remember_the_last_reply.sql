-- What the engine thought about the move it just played.
--
-- Until now this was returned once, in the response to the move that provoked it, and then lost. That was
-- invisible while a game only ever lived in one browser tab. It stopped being invisible when games became
-- reachable by URL: reload a game, or open somebody's link, and the panel that exists to show the engine's
-- reasoning shows nothing at all.
--
-- Nullable rather than defaulted to zero: a game where the engine has not moved yet has no last reply, and
-- "no answer" and "an answer of zero centipawns" are different facts. Zero is a perfectly ordinary score.

-- The move is stored rather than taken as "the last one played". Those are the same move almost always
-- and not always: a human move that ends the game is the last one played, and the engine never answered
-- it, so inferring would attach the engine's previous reasoning to somebody else's move.

ALTER TABLE games ADD COLUMN last_move   VARCHAR(6);
ALTER TABLE games ADD COLUMN last_score  INTEGER;
ALTER TABLE games ADD COLUMN last_depth  INTEGER;
ALTER TABLE games ADD COLUMN last_nodes  BIGINT;
