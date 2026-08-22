-- A thirty-day ceiling, which the evidence says is the one that actually bites.
--
-- This deployment's two churning refusals came at 148 and 144 borrows in the rolling thirty days
-- before each. Nothing shorter explains them: the same card had already survived a 68-borrow week and
-- a 77-borrow rolling week without complaint, and the refused week was 50 borrows of 50 distinct
-- titles with no repeats. What changed was the running total — the account crossed ~145 in a month
-- for the first time, and was refused as it did.
--
-- Without this column the shorter ceilings cannot express that constraint at all: a weekly limit
-- loose enough to be usable still adds up past a monthly cap after four weeks, and then trips.
ALTER TABLE overdrive_card_limit
    ADD COLUMN max_per_month INT NULL;
