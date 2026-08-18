--liquibase formatted sql

--changeset matigfv:6
-- `content` holds extracted plain text for .txt/.md files only (see FileService for the scope
-- decision) so search can match file content, not just names.
ALTER TABLE files ADD COLUMN content TEXT NULL;
-- Backs `GET /v1/folders/search`: every search is scoped to a workspace first, so leading with
-- workspace_id lets the engine narrow to that workspace before scanning names.
CREATE INDEX idx_files_workspace_id_name ON files (workspace_id, name);
--rollback DROP INDEX idx_files_workspace_id_name ON files;
--rollback ALTER TABLE files DROP COLUMN content;
