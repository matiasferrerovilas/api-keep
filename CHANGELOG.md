# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.10.1] - 2026-09-01

### Fixed
- `UserService.getMe()`'s `@Cacheable` key used
  `T(org.springframework.security.core.context.SecurityContextHolder)...` — a SpEL type
  reference, resolved via `Class.forName` at runtime. This app runs as a GraalVM native image, and
  a type not explicitly registered in the native-image reflection config throws
  `SpelEvaluationException: EL1005E: Type cannot be found` when looked up this way — even one
  actively used elsewhere in the app (Spring Security's own filter chain, in this case). Never
  reproduced under tests, which run on a normal JVM with no such closed-world reflection
  restriction (found live in api-movements, fixed here preemptively since both share the exact
  same pattern). Replaced with a `@userService.getAuthenticatedEmail()` bean-reference expression
  (new method), which resolves through the `BeanFactoryResolver` instead of `Class.forName` and
  needs no reflection registration at all.
- Same native-image reflection gap, different mechanism: several record/event types are only ever
  serialized via `SimpMessagingTemplate.convertAndSend(topic, Object)` (WebSocket) or deserialized
  via `@RabbitListener` — neither path is visible to Spring's AOT MVC-controller scanning, so none
  of `FileDTO`/`WorkspaceInvitationDTO`/`InvitationReceivedEvent`/`InvitationAcceptedReceivedEvent`/
  `MemberRemovedReceivedEvent`/`UserFileShareEvent`/`EventWrapper` were registered for reflection —
  this repo had no `RuntimeHints` registrar at all until now (api-movements already had one,
  missing the same handful of types — see its changelog). New `WebBindingRuntimeHints`, wired via
  `@ImportRuntimeHints` on `ApiKeepApplication`. Fixed preemptively — hadn't thrown here yet, but
  the exact same live crash api-movements hit (`UnsupportedFeatureError: Record components not
  available`) was only one WebSocket push away.

## [1.10.0] - 2026-08-31

### Added
- `sendInvitation` now requires a `role` (`COLLABORATOR`/`READ_ONLY`) alongside the email list —
  passed straight through to api-identity, which applies it to the membership when the invitee
  accepts. `WorkspaceInvitationDTO`/`WorkspaceSentInvitationDTO` (REST responses) and the
  WebSocket-pushed invitation payload now carry it too.

## [1.9.0] - 2026-08-31

### Added
- Wired up `CacheConfiguration.USER_CACHE`, which was declared but never actually used, to cache
  the two api-identity calls that fired on almost every request (`UserService.getMe()`,
  `WorkspaceService.verifyUserIsMemberOfWorkspace` — the latter called from `FileMembershipGuard`/
  `FileService` on nearly every file operation) for 5 hours, keyed per-caller (and per-workspace/
  user pair for membership) off the authenticated principal's email. Previously every one of those
  calls was a synchronous, uncached, timeout-less HTTP round-trip to api-identity — if it hung even
  briefly, this app couldn't serve a single operation despite the caller's JWT still being valid.
  A revoked membership is never masked by the cache: `verifyUserIsMemberOfWorkspace` only caches
  the successful (granted) outcome, so a removal always re-checks against api-identity immediately.

### Security
- `application-prod.yaml` hardcoded the RabbitMQ username/password in plain text
  (`api-keep`/`api-keep`) while `DB_USERNAME`/`DB_PASSWORD`/`REDIS_PASSWORD`/`CORS_ALLOWED_ORIGINS`
  right next to it were already env vars — same pattern already fixed in api-identity this round.
  Now `${RABBIT_USERNAME}`/`${RABBIT_PASSWORD}`, no default.

## [1.8.2] - 2026-08-30

### Fixed
- Downloading a folder as a zip included files that were already in the trash (soft-deleted, still
  physically on disk until `purgeExpiredTrash()`/`purgeNode()` actually removes them) — `zipDirectory`
  walked the real filesystem with no idea of `deletedAt`, treating disk as the source of truth where
  the rest of the domain treats the DB row as it. `StorageAdapter.zipDirectory` now takes the set of
  relative paths to include (computed by `FileService` from the same non-deleted pool
  `getPersonalFolder` uses), so it stays storage-agnostic while `FileService` keeps owning the "what
  counts as existing" decision.
- N+1 in `listTrash`/`listFavorites`/`listRecent`: each mapped every result through
  `toResponseNode`, which ran its own `userFileShareRepository.findByFileId` query per file just to
  count active shares — the same batching `getPersonalFolder` already did (`findByFileIdIn`, grouped
  once) had never been applied to these three sibling endpoints. New `toResponseNodes(List)` does the
  batch lookup once per call instead of once per row.

### Added
- `DELETE /v1/files/{id}/purge` — permanently deletes a single trashed node (and its whole subtree,
  for a folder) right now, instead of waiting for the next-day `purgeExpiredTrash()` sweep.
  Previously `restoreNode`/the automatic sweep were the only ways out of the papelera, so there was
  no lever to reclaim disk space on demand. fe-keep: a per-row "Eliminar ahora" button in the trash
  list, a bulk "Eliminar ahora" action on multi-select, and a top-level "Vaciar papelera" button
  that purges everything currently in the trash — each behind its own confirmation dialog.
- `GET /v1/workspace/invitations/sent` and `DELETE /v1/workspace/invitations/{invitationId}` proxy
  api-identity's new sent-invitations endpoints (`IdentityClient.getSentInvitations`/
  `cancelInvitation`), so a workspace owner/collaborator can list invitations they sent and cancel a
  still-pending one before the recipient responds.
