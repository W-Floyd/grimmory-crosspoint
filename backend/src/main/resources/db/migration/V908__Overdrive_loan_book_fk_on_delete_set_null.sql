-- Allow deleting a book that an OverDrive loan references.
--
-- V149 created fk_overdrive_loan_book with no ON DELETE action, so deleting a book that a loan points
-- at fails with a foreign-key violation (error 1451). A loan is independent of the library book it was
-- imported into, so drop and recreate the constraint with ON DELETE SET NULL: deleting the book simply
-- clears the loan's book_id (unlinking it) while the loan record itself survives.

ALTER TABLE overdrive_loan DROP FOREIGN KEY IF EXISTS fk_overdrive_loan_book;

ALTER TABLE overdrive_loan
    ADD CONSTRAINT fk_overdrive_loan_book
        FOREIGN KEY (book_id) REFERENCES book(id) ON DELETE SET NULL;
