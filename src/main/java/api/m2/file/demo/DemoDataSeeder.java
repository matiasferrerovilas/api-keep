package api.m2.file.demo;

import api.m2.file.configuration.properties.StorageProperties;
import api.m2.file.entity.FileEntity;
import api.m2.file.enums.FileType;
import api.m2.file.repository.FileRepository;
import api.m2.file.service.storage.StorageAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * Seeds a handful of sample folders/files for local demos and screenshots. Only ever active under
 * the {@code demo} Spring profile — never wired into {@code dev}/{@code prod}/default.
 *
 * <p>{@code workspaceId = 1} is a suite-wide convention: api-identity's own {@code demo} profile
 * is responsible for creating that workspace/user; every other backend (this one, api-movements,
 * ...) only seeds its own domain rows against that same id, independently. This class does not
 * create the workspace itself.
 */
@Slf4j
@Component
@Profile("demo")
@RequiredArgsConstructor
public class DemoDataSeeder implements CommandLineRunner {

    private static final Long DEMO_WORKSPACE_ID = 1L;
    private static final Long DEMO_OWNER_ID = 1L;
    private static final String CHECKSUM_ALGORITHM = "SHA-256";

    private final FileRepository fileRepository;
    private final StorageProperties storageProperties;
    private final StorageAdapter storageAdapter;

    @Override
    public void run(String... args) {
        // Idempotency: a restart in the demo profile (or a redeploy) must not duplicate rows —
        // any existing row for the demo workspace means seeding already happened.
        if (fileRepository.existsByWorkspaceId(DEMO_WORKSPACE_ID)) {
            log.info("Ya existen datos de demo para el workspace {}, no se vuelve a sembrar", DEMO_WORKSPACE_ID);
            return;
        }

        log.info("Sembrando datos de demo para el workspace {}", DEMO_WORKSPACE_ID);

        LocalDateTime now = LocalDateTime.now();
        String rootLocation = "%s/%s".formatted(storageProperties.basePath(), DEMO_WORKSPACE_ID);
        FileEntity root = createFolder(null, rootLocation, "Home", now);

        FileEntity documents = createFolder(root.getId(), childLocation(root, "Documentos"), "Documentos", now);
        createFile(documents, "Bienvenida.md", """
                # Bienvenido a Keep

                Esto es un workspace de demostración, sembrado automáticamente al levantar la app
                con el perfil `demo`. Los archivos que ves acá son de ejemplo — subí, movés,
                renombrá o borrá lo que quieras, es un entorno descartable.
                """, now);
        createFile(documents, "Notas.txt", "Notas de ejemplo para la demo de Keep.\n", now);

        FileEntity recipes = createFolder(root.getId(), childLocation(root, "Recetas"), "Recetas", now);
        createFile(recipes, "Tarta de manzana.md", """
                # Tarta de manzana

                ## Ingredientes
                - 3 manzanas
                - 200g de harina
                - 100g de manteca
                - 100g de azúcar

                ## Preparación
                1. Armar la masa y estirarla en un molde.
                2. Cortar las manzanas en láminas finas y acomodarlas encima.
                3. Hornear 35 minutos a 180°C.
                """, now);

        log.info("Datos de demo sembrados: 1 carpeta raíz, 2 subcarpetas, 3 archivos");
    }

    private static String childLocation(FileEntity parent, String childName) {
        return "%s/%s".formatted(parent.getLocation(), childName);
    }

    private FileEntity createFolder(Long parentId, String location, String name, LocalDateTime now) {
        try {
            storageAdapter.createDirectories(location);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear la carpeta de demo: " + location, e);
        }

        FileEntity folder = FileEntity.builder()
                .parentId(parentId)
                .ownerId(DEMO_OWNER_ID)
                .workspaceId(DEMO_WORKSPACE_ID)
                .name(name)
                .type(FileType.FOLDER)
                .location(location)
                .createdAt(now)
                .updatedAt(now)
                .build();
        return fileRepository.save(folder);
    }

    private void createFile(FileEntity parent, String name, String content, LocalDateTime now) {
        String location = childLocation(parent, name);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        try {
            storageAdapter.storeNew(location, new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo escribir el archivo de demo: " + location, e);
        }

        String contentType;
        try {
            String probed = storageAdapter.probeContentType(location);
            contentType = probed != null ? probed : "text/plain";
        } catch (IOException e) {
            contentType = "text/plain";
        }

        FileEntity file = FileEntity.builder()
                .parentId(parent.getId())
                .ownerId(DEMO_OWNER_ID)
                .workspaceId(DEMO_WORKSPACE_ID)
                .name(name)
                .type(FileType.FILE)
                .size((long) bytes.length)
                .contentType(contentType)
                .checksum(sha256(bytes))
                .location(location)
                .createdAt(now)
                .updatedAt(now)
                .build();
        fileRepository.save(file);
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance(CHECKSUM_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algoritmo de checksum no disponible: " + CHECKSUM_ALGORITHM, e);
        }
    }
}
