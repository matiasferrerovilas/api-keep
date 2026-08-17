package api.m2.file.events;

import api.m2.file.enums.SharePermission;

/**
 * Published to RabbitMQ (see {@code RabbitConfig}) when a file/folder is shared with another
 * app, so that app can eventually react instead of the grant sitting unused until it happens to
 * poll GET /v1/shares.
 */
public record FileSharedEvent(
        Long fileId,
        String fileName,
        String apiName,
        SharePermission permission,
        Long sharedByUserId) {
}
