package com.ticketflow.infrastructure.config;

import com.ticketflow.infrastructure.persistence.DynamoDbTableProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder;

/** Wires the DynamoDB async clients and (optionally) the startup table provisioning. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DynamoDbProperties.class)
public class DynamoDbConfig {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbConfig.class);

    @Bean(destroyMethod = "close")
    DynamoDbAsyncClient dynamoDbAsyncClient(DynamoDbProperties properties) {
        return buildClient(properties, DynamoDbAsyncClient.builder());
    }

    @Bean
    DynamoDbEnhancedAsyncClient dynamoDbEnhancedAsyncClient(DynamoDbAsyncClient client) {
        return DynamoDbEnhancedAsyncClient.builder().dynamoDbClient(client).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "ticketflow.dynamodb", name = "provisioning-enabled", havingValue = "true")
    DynamoDbTableProvisioner dynamoDbTableProvisioner(DynamoDbAsyncClient client, DynamoDbProperties properties) {
        return new DynamoDbTableProvisioner(client, properties.provisioningMaxAttempts(),
                properties.provisioningPollInterval());
    }

    @Bean
    @ConditionalOnProperty(prefix = "ticketflow.dynamodb", name = "provisioning-enabled", havingValue = "true")
    TableProvisioningStarter tableProvisioningStarter(DynamoDbTableProvisioner provisioner) {
        return new TableProvisioningStarter(provisioner);
    }

    static DynamoDbAsyncClient buildClient(DynamoDbProperties properties, DynamoDbAsyncClientBuilder builder) {
        builder.region(Region.of(properties.region())).credentialsProvider(credentialsProvider(properties));
        if (properties.endpoint() != null) {
            builder.endpointOverride(properties.endpoint());
        }
        return builder.build();
    }

    static AwsCredentialsProvider credentialsProvider(DynamoDbProperties properties) {
        if (properties.hasStaticCredentials()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }

    /**
     * Runs the provisioning once the application is ready, without blocking: failures are logged
     * (the reactive path must never block, see docs/conventions.md).
     */
    static class TableProvisioningStarter {

        private final DynamoDbTableProvisioner provisioner;

        TableProvisioningStarter(DynamoDbTableProvisioner provisioner) {
            this.provisioner = provisioner;
        }

        @EventListener(ApplicationReadyEvent.class)
        void onApplicationReady() {
            provisioner.provision().subscribe(
                    unused -> { },
                    error -> log.error("DynamoDB table provisioning failed", error),
                    () -> log.info("DynamoDB table provisioning completed"));
        }
    }
}
