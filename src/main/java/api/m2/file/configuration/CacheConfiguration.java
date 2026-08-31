package api.m2.file.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

import java.time.Duration;

@Configuration
public class CacheConfiguration {

    // Cachea las dos llamadas a api-identity que se disparan en casi cada request (resolver el
    // usuario autenticado, verificar membership de un workspace — esto último se llama desde
    // FileMembershipGuard/FileService en casi todos los endpoints) — antes cada operación hacía un
    // round-trip HTTP síncrono sin cache, sin timeout y sin circuit breaker: si api-identity se
    // colgaba un momento, ninguna operación de esta app podía completarse aunque el JWT del caller
    // siguiera siendo válido. 5hs es deliberadamente generoso: cambios de membership o de rol
    // pueden tardar hasta ese tiempo en reflejarse acá, a cambio de sacar a api-identity del
    // camino crítico de casi todo el tráfico. Quedaba declarado pero sin usar; ahora está cableado.
    public static final String USER_CACHE = "user";
    private static final String KEY_PREFIX = "api-file-sharing:";
    private static final Duration IDENTITY_TTL = Duration.ofHours(5);

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // USER_CACHE NO puede deshabilitar el cacheo de nulls: verifyUserIsMemberOfWorkspace es
        // @Cacheable pero retorna void, y el mecanismo de cacheo de métodos void de Spring termina
        // pasando por el mismo chequeo de null que RedisCache usa para valores reales — con
        // disableCachingNullValues() activo, la primera invocación exitosa explota con
        // "Cache 'user' does not allow 'null' values" en vez de cachear. Spring Data Redis soporta
        // cachear null nativamente (guarda un sentinel serializado), así que dejarlo habilitado
        // acá es seguro y no cambia el comportamiento de getMe(), que siempre devuelve un valor real.
        RedisCacheConfiguration identityConfig = baseCacheConfig();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(baseCacheConfig().disableCachingNullValues())
                .withCacheConfiguration(USER_CACHE, identityConfig.entryTtl(IDENTITY_TTL))
                .build();
    }

    private static RedisCacheConfiguration baseCacheConfig() {
        var typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType(Object.class)
                .build();

        var serializer = GenericJacksonJsonRedisSerializer.create(
                builder -> builder.enableDefaultTyping(typeValidator));

        return RedisCacheConfiguration.defaultCacheConfig()
                .computePrefixWith(cacheName -> KEY_PREFIX + cacheName + "::")
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));
    }
}
