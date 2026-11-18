package com.example.shop.order.config;

import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Отдельный String-темплейт для OutboxPublisher: payload в outbox — уже готовый
 * JSON-текст, повторная Json-сериализация не нужна. Автоконфигурация Spring Boot
 * создаёт KafkaTemplate<String,Object> (JsonSerializer), поэтому бин нужно объявить явно.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public ProducerFactory<String, String> stringProducerFactory(KafkaProperties props) {
        var cfg = props.buildProducerProperties(null);
        cfg.put("key.serializer", StringSerializer.class);
        cfg.put("value.serializer", StringSerializer.class);
        cfg.put("spring.json.add.type.headers", false);
        return new DefaultKafkaProducerFactory<>(cfg);
    }

    @Bean
    public KafkaTemplate<String, String> stringKafkaTemplate(
            ProducerFactory<String, String> stringProducerFactory) {
        return new KafkaTemplate<>(stringProducerFactory);
    }
}