package dev.arthurgaspar.expeditions.platform.outbox;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.arthurgaspar.expeditions.platform.events.EventCodec;
import dev.arthurgaspar.expeditions.platform.events.EventsAutoConfiguration;
import dev.arthurgaspar.expeditions.platform.tracing.TraceContext;
import dev.arthurgaspar.expeditions.platform.tracing.TracingAutoConfiguration;

/**
 * Services with a database get the outbox. Their Flyway locations include {@code classpath:db/outbox} for the table.
 */
@AutoConfiguration(after = {EventsAutoConfiguration.class, TracingAutoConfiguration.class}, afterName = {
        "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration",
        "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration"})
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnBean(JdbcTemplate.class)
public class OutboxAutoConfiguration {

    @Bean
    Outbox outbox(JdbcTemplate jdbc, EventCodec codec, ObjectProvider<TraceContext> traces) {
        return new Outbox(jdbc, codec, traces.getIfAvailable());
    }

    @Bean
    Inbox inbox(JdbcTemplate jdbc, Clock clock) {
        return new Inbox(jdbc, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "expeditions.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
    OutboxRelay outboxRelay(JdbcTemplate jdbc, PlatformTransactionManager transactions, KafkaTemplate<String, String> kafka,
                            Clock clock, ObjectProvider<TraceContext> traces,
                            @Value("${expeditions.outbox.batch-size:200}") int batchSize,
                            @Value("${expeditions.outbox.retention:P1D}") Duration retention) {
        return new OutboxRelay(jdbc, new TransactionTemplate(transactions), kafka, clock, batchSize, retention,
                traces.getIfAvailable());
    }
}
