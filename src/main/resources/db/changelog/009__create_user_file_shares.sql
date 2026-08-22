--liquibase formatted sql

--changeset matigfv:9
CREATE TABLE user_file_shares (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_id              BIGINT       NOT NULL,
    shared_with_user_id  BIGINT       NOT NULL,
    shared_with_email    VARCHAR(255) NOT NULL,
    permission           VARCHAR(20)  NOT NULL,
    expires_at           TIMESTAMP    NULL,
    created_by           BIGINT       NOT NULL,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_file_shares_file FOREIGN KEY (file_id) REFERENCES files (id) ON DELETE CASCADE,
    CONSTRAINT chk_user_file_shares_permission CHECK (permission IN ('READ', 'WRITE', 'READ_WRITE')),
    CONSTRAINT uq_user_file_shares UNIQUE (file_id, shared_with_user_id)
);
CREATE INDEX idx_user_file_shares_file_id ON user_file_shares (file_id);
CREATE INDEX idx_user_file_shares_shared_with_user_id ON user_file_shares (shared_with_user_id);
CREATE INDEX idx_user_file_shares_expires_at ON user_file_shares (expires_at);
--rollback DROP TABLE user_file_shares;
