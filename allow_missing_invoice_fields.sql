-- Allow invoices and lines to preserve fields that AI could not recognize.
-- Document/invoice links and currency retain their existing constraints.
BEGIN;

ALTER TABLE invoices
    ALTER COLUMN seller_name DROP NOT NULL,
    ALTER COLUMN invoice_number DROP NOT NULL,
    ALTER COLUMN invoice_date DROP NOT NULL,
    ALTER COLUMN total_amount DROP NOT NULL;

ALTER TABLE invoice_item
    ALTER COLUMN item_name DROP NOT NULL,
    ALTER COLUMN quantity DROP NOT NULL,
    ALTER COLUMN unit_price DROP NOT NULL,
    ALTER COLUMN line_total DROP NOT NULL;

COMMIT;