- Real-time notification (STOMP, same infra as file-tree/invitations) when a user-file-share is
  created or is about to expire — previously `UserSharingService.shareWithUser` never published
  anything, so the recipient only found out by opening "Compartido conmigo" and looking. New
  `GET /topic/shares/users/{email}/new` topic, `UserFileShareEvent` record (carries an `eventType`
  of `USER_FILE_SHARED` or `USER_FILE_SHARE_EXPIRING`), `UserSharePublishServiceWebSocket`.
- `sendExpiringShareReminders()` (`FileService`, same hourly cadence as `purgeExpiredUserShares`)
  publishes the expiring-share event once for each grant expiring within the next 24h — previously
  neither the owner nor the recipient found out until access had already disappeared. New
  `expiry_reminder_sent_at` column on `user_file_shares` (migration 011) tracks which grants have
  already been reminded about, so the hourly job doesn't re-notify on every run; cleared by
  `updateShare` whenever the expiration actually changes, so extending a share re-arms the
  reminder for the new date.

### Security
- `/ws/**` was (and stays) `permitAll()` at the HTTP layer — required for SockJS's handshake/XHR
  fallback requests, which aren't the STOMP CONNECT frame itself — but nothing validated the STOMP
  frames flowing over the resulting session: no `ChannelInterceptor` checked CONNECT or SUBSCRIBE at
  all. Topics are addressed by workspace id or by another user's email in plain text
  (`/topic/files/{workspaceId}/new`, `/topic/shares/users/{email}/new`), so any client that opened
  the SockJS connection — authenticated or not — could subscribe to any topic and passively harvest
  file names, shares, and invitations from any workspace. Same "the frontend hides it but the
  backend doesn't validate it" pattern already closed for sharing this round, still open here. New
  `StompAuthChannelInterceptor` on the client-inbound channel: CONNECT now requires a valid Bearer
  JWT (same `JwtDecoder`/`JwtAuthenticationConverter` beans the HTTP filter chain already uses — the
  three frontends already send `Authorization: Bearer <token>` as a STOMP connect header, so no
  client-side change was needed), and every SUBSCRIBE is checked against the connected user before
  being allowed through — a workspace-scoped topic requires membership (verified against
  api-identity), an email-scoped topic requires the destination email to match the caller's own. A
  destination matching none of the known topic shapes is rejected by default rather than let
  through.
- `moveNode` only checked `verifyWriteAccess` on the node being moved — `resolveParent` validated
  that the destination belonged to the same workspace, but never that the caller actually had
  access to that specific folder. Someone holding only a `UserFileShare` write grant on a single
  file could relocate it into any folder in the workspace, including ones never shared with them.
  Now `moveNode` also calls `verifyWriteAccess` on the resolved destination folder before moving.
- Sharing (both app-to-app via `SharingService` and person-to-person via `UserSharingService`) is
  now enforced as `ROLE_ADMIN`-only at the backend (`@PreAuthorize("hasRole('ADMIN')")` on
  `shareFile`/`getShares`/`revokeShare` and `shareWithUser`/`getShares`/`updateShare`/
  `revokeShare`), matching fe-keep's UI gate — previously the frontend hid the "Compartir" menu
  from non-admins, but the endpoints themselves only checked workspace membership, so a FAMILY
  member could call them directly (curl/Postman) and it worked. Does not restrict
  `FileService.listSharedWithMe`/`getSubtree` — reading content already shared with you stays open
  to any workspace role, otherwise sharing with a non-admin would be pointless. Added a
  `AccessDeniedException` handler to `ErrorHandler` (was falling through to the generic 500
  catch-all, not 403) since this is the first `@PreAuthorize` used anywhere in this codebase.

### Added
- `PATCH /v1/shares/users/{id}` — changes a user-share's permission and/or expiration in place.
  Previously the only way to change either was `DELETE` + `POST`, which lost the share's identity
  and creation history. Not a partial patch — same replace-wholesale contract as creating one
  (`expiresAt: null` clears it). `UserSharingService.updateShare`, new
  `UpdateUserFileShareRequest{permission, expiresAt}`.

### Fixed
- `UserSharingService.shareWithUser` never checked whether the resolved target email was the
  caller's own — sharing a file with yourself created a pointless `UserFileShare` row that then
  showed up in your own "Compartido conmigo" pointing at content you already own. Now rejected with
  `BusinessException` (400).

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
- New `FileMembershipGuard.requireFileWithMembership` collapses the `fileRepository.findById(id)
  .orElseThrow(...)` + `workspaceService.verifyUserIsMemberOfWorkspace(...)` pair that was
  copy-pasted at every call site in `SharingService` and `UserSharingService` (7 sites total). New
  `FileService` private helpers `requireFileWithReadAccess`/`requireFileWithWriteAccess` do the same
  for the fuller `verifyReadAccess`/`verifyWriteAccess` pattern duplicated across 9 of its own call
  sites (uploadFile/renameNode/deleteNode/restoreNode/purgeNode/moveNode/setFavorite/
  setFolderCustomization/getActivity/getSubtree/downloadFile). Pure refactor — same checks, same
  exceptions, ~50 fewer duplicated lines; `setFolderCustomization` now checks access before the
  "not a folder" business check instead of after, so a caller without access can no longer learn a
  node's type before being told they can't touch it.
- Removed the unused `spring-boot-starter-oauth2-authorization-server` and
  `spring-boot-starter-security-oauth2-client` Gradle dependencies — this service only ever acts as
  an OAuth2 resource server validating Keycloak JWTs, never issues tokens, and is never itself an
  OAuth2 client. Both classes were dead classpath/native-image weight; the actual resource-server
  classes in use (`JwtDecoder`, `NimbusJwtDecoder`, `.oauth2ResourceServer()`) were only reachable
  as a transitive dependency of the authorization-server starter, which no longer holds — replaced
  with the correct, minimal `spring-boot-starter-oauth2-resource-server` declared directly.
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
