# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.3.0] - 2026-08-18

### Added

- `StorageAdapter` seam (`api.m2.file.service.storage`) between `FileService` and disk I/O, with
  `LocalFsStorageAdapter` as the sole implementation for now — decouples the app from local disk
  so a future S3/NAS-backed adapter can be swapped in without touching `FileService`. Pure refactor:
  no externally-observable behavior changed.
- Per-workspace storage quota (`app.storage.workspace-quota`, default 5GB): uploads are rejected
  once a workspace's total non-deleted file size would exceed the configured limit.
- `GET /v1/folders/usage?workspaceId={id}` — reports `usedBytes`/`quotaBytes` for a workspace, for
  clients to render a usage indicator.
- Demo mode (`--spring.profiles.active=demo`): seeds sample folders/files against the suite-wide
  shared demo workspace (id 1) on startup, idempotently.

## [1.2.1] - Baseline

Snapshot of the feature set prior to this changelog's introduction — see README for full detail.

### Added

- File/folder tree per workspace on local disk, addressed by an auto-created root (`Home`).
- Multipart upload (configurable max size), single-file download, and on-the-fly zip download for
  folders.
- Move, rename, delete with automatic relocation of descendants.
- SHA-256 checksums computed on upload; duplicate-content uploads within a workspace are rejected.
- Soft-delete trash with scheduled purge after a configurable retention window, and restore.
- App-to-app sharing: grant another app `READ`/`WRITE`/`READ_WRITE` on a specific file/folder,
  enforced on every read/write.
- Real-time updates over WebSocket (STOMP/SockJS) on upload, rename, move, and delete.
- Cross-app sharing events published to RabbitMQ (`file-sharing.topic`).
- Keycloak OAuth2/JWT (RS256) resource server authentication; calling app resolved from the JWT's
  `app` claim.
- Swagger/OpenAPI documentation.
- Liquibase-managed database migrations.

[1.3.0]: https://github.com/matiasferrerovilas/api-file-share/releases/tag/v1.3.0
[1.2.1]: https://github.com/matiasferrerovilas/api-file-share/releases/tag/v1.2.1
