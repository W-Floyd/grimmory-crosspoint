-- Create OverDrive loan tracking table.
-- Loans are tracked locally (per Grimmory user) so they survive server restarts and can be
-- managed from the UI.

CREATE TABLE IF NOT EXISTS overdrive_loan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    overdrive_loan_id VARCHAR(255) NOT NULL,
    format_id VARCHAR(255),
    identity VARCHAR(255),
    user_id BIGINT,
    title VARCHAR(512),
    author VARCHAR(255),
    expire_date TIMESTAMP NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'BORROWED',
    isbn VARCHAR(13),
    book_id BIGINT,
    fulfilled BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_sync TIMESTAMP NULL,
    CONSTRAINT fk_overdrive_loan_book FOREIGN KEY (book_id) REFERENCES book(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Indexes for common query patterns
CREATE INDEX idx_overdrive_loan_identity ON overdrive_loan(identity);
CREATE INDEX idx_overdrive_loan_user ON overdrive_loan(user_id);
CREATE INDEX idx_overdrive_loan_user_state ON overdrive_loan(user_id, state);
CREATE INDEX idx_overdrive_loan_book_id ON overdrive_loan(book_id);
CREATE INDEX idx_overdrive_loan_expire ON overdrive_loan(expire_date);
