--liquibase formatted sql

--changeset matigfv:10
CREATE TABLE file_activity (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_id         BIGINT       NOT NULL,
    workspace_id    BIGINT       NOT NULL,
    action          VARCHAR(20)  NOT NULL,
    actor_user_id   BIGINT       NOT NULL,
    actor_email     VARCHAR(255) NOT NULL,
    file_name       VARCHAR(255) NOT NULL,
    detail          VARCHAR(500) NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_file_activity_file FOREIGN KEY (file_id) REFERENCES files (id) ON DELETE CASCADE,
    CONSTRAINT chk_file_activity_action CHECK (action IN ('UPLOADED', 'RENAMED', 'MOVED', 'DELETED', 'RESTORED', 'SHARED', 'UNSHARED'))
);
CREATE INDEX idx_file_activity_file_id ON file_activity (file_id, created_at);
--rollback DROP TABLE file_activity;
