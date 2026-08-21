package api.m2.file.constants;

/**
 * Centralizes STOMP topic paths, mirroring the convention already used in api-movements.
 */
public final class WebSocketTopics {

    public static final String FILES = "/topic/files";
    public static final String WORKSPACES = "/topic/workspace";
    public static final String INVITATIONS = "/topic/invitations";
    public static final String MEMBERSHIP = "/topic/membership";

    private static final String NEW = "/new";
    private static final String UPDATE = "/update";
    private static final String DELETE = "/delete";
    private static final String REMOVE = "/remove";

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

    /**
     * Topic de "alguien se sumó a este workspace", direccionado por workspaceId — para que
     * todos los que lo tienen abierto refresquen la lista de miembros.
     */
    public static String workspaceMembersUpdate(Long workspaceId) {
        return WORKSPACES + "/" + workspaceId + "/members" + UPDATE;
    }

    /**
     * Topic de invitaciones recibidas por un usuario, direccionado por email (el "name" del
     * principal autenticado, igual que en el resto del backend) ya que el evento que llega
     * desde api-identity no incluye el subject de Keycloak del invitado.
     */
    public static String invitationsNew(String invitedUserEmail) {
        return INVITATIONS + "/" + invitedUserEmail + NEW;
    }

    /**
     * Topic de "me sacaron de un workspace", direccionado por email igual que {@link #invitationsNew}.
     */
    public static String membershipRemoved(String removedUserEmail) {
        return MEMBERSHIP + "/" + removedUserEmail + REMOVE;
    }
}
