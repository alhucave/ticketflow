package com.ticketflow.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketflow.infrastructure.persistence.DynamoDbTableProvisioner;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

class DynamoDbConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DynamoDbConfig.class);

    private static DynamoDbProperties props(URI endpoint, String key, String secret) {
        return new DynamoDbProperties(endpoint, "eu-west-1", key, secret, false, 3, Duration.ofMillis(1));
    }

    @Test
    void credentialsProvider_staticKeys_usesStaticProvider() {
        var provider = DynamoDbConfig.credentialsProvider(props(null, "k", "s"));
        assertThat(provider).isInstanceOf(StaticCredentialsProvider.class);
        assertThat(provider.resolveCredentials().accessKeyId()).isEqualTo("k");
    }

    @Test
    void credentialsProvider_noKeys_usesDefaultChain() {
        assertThat(DynamoDbConfig.credentialsProvider(props(null, null, null)))
                .isInstanceOf(DefaultCredentialsProvider.class);
        assertThat(DynamoDbConfig.credentialsProvider(props(null, "k", " ")))
                .isInstanceOf(DefaultCredentialsProvider.class);
    }

    @Test
    void context_withEndpointAndRegion_buildsClientsFromProperties() {
        runner.withPropertyValues(
                        "ticketflow.dynamodb.endpoint=http://localhost:8123",
                        "ticketflow.dynamodb.region=eu-west-1",
                        "ticketflow.dynamodb.access-key-id=test",
                        "ticketflow.dynamodb.secret-access-key=test")
                .run(context -> {
                    assertThat(context).hasSingleBean(DynamoDbAsyncClient.class);
                    assertThat(context).hasSingleBean(DynamoDbEnhancedAsyncClient.class);
                    var client = context.getBean(DynamoDbAsyncClient.class);
                    assertThat(client.serviceClientConfiguration().region().id()).isEqualTo("eu-west-1");
                    assertThat(client.serviceClientConfiguration().endpointOverride())
                            .contains(URI.create("http://localhost:8123"));
                });
    }

    @Test
    void context_withoutEndpoint_usesDefaultRegionAndNoOverride() {
        runner.withPropertyValues("ticketflow.dynamodb.access-key-id=a", "ticketflow.dynamodb.secret-access-key=b")
                .run(context -> {
                    var cfg = context.getBean(DynamoDbAsyncClient.class).serviceClientConfiguration();
                    assertThat(cfg.region().id()).isEqualTo("us-east-1");
                    assertThat(cfg.endpointOverride()).isEmpty();
                });
    }

    @Test
    void context_provisioningDisabledByDefault_noProvisionerBeans() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(DynamoDbTableProvisioner.class);
            assertThat(context).doesNotHaveBean(DynamoDbConfig.TableProvisioningStarter.class);
        });
    }

    @Test
    void context_provisioningEnabled_createsProvisionerAndStarter() {
        runner.withPropertyValues("ticketflow.dynamodb.provisioning-enabled=true",
                        "ticketflow.dynamodb.access-key-id=a", "ticketflow.dynamodb.secret-access-key=b")
                .run(context -> {
                    assertThat(context).hasSingleBean(DynamoDbTableProvisioner.class);
                    assertThat(context).hasSingleBean(DynamoDbConfig.TableProvisioningStarter.class);
                });
    }

    @Test
    void starter_onApplicationReady_subscribesToProvisioning() {
        var provisioner = mock(DynamoDbTableProvisioner.class);
        var subscribed = new boolean[1];
        when(provisioner.provision()).thenReturn(Mono.<Void>empty().doOnSubscribe(s -> subscribed[0] = true));
        new DynamoDbConfig.TableProvisioningStarter(provisioner).onApplicationReady();
        verify(provisioner).provision();
        assertThat(subscribed[0]).isTrue();
    }

    @Test
    void starter_onApplicationReady_provisioningFailureIsSwallowedAndLogged() {
        var provisioner = mock(DynamoDbTableProvisioner.class);
        when(provisioner.provision()).thenReturn(Mono.error(new IllegalStateException("boom")));
        new DynamoDbConfig.TableProvisioningStarter(provisioner).onApplicationReady();
        verify(provisioner).provision();
    }
}
