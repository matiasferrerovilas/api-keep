--liquibase formatted sql

--changeset matigfv:11
ALTER TABLE user_file_shares ADD COLUMN expiry_reminder_sent_at TIMESTAMP NULL;
--rollback ALTER TABLE user_file_shares DROP COLUMN expiry_reminder_sent_at;
