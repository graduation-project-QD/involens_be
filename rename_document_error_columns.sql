-- Run once on the existing PostgreSQL database BEFORE starting the updated application.
-- Renaming columns preserves existing upload error codes and messages.
BEGIN;
ALTER TABLE documents RENAME COLUMN upload_error_code TO error_code;
ALTER TABLE documents RENAME COLUMN upload_error_message TO error_message;
COMMIT;
