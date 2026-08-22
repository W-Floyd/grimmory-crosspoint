-- A queue of titles the user wants, worked through by the poller as cards become able.
--
-- Borrowing a list of books by hand is the thing the rate limits punish: a person who finds twenty
-- titles at once clicks twenty times in a minute, which is exactly the burst OverDrive objects to.
-- The bag turns that into a standing intention the schedule drains at a defensible pace, a few at a
-- time, only on cards with budget left.
--
-- Ordering is FIFO by position, but a blocked entry never stalls the ones behind it: a title whose
-- libraries are all out of copies would otherwise hold up a queue of books that could be borrowed
-- today. It keeps its place for the next pass rather than being pushed to the back.
--
-- A title nobody can lend right now gets a hold placed for it instead, and stays in the bag until
-- that hold comes in. That is what makes the bag a want-list rather than a list of things to try
-- once: hold_card_id records where the hold went, so the entry is not held twice.
CREATE TABLE overdrive_bookbag (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    title_id      VARCHAR(255) NOT NULL,
    title         VARCHAR(1024),
    author        VARCHAR(255),
    -- Sort key. Gaps are fine: entries are ordered by it, never counted with it.
    position      INT          NOT NULL DEFAULT 0,
    -- The card a hold was placed on while waiting for a copy, or null if none has been.
    hold_card_id  VARCHAR(255),
    hold_placed_at TIMESTAMP   NULL,
    -- Why the last pass could not borrow it, for the user to read. Cleared on success.
    last_note     VARCHAR(512),
    last_tried_at TIMESTAMP    NULL,
    created_at    TIMESTAMP    NULL,
    PRIMARY KEY (id),
    -- One entry per title per user: adding a book already in the bag should move nothing.
    CONSTRAINT uq_overdrive_bookbag_user_title UNIQUE (user_id, title_id)
);

CREATE INDEX idx_overdrive_bookbag_user_position ON overdrive_bookbag (user_id, position);
