package api.m2.file.configuration.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

// maxFileSize es la única fuente de verdad para el tope de subida — application.yaml también
// referencia esta misma property para spring.servlet.multipart.max-file-size/max-request-size,
// así el límite del servlet y el que valida FileService nunca vuelven a desalinearse.
// workspaceQuota es el tope de bytes totales que un workspace puede acumular (suma de archivos no
// borrados) — independiente de maxFileSize, que solo limita el tamaño de una subida individual.
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(String basePath, DataSize maxFileSize, DataSize workspaceQuota) {
}
