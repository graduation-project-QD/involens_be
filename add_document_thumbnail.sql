-- Existing documents retain NULL; preview files are stored in MinIO.
ALTER TABLE documents ADD COLUMN IF NOT EXISTS thumbnail_object_key varchar(255);
