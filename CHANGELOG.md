# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **File/folder activity log** — who uploaded, renamed, moved, deleted, restored, or (un)shared a
  node, and when. New `FileActivity` entity (table `file_activity`, migration 010) and
  `GET /v1/folders/{id}/activity` (`FileActivityResponse[]`, most recent first, gated by the same
  `verifyReadAccess` as reading the file itself). Written explicitly at each call site
  (`FileService.uploadFile/renameNode/moveNode/deleteNode/restoreNode`,
  `SharingService.shareFile/revokeShare`, `UserSharingService.shareWithUser/revokeShare`) via a
  new `FileActivityLogService.record(...)`, rather than derived from the existing
  `FileTreeChangedEvent` — that event's `FILE_ADDED`/`FILE_UPDATED`/`FILE_DELETED` types are too
  coarse to tell a move apart from a rename, or either from a favorite toggle, so this needed its
  own explicit write path instead of "just" listening to what already publishes.
- **User-to-user file/folder sharing**, with optional expiration — previously "compartir" only
  meant app-to-app (`AppFileShare`, keyed by `apiName`); this is the first way to actually share
  with another *person*. New `UserFileShare` entity (table `user_file_shares`, migration 009):
  `file_id`, `shared_with_user_id`, `shared_with_email` (denormalized — api-keep has no local
  users table to join against), `permission` (reuses `SharePermission`), `expires_at` (nullable,
  `null` = never expires), `created_by`.
  - `FileService.verifyAccess`'s existing fallback (native workspace membership → app share) gained
    a third path: walk from the target file up through `parentId`, checking each ancestor
    (inclusive) for a non-expired `UserFileShare` granting the required permission. This is what
    makes sharing a folder cover everything inside it, including files added *after* the share was
    created — nothing is precomputed, the check runs live against the tree as it currently is.
  - New `POST/GET /v1/shares/users`, `DELETE /v1/shares/users/{id}` (`UserSharingController`/
    `UserSharingService`, mirroring `SharingController`/`SharingService`) — creating a share
    resolves the target by email via a new api-identity call (`IdentityClient.lookupUser`, backed
    by api-identity's new `GET /v1/users/lookup`), 404s if no account matches.
  - `GET /v1/shares/users/shared-with-me` — how a recipient discovers what's been shared with
    them, since they're not necessarily a member of the owner's workspace and can't see the normal
    tree. `GET /v1/folders/{id}/subtree` (`FileController`) — a nested `FileDTO` rooted at one
    node, gated by the same share-aware `verifyReadAccess` instead of workspace-membership-only,
    letting a recipient browse into a shared folder in the app rather than only downloading it as
    a zip.
  - `purgeExpiredUserShares()` (`@Scheduled`, hourly, same cadence as `purgeExpiredTrash()`) sweeps
    expired grants.
  - `FileDTO.Metadata` gained `sharedWithUserCount` (active grants only), populated the same
    batch-map way `shareWith` already was — a lightweight signal for the owner's UI, the full
    grantee list is fetched on demand.
- Custom color/icon per folder: `FileEntity` gained `folder_color`/`folder_icon` (migration 008,
  both nullable, only meaningful for `FileType.FOLDER` rows). New
  `PATCH /v1/folders/{id}/customization` (`SetFolderCustomizationRequest{color,icon}`, both
  independently nullable so sending `null` for one clears just that one) — 400s via
  `BusinessException` if the target isn't a folder. Backend doesn't validate `color`/`icon` against
  any fixed palette/set; that's left to the client, same as other free-form display metadata.

### Changed
- CORS allowed origins moved out of `SecurityConfiguration.corsConfigurationSource()` and into
  config (new `CorsProperties`, `@ConfigurationProperties(prefix = "app.cors")`, same pattern
  already applied to api-identity and api-movements this round): `application.yaml` keeps the
  current 3 origins as the dev/base default, `application-prod.yaml` now reads
  `app.cors.allowed-origins` from the `CORS_ALLOWED_ORIGINS` env var instead of a fixed prod value
  baked into the Java list. A self-hoster's own frontend origin no longer requires editing and
  recompiling `SecurityConfiguration.java`.
- Keep is now gated to `ROLE_ADMIN`/`ROLE_FAMILY` at the security-filter level — added
  `.requestMatchers("/v1/**").hasAnyRole("ADMIN", "FAMILY")` in `SecurityConfiguration` (below the
  existing `/v1/onboarding/**` matcher, which still allows `GUEST` through). A `GUEST` user hitting
  any other endpoint now gets a 403 instead of a normal authenticated response — Keep never had a
  concept of a `GUEST`-accessible workspace to begin with; fe-movements no longer shows the link to
  Keep for that role either, and fe-keep's own route guards were tightened to match.

### Added
- `DELETE /v1/shares/{id}` — revokes a file/folder share (`SharingService.revokeShare`). Previously
  `SharingController` only had `POST` (create) and `GET` (list); once another app had `READ_WRITE`
  on a file, the only way to take it back was editing the database by hand. Same permission check as
  the other share endpoints (caller must belong to the file's workspace).

### Changed
- `FileService.searchFiles` no longer loads the entire workspace tree to build each result's
  breadcrumb — it now resolves only the ancestor chain of the matched nodes, level by level
  (`loadAncestors`, batched via `findAllById`, deduping shared ancestors across matches), instead of
  `findByWorkspaceIdAndDeletedAtIsNull` over the whole workspace. Fine at home-lab scale either way,
  but this scales with result-set depth instead of total file count.

### Added
- Workspace member invitations, mirroring api-movements: `POST /v1/workspace/{id}/invitations`
  (send), `GET /v1/workspace/invitations` (list pending), `PATCH /v1/workspace/invitations/{id}`
  (accept/reject), `DELETE /v1/workspace/{id}/members/{userId}` (remove a member), and
  `DELETE /v1/workspace/{id}` (leave a workspace) — none of these existed before; api-keep only
  proxied workspace creation and listing. New `WorkspaceInvitationDTO`/`WorkspaceSendInvitationDTO`/
  `AcceptRejectInvitationDTO` records, `InvitationStatus` enum, and `WorkspaceMemberDTO.Metadata`
  now includes `memberDetails` (userId/email/role per member), needed to target the remove-member
  call. `UserSettingService` gained `deleteByKey`/`deleteByKeyForUser`, used to clear a user's
  `DEFAULT_WORKSPACE` setting when they leave/get removed from it.
- Live updates for the above: a new RabbitMQ consumer (`InvitationPublishServiceWebSocket`,
  `WorkspaceMembershipPublishServiceWebSocket`) binds queues to api-identity's `identity.topic`
  exchange (same exchange api-movements already consumes from) for `identity.invitation.sent`,
  `identity.invitation.accepted`, and `identity.member.removed`, pushing each over the existing
  STOMP broker at `/ws` so the frontend doesn't need to poll. New `EventType` values
  (`INVITATION_ADDED`, `MEMBERSHIP_UPDATED`, `WORKSPACE_LEFT`) required adding a `default`-less
  exhaustive-switch arm to the pre-existing `FileTreePublishServiceWebSocket`, since it shares the
  same `EventType` enum with file-tree events.
- Onboarding now calls api-identity's new `POST /v1/onboarding/start` when it needs to create a new
  workspace, instead of two separate requests (`POST /v1/users` then `POST /v1/workspaces`) — closes
  the window where a failure between the two calls could leave a user with no workspace. The
  join-an-existing-workspace path (`existingDefaultWorkspaceId` set, no new workspace names) still
  calls `IdentityClient.createLogInUser` alone, since there's nothing to make atomic with in that
  case. `UserService.createLogInUser` renamed to `buildUserToAdd` and is now a pure builder with no
  HTTP call; `WorkspaceService.createWorkspaces` (batch) removed, only reachable from the old
  onboarding path.

## [1.4.0] - 2026-08-18

### Added

- `GET /v1/folders/search?workspaceId=&query=` — case-insensitive name search backed by an
  indexed SQL `LIKE` (new `idx_files_workspace_id_name` composite index), scoped to the workspace.
  Also matches against a new `content` column populated at upload time for `.txt`/`.md` files
  only (plain-text extraction, no parsing library — PDFs/images/other binaries are explicitly out
  of scope, would need something like Apache Tika). Each result includes a resolved breadcrumb
  path so the client doesn't have to walk `parentId` itself.
- Favorites: `is_favorite` boolean on `FileEntity`, `PATCH /v1/folders/{id}/favorite` to toggle,
  `GET /v1/folders/favorites?workspaceId=` to list.
- Recently-accessed tracking: `last_accessed_at` on `FileEntity`, updated only on an actual
  download/open (`FileService#downloadFile`) — never on tree listing, so it reflects genuine
  access. `GET /v1/folders/recent?workspaceId=&limit=` (default 20) lists non-null-access files
  ordered by most recent first.
- New indexes: `idx_files_workspace_id_name`, `idx_files_workspace_id_favorite`,
  `idx_files_workspace_id_last_accessed_at`.

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

[1.4.0]: https://github.com/matiasferrerovilas/api-file-share/releases/tag/v1.4.0
[1.3.0]: https://github.com/matiasferrerovilas/api-file-share/releases/tag/v1.3.0
[1.2.1]: https://github.com/matiasferrerovilas/api-file-share/releases/tag/v1.2.1
