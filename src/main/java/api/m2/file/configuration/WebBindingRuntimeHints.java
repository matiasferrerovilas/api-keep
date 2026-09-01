package api.m2.file.configuration;

import api.m2.file.clients.identity.response.WorkspaceInvitationDTO;
import api.m2.file.events.InvitationAcceptedReceivedEvent;
import api.m2.file.events.InvitationReceivedEvent;
import api.m2.file.events.MemberRemovedReceivedEvent;
import api.m2.file.record.FileDTO;
import api.m2.file.record.events.EventWrapper;
import api.m2.file.record.events.UserFileShareEvent;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Bajo native-image hace falta reflection registrada explícitamente para records publicados solo
 * vía WebSocket ({@code SimpMessagingTemplate.convertAndSend} con {@code Object} genérico, ver
 * {@code EventWrapper}) o consumidos vía {@code @RabbitListener} — ninguno de los dos caminos pasa
 * por el escaneo AOT de MVC, así que quedan sin registrar hasta que efectivamente se disparan en
 * runtime (mismo bug encontrado y corregido primero en api-movements: un tipo así tira
 * {@code UnsupportedFeatureError} en el native image de producción, nunca en tests porque ahí no
 * corre como native image).
 */
public class WebBindingRuntimeHints implements RuntimeHintsRegistrar {

    private static final Class<?>[] RECORD_TYPES = {
            EventWrapper.class,
            FileDTO.class,
            FileDTO.Metadata.class,
            WorkspaceInvitationDTO.class,
            InvitationReceivedEvent.class,
            InvitationAcceptedReceivedEvent.class,
            MemberRemovedReceivedEvent.class,
            UserFileShareEvent.class,
    };

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        for (Class<?> type : RECORD_TYPES) {
            hints.reflection().registerType(type, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS);
        }
    }
}
