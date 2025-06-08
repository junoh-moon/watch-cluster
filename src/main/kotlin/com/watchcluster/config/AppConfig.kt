package com.watchcluster.config

import com.watchcluster.model.WebhookConfig
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.newSingleThreadContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Bean("watchClusterContext")
    fun watchClusterContext(): CoroutineContext {
        return newSingleThreadContext("WatchClusterThread")
    }

    @Bean("watchClusterScheduler")
    fun watchClusterTaskScheduler(): TaskScheduler {
        return ThreadPoolTaskScheduler().apply {
            poolSize = 1
            setThreadNamePrefix("WatchCluster-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(30)
            initialize()
        }
    }
}