--liquibase formatted sql

--changeset matigfv:5
ALTER TABLE files ADD COLUMN deleted_at DATETIME NULL;
CREATE INDEX idx_files_deleted_at ON files (deleted_at);
--rollback DROP INDEX idx_files_deleted_at ON files;
--rollback ALTER TABLE files DROP COLUMN deleted_at;
