--liquibase formatted sql

--changeset matigfv:8
ALTER TABLE files ADD COLUMN folder_color VARCHAR(16) NULL;
ALTER TABLE files ADD COLUMN folder_icon VARCHAR(32) NULL;
--rollback ALTER TABLE files DROP COLUMN folder_color;
--rollback ALTER TABLE files DROP COLUMN folder_icon;
