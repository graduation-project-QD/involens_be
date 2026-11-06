-- Apply before deploying notification support. No historical notifications are backfilled.
BEGIN;
CREATE TABLE IF NOT EXISTS notifications (
    id uuid PRIMARY KEY,
    recipient_id bigint NOT NULL REFERENCES users(id),
    type varchar(255) NOT NULL,
    item_count bigint NOT NULL DEFAULT 0 CHECK (item_count >= 0),
    read_at timestamptz,
    visible boolean NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_notification_unread_group
    ON notifications(recipient_id, type) WHERE read_at IS NULL AND visible = true;
CREATE INDEX IF NOT EXISTS ix_notification_owner_history
    ON notifications(recipient_id, updated_at DESC, id DESC) WHERE visible = true;
CREATE TABLE IF NOT EXISTS notification_items (
    id uuid PRIMARY KEY,
    notification_id uuid NOT NULL REFERENCES notifications(id),
    source_id uuid NOT NULL,
    document_id uuid REFERENCES documents(id),
    filename varchar(255),
    error_code varchar(255),
    error_message text,
    active boolean NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT uk_notification_item_source UNIQUE(notification_id, source_id)
);
CREATE INDEX IF NOT EXISTS ix_notification_item_document ON notification_items(document_id) WHERE active = true;
CREATE INDEX IF NOT EXISTS ix_notification_item_listing ON notification_items(notification_id, created_at DESC, id DESC);
COMMIT;
