package api.m2.file.record.events;

import api.m2.file.enums.EventType;
import api.m2.file.record.FileDTO;

/**
 * Published after a tree mutation commits (see FileService); consumed by
 * FileTreePublishServiceWebSocket to push a live update instead of requiring the client to
 * refetch the whole tree.
 */
public record FileTreeChangedEvent(Long workspaceId, EventType eventType, FileDTO file) {
}
