package api.m2.file.constants;

/**
 * Centralizes STOMP topic paths, mirroring the convention already used in api-movements.
 */
public final class WebSocketTopics {

    public static final String FILES = "/topic/files";

    private static final String NEW = "/new";
    private static final String UPDATE = "/update";
    private static final String DELETE = "/delete";

    private WebSocketTopics() {
    }

    public static String filesNew(Long workspaceId) {
        return FILES + "/" + workspaceId + NEW;
    }

    public static String filesUpdate(Long workspaceId) {
        return FILES + "/" + workspaceId + UPDATE;
    }

    public static String filesDelete(Long workspaceId) {
        return FILES + "/" + workspaceId + DELETE;
    }
}
