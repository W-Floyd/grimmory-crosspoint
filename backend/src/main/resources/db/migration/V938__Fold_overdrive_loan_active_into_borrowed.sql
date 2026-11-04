-- Imports wrote ACTIVE where sync wrote BORROWED for the same live loan. The state is now an enum
-- with BORROWED as the only live value.
UPDATE overdrive_loan SET state = 'BORROWED' WHERE state = 'ACTIVE';
