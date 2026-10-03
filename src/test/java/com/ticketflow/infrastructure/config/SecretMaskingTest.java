package com.ticketflow.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Configuration objects holding secrets must never print them. */
class SecretMaskingTest {

    private static final String ACCESS = "AKIAEXAMPLEACCESSKEY1";
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

    @Test
    void dynamoDbProperties_toString_masksCredentialsAndEndpointUserInfo() {
        var properties = new DynamoDbProperties(URI.create("http://user:pw-in-url@dynamodb:8000/path?token=t0k3n"),
                "us-east-1", ACCESS, SECRET, true, 30, Duration.ofMillis(500));

        assertThat(properties.toString())
                .doesNotContain(ACCESS).doesNotContain(SECRET).doesNotContain("pw-in-url").doesNotContain("t0k3n")
                .contains("endpoint=http://dynamodb:8000").contains("region=us-east-1")
                .contains("accessKeyId=****").contains("secretAccessKey=****")
                .contains("provisioningEnabled=true");
    }

    @Test
    void sqsProperties_toString_masksCredentialsAndQueueUrl() {
        var properties = new SqsProperties(URI.create("http://localstack:4566"), "us-east-1", ACCESS, SECRET,
                "orders", "http://localstack:4566/000000000000/orders?sig=s1gn4tur3");

        assertThat(properties.toString())
                .doesNotContain(ACCESS).doesNotContain(SECRET).doesNotContain("s1gn4tur3")
                .contains("endpoint=http://localstack:4566").contains("secretAccessKey=****")
                .contains("ordersQueueName=orders").contains("ordersQueueUrl=****");
    }

    @Test
    void toString_unsetValues_showNullSoMissingConfigurationIsVisible() {
        assertThat(new DynamoDbProperties(null, "us-east-1", null, null, false, 30, Duration.ofMillis(500)).toString())
                .contains("endpoint=null").contains("accessKeyId=null").contains("secretAccessKey=null");
        assertThat(new SqsProperties(null, "us-east-1", null, null, "orders", null).toString())
                .contains("endpoint=null").contains("secretAccessKey=null").contains("ordersQueueUrl=null");
    }

    @Test
    void endpoint_withoutHostOrScheme_doesNotFail() {
        assertThat(SecretMasking.endpoint(URI.create("opaque:thing"))).isEqualTo("opaque://");
        assertThat(SecretMasking.endpoint(URI.create("/relative"))).isEmpty();
    }
}
