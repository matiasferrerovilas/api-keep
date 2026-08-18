--liquibase formatted sql

--changeset matigfv:7
ALTER TABLE files ADD COLUMN is_favorite BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE files ADD COLUMN last_accessed_at DATETIME NULL;
CREATE INDEX idx_files_workspace_id_favorite ON files (workspace_id, is_favorite);
CREATE INDEX idx_files_workspace_id_last_accessed_at ON files (workspace_id, last_accessed_at);
--rollback DROP INDEX idx_files_workspace_id_favorite ON files;
--rollback DROP INDEX idx_files_workspace_id_last_accessed_at ON files;
--rollback ALTER TABLE files DROP COLUMN is_favorite;
--rollback ALTER TABLE files DROP COLUMN last_accessed_at;
