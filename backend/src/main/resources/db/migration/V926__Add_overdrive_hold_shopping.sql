-- Per-user opt-in for checking a waiting hold against the user's other libraries on each poll.
--
-- The Holds tab already offers this by hand ("check all other libraries", then move the hold): the
-- same title is often stocked by several of a user's libraries with very different queues, and a hold
-- placed at one of them can sit for months while another has it on the shelf. Doing it on the poll
-- turns a manual chore into something the schedule handles.
--
-- One flag and no threshold, deliberately: the automation applies exactly the rule the Holds tab
-- applies by hand — any shorter estimate is worth moving to — so a user cannot end up with the button
-- and the schedule disagreeing about what counts as better.
ALTER TABLE overdrive_auto_sync
    ADD COLUMN hold_shopping_enabled BOOLEAN NOT NULL DEFAULT FALSE;
