package org.involens_be.config;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.helper.LocalDateTimeTypeAdapter;
import org.involens_be.helper.LocalDateTypeAdapter;
import org.involens_be.helper.OffsetDateTimeTypeAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Configuration
@Slf4j
public class AppConfig {

    /**
     * Creates the client used by application services to access the configured MinIO object store.
     *
     * @param endpoint MinIO API endpoint
     * @param accessKey MinIO access key
     * @param secretKey MinIO secret key
     * @return configured MinIO client
     */
    @Bean
    @org.springframework.context.annotation.Primary
    public MinioClient minioClient(
            @Value("${app.minio.endpoint}") String endpoint,
            @Value("${app.minio.access-key}") String accessKey,
            @Value("${app.minio.secret-key}") String secretKey) {
        log.info("Configuring MinIO client. endpoint={}", endpoint);
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        log.info("MinIO client configured successfully. endpoint={}", endpoint);
        return client;
    }

    /** Creates a signing client using the browser-accessible endpoint without contacting that endpoint. */
    @Bean
    public MinioClient publicMinioClient(
            @Value("${app.minio.public-endpoint}") String endpoint,
            @Value("${app.minio.access-key}") String accessKey,
            @Value("${app.minio.secret-key}") String secretKey,
            @Value("${app.minio.region}") String region) {
        log.info("Configuring browser-accessible MinIO signing client. endpoint={}, region={}", endpoint, region);
        MinioClient client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey)
                .region(region).build();
        log.info("Browser-accessible MinIO signing client configured");
        return client;
    }

    /**
     * Creates the JSON serializer used for API payloads and supported Java date types.
     *
     * @return configured Gson serializer
     */
    @Bean
    public Gson gson() {
        log.info("Configuring Gson serializer");
        ExclusionStrategy strategy = new ExclusionStrategy() {
            @Override
            public boolean shouldSkipField(FieldAttributes field) {
                return false;
            }

            @Override
            public boolean shouldSkipClass(Class<?> clazz) {
                return false;
            }
        };
        Gson gson = new GsonBuilder().disableHtmlEscaping()
                .addSerializationExclusionStrategy(strategy).excludeFieldsWithoutExposeAnnotation()
                .setDateFormat("DD/MM/YYYY HH:mm:ss zzz")
                .registerTypeAdapter(LocalDate.class, new LocalDateTypeAdapter())
                .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeTypeAdapter())
                .registerTypeAdapter(OffsetDateTime.class, new OffsetDateTimeTypeAdapter()).create();
        log.info("Gson serializer configured successfully");
        return gson;
    }
}
