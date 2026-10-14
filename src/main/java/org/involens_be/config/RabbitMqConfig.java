package org.involens_be.config;

import lombok.extern.slf4j.Slf4j;
import org.involens_be.service.ExtractionMessageService;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration // Đánh dấu đây là class cấu hình của Spring
public class RabbitMqConfig {

    /** Declares the durable exchange, queue and routing binding used to deliver documents to AI. */
    @Bean
    @ConditionalOnProperty(name = "app.rabbitmq.enabled", havingValue = "true")
    public Declarables extractionTopology(
            @Value("${app.rabbitmq.extract.exchange:}") String exchangeName,
            @Value("${app.rabbitmq.extract.queue:}") String queueName,
            @Value("${app.rabbitmq.extract.routing-key:}") String routingKey) {
        log.info("Configuring AI extraction RabbitMQ topology");
        if (!StringUtils.hasText(exchangeName) || !StringUtils.hasText(queueName)
                || !StringUtils.hasText(routingKey)) {
            log.error("AI extraction exchange, queue and routing key must be configured");
            throw new IllegalStateException("RabbitMQ extraction exchange, queue and routing key must be configured");
        }
        DirectExchange exchange = new DirectExchange(exchangeName, true, false);
        Queue queue = new Queue(queueName, true);
        Declarables topology = new Declarables(exchange, queue,
                BindingBuilder.bind(queue).to(exchange).with(routingKey));
        log.info("AI extraction RabbitMQ topology configured. exchange={}, queue={}, routingKey={}",
                exchangeName, queueName, routingKey);
        return topology;
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.rabbitmq.enabled",
            havingValue = "true"
    )
    public SimpleMessageListenerContainer resultListener(

            RabbitTemplate rabbitTemplate,

            ExtractionMessageService extractionMessageService,
            // Queue nhận kết quả xử lý từ service khác
            @Value("${app.rabbitmq.result.queue:}")
            String resultQueue,
            // Queue chứa các message xử lý thất bại sau khi retry
            @Value("${app.rabbitmq.result.dead-letter-queue:}")
            String deadLetterQueue) {

        log.info("Configuring extraction result listener");

        // Bắt buộc phải cấu hình đủ tên result queue và DLQ
        if (!StringUtils.hasText(resultQueue)
                || !StringUtils.hasText(deadLetterQueue)) {
            throw new IllegalStateException(
                    "RabbitMQ result and dead letter queue names must be configured"
            );
        }

        /*
         * RabbitAdmin dùng để tạo / quản lý Queue, Exchange, Binding...
         * Sử dụng cùng ConnectionFactory của RabbitTemplate.
         */
        RabbitAdmin admin =
                new RabbitAdmin(rabbitTemplate.getConnectionFactory());

        /*
         * Khai báo result queue.
         *
         * true = durable
         * -> Queue vẫn tồn tại nếu RabbitMQ restart.
         */
        admin.declareQueue(
                new Queue(resultQueue, true)
        );

        admin.declareQueue(
                new Queue(deadLetterQueue, true)
        );

        /*
         * Listener Container:
         * thành phần liên tục lắng nghe message từ RabbitMQ.
         */
        SimpleMessageListenerContainer container =
                new SimpleMessageListenerContainer(
                        rabbitTemplate.getConnectionFactory()
                );

        // Chỉ định queue mà listener sẽ consume
        container.setQueueNames(resultQueue);

        /*
         * Callback được gọi mỗi khi resultQueue có message mới.
         */
        container.setMessageListener(message -> {

            // RabbitMQ body là byte[] -> convert thành String UTF-8
            String body = new String(
                    message.getBody(),
                    StandardCharsets.UTF_8
            );

            /*
             * Tổng cộng tối đa 4 lần xử lý:
             */
            for (int attempt = 1; attempt <= 4; attempt++) {
                try {
                    extractionMessageService.receiveResult(body);
                    return;
                } catch (Exception exception) {
                    log.error(
                            "Extraction result handling failed. attempt={}",
                            attempt,
                            exception
                    );
                    if (attempt == 4) {
                        rabbitTemplate.convertAndSend(
                                "",
                                deadLetterQueue,
                                message
                        );
                        log.error(
                                "Extraction result moved to dead letter queue after three retries"
                        );
                    }
                }
            }
        });
        log.info(
                "Extraction result listener configured. queue={}, deadLetterQueue={}",
                resultQueue,
                deadLetterQueue
        );
        // Đưa Listener Container vào Spring Context
        return container;
    }
}
