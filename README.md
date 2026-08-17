# api-keep

A file storage and sharing backend, built with Spring Boot. Each workspace gets its own file/folder tree on disk, with real-time updates over WebSocket and app-to-app sharing grants. User identity and workspace membership are delegated to [api-identity](https://github.com/matiasferrerovilas/api-identity) — this service only owns files and folders.

## Features

- **File tree per workspace**: nested folders on local disk, addressed by an auto-created root (`Home`)
- **Upload / download**: multipart upload (50MB app-level limit), single-file download, and on-the-fly zip download for folders
- **Move, rename, delete**: full tree management, with descendants relocated automatically when a folder moves or is renamed
- **Checksums**: SHA-256 computed on upload and returned with file metadata
- **App-to-app sharing**: grant another app (e.g. api-movements) `READ`/`WRITE`/`READ_WRITE` access to a specific file or folder, enforced on every read/write — a caller outside the file's own workspace only gets through if there's a matching grant
- **Real-time updates**: WebSocket (STOMP/SockJS) push on upload, rename, move, and delete, so other sessions don't need to refetch the whole tree
- **Cross-app events**: sharing a file publishes to RabbitMQ (`file-sharing.topic`) so other services can react
- **User authentication**: Keycloak OAuth2 / JWT (RS256) resource server; the calling app itself is identified from the JWT's `app` claim (set per-client in Keycloak), not a client-supplied header
- **API documentation**: Swagger/OpenAPI UI
- **Database migrations**: Liquibase (`ddl-auto: none`)

Not supported yet: image/video uploads (rejected by design — see Immich for photo storage in this suite), file versioning, trash/undo-delete, full-text search.

## Tech Stack

- **Java 25** with **Spring Boot 4.1**
- **MySQL 8.0** database
- **Liquibase** for database migrations
- **MapStruct** for object mapping
- **Spring Security** with OAuth2 / Keycloak JWT
- **Spring Web** for REST endpoints
- **Spring Data JPA** for data access
- **RabbitMQ** for cross-app events (`file-sharing.topic` exchange)
- **WebSocket (STOMP/SockJS)** for real-time tree updates
- **Redis** for caching
- **JUnit 5 + Mockito** for testing

## Prerequisites

- Java 25 JDK
- Docker and Docker Compose
- MySQL 8.0+ (or use the provided Docker Compose setup)
- Gradle 9+
- A running [api-identity](https://github.com/matiasferrerovilas/api-identity) instance — every file operation verifies workspace membership against it

## Getting Started

### Local Development

1. **Clone the repository**
   ```bash
   git clone https://github.com/matiasferrerovilas/api-file-share.git api-keep
   cd api-keep
   ```

2. **Run api-identity** alongside this service (defaults to `http://localhost:8082`, configured via `identity.base-url`).

3. **Set up the database**
   - Create a MySQL database named `files`
   - Or use the provided Docker Compose setup:
     ```bash
     docker compose -f docker-compose/docker-compose.yml up -d
     ```

4. **Configure application properties**
   Create `src/main/resources/application-dev.yaml` (or export env vars) with your database, RabbitMQ, and storage settings. Key properties:
   ```yaml
   app:
     storage:
       base-path: /path/to/local/storage   # STORAGE_BASE_PATH
   spring:
     datasource:
       url: jdbc:mysql://localhost:3306/files
       username: your_username
       password: your_password
   ```

5. **Run the application**
   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=dev'
   ```

### Docker Build

```bash
docker build -t api-keep .

docker run -p 8081:8081 \
  -e DB_URL=jdbc:mysql://db:3306/files \
  -e DB_USERNAME=your_username \
  -e DB_PASSWORD=your_password \
  -e STORAGE_BASE_PATH=/mnt/storage \
  -v /mnt/storage:/mnt/storage \
  api-keep
```

## API Overview

All endpoints are under `/v1` and require a valid Keycloak-issued JWT.

| Method | Path | Description |
|---|---|---|
| GET | `/v1/folders/tree` | Full file/folder tree for the current workspace |
| GET | `/v1/folders/{id}/download` | Download a file, or a zipped folder |
| POST | `/v1/folders/upload` | Upload a file (multipart) |
| POST | `/v1/folders` | Create a folder |
| PATCH | `/v1/folders/{id}` | Rename a file/folder |
| PATCH | `/v1/folders/{id}/move` | Move a file/folder to a new parent |
| DELETE | `/v1/folders/{id}` | Delete a file/folder |
| POST | `/v1/shares` | Share a file/folder with another app |
| GET | `/v1/shares?fileId=` | List an app's shares for a file |
| GET/PUT | `/v1/settings/defaults/{key}` | Per-user default settings |
| GET | `/v1/users/me` | Current authenticated user |
| POST/GET | `/v1/workspace` | Workspace creation/listing (proxied to api-identity) |
| POST/PUT | `/v1/onboarding` | Onboarding flow |

Full interactive documentation is available at `/swagger-ui.html` once the app is running.

## Authentication

Keycloak OAuth2 with JWT (RS256). Include the token in the `Authorization` header as `Bearer <token>`.

## Real-time events

Connect to `/ws` (SockJS) and subscribe to, per workspace:

- `/topic/files/{workspaceId}/new`
- `/topic/files/{workspaceId}/update`
- `/topic/files/{workspaceId}/delete`

Each message is `{ eventType, message }`, where `message` is the affected file's metadata.

## Testing

```bash
./gradlew test
```

Run tests and checkstyle together:

```bash
./gradlew test checkstyleMain
```

## License

See the [LICENSE](LICENSE) file for details.
