package com.watchcluster.config

import com.watchcluster.model.WebhookConfig
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AppConfig {

    @Bean
    fun kubernetesClient(): KubernetesClient {
        return KubernetesClientBuilder().build()
    }

    @Bean
    fun webhookConfig(): WebhookConfig {
        return WebhookConfig.fromEnvironment()
    }
}