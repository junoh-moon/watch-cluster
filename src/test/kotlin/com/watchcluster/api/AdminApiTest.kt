package com.watchcluster.api

import com.watchcluster.client.K8sClient
import com.watchcluster.client.K8sWatcher
import com.watchcluster.client.domain.ContainerInfo
import com.watchcluster.client.domain.DeploymentEventInfo
import com.watchcluster.client.domain.DeploymentInfo
import com.watchcluster.client.domain.DeploymentStatus
import com.watchcluster.client.domain.K8sClientConfig
import com.watchcluster.client.domain.PodInfo
import com.watchcluster.client.domain.SecretInfo
import com.watchcluster.controller.WatchController
import com.watchcluster.model.WatchClusterAnnotations
import com.watchcluster.model.WebhookConfig
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminApiTest {
    /**
     * In-memory cluster: records every patch so tests can assert on the
     * annotations the API writes, which are the actual contract with the
     * controller.
     */
    private class FakeK8sClient(
        var deployments: MutableList<DeploymentInfo> = mutableListOf(),
    ) : K8sClient {
        val patches = mutableListOf<Triple<String, String, String>>()
        var patchSucceeds = true
        var eventsFailure: Exception? = null

        override suspend fun getDeployment(
            namespace: String,
            name: String,
        ): DeploymentInfo? = deployments.find { it.namespace == namespace && it.name == name }

        override suspend fun patchDeployment(
            namespace: String,
            name: String,
            patchJson: String,
        ): DeploymentInfo? {
            patches += Triple(namespace, name, patchJson)
            if (!patchSucceeds) return null
            return getDeployment(namespace, name)
        }

        override suspend fun listDeployments(): List<DeploymentInfo> = deployments

        override suspend fun listDeploymentEvents(
            namespace: String,
            deploymentName: String,
            limit: Int,
        ): List<DeploymentEventInfo> = eventsFailure?.let { throw it } ?: emptyList()

        override suspend fun watchDeployments(watcher: K8sWatcher<DeploymentInfo>) = Unit

        override suspend fun getPod(
            namespace: String,
            name: String,
        ): PodInfo? = null

        override suspend fun listPodsByLabels(
            namespace: String,
            labels: Map<String, String>,
        ): List<PodInfo> = emptyList()

        override suspend fun getSecret(
            namespace: String,
            name: String,
        ): SecretInfo? = null

        override suspend fun getConfiguration(): K8sClientConfig = K8sClientConfig("https://kubernetes.default.svc")
    }

    private lateinit var k8sClient: FakeK8sClient

    @BeforeEach
    fun setup() {
        mockkObject(WebhookConfig.Companion)
        every { WebhookConfig.fromEnvironment() } returns WebhookConfig(url = null)
        k8sClient = FakeK8sClient()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun deployment(
        namespace: String = "default",
        name: String = "app",
        image: String = "nginx:1.24.0",
        annotations: Map<String, String> = mapOf(WatchClusterAnnotations.ENABLED to "true"),
        replicas: Int = 1,
    ) = DeploymentInfo(
        namespace = namespace,
        name = name,
        generation = 1,
        replicas = replicas,
        selector = mapOf("app" to name),
        containers = listOf(ContainerInfo(name = "app", image = image)),
        imagePullSecrets = emptyList(),
        annotations = annotations,
        status =
            DeploymentStatus(
                observedGeneration = 1,
                readyReplicas = replicas,
                updatedReplicas = replicas,
                availableReplicas = replicas,
            ),
    )

    private fun ApplicationTestBuilder.installModule(authToken: String? = null) {
        val controller = WatchController(k8sClient, CoroutineScope(Dispatchers.Unconfined + SupervisorJob()))
        application { adminModule(AdminService(k8sClient, controller), authToken) }
    }

    @Test
    fun `lists only watch-enabled deployments`() =
        testApplication {
            k8sClient.deployments =
                mutableListOf(
                    deployment(name = "watched"),
                    deployment(name = "ignored", annotations = emptyMap()),
                    deployment(name = "disabled", annotations = mapOf(WatchClusterAnnotations.ENABLED to "false")),
                )
            installModule()

            val body = client.get("/api/apps").bodyAsText()

            assertContains(body, "watched")
            assertTrue("ignored" !in body)
            assertTrue("disabled" !in body)
        }

    @Test
    fun `exposes configured minimum release age without changing annotations`() =
        testApplication {
            k8sClient.deployments =
                mutableListOf(
                    deployment(
                        annotations =
                            mapOf(
                                WatchClusterAnnotations.ENABLED to "true",
                                "watch-cluster.io/minimum-release-age" to "3d",
                            ),
                    ),
                )
            installModule()
            val body = client.get("/api/apps").bodyAsText()
            val apps = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(body)
            assertEquals("3d", apps[0].path("minimumReleaseAge").asText())
            assertTrue(k8sClient.patches.isEmpty())
        }

    @Test
    fun `candidate list excludes already watched deployments`() =
        testApplication {
            k8sClient.deployments =
                mutableListOf(
                    deployment(name = "watched"),
                    deployment(name = "candidate", annotations = emptyMap()),
                )
            installModule()

            val body = client.get("/api/deployments").bodyAsText()

            assertContains(body, "candidate")
            assertTrue("watched" !in body)
        }

    @Test
    fun `applying a watch patches enabled cron and strategy`() =
        testApplication {
            k8sClient.deployments = mutableListOf(deployment(name = "candidate", annotations = emptyMap()))
            installModule()

            val response =
                client.put("/api/apps/default/candidate/watch") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"cron":"0 2 * * *","strategy":"latest"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val patch = k8sClient.patches.single().third
            assertContains(patch, """"${WatchClusterAnnotations.ENABLED}":"true"""")
            assertContains(patch, """"${WatchClusterAnnotations.CRON}":"0 2 * * *"""")
            assertContains(patch, """"${WatchClusterAnnotations.STRATEGY}":"latest"""")
        }

    @Test
    fun `applying a watch leaves omitted fields out of the patch`() =
        testApplication {
            k8sClient.deployments =
                mutableListOf(
                    deployment(
                        annotations =
                            mapOf(
                                WatchClusterAnnotations.ENABLED to "true",
                                WatchClusterAnnotations.CRON to "0 3 * * *",
                                WatchClusterAnnotations.STRATEGY to "latest",
                            ),
                    ),
                )
            installModule()

            client.put("/api/apps/default/app/watch") {
                contentType(ContentType.Application.Json)
                setBody("""{"strategy":"version"}""")
            }

            // Restating cron here would clobber a concurrent `kubectl annotate`.
            val patch = k8sClient.patches.single().third
            assertContains(patch, """"${WatchClusterAnnotations.STRATEGY}":"version"""")
            assertTrue(WatchClusterAnnotations.CRON !in patch)
        }

    @Test
    fun `rejects a blank cron instead of silently keeping the old one`() =
        testApplication {
            k8sClient.deployments = mutableListOf(deployment())
            installModule()

            val response =
                client.put("/api/apps/default/app/watch") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"cron":"   "}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(k8sClient.patches.isEmpty())
        }

    @Test
    fun `rejects a cron that parses but never fires`() =
        testApplication {
            k8sClient.deployments = mutableListOf(deployment())
            installModule()

            // February 30 never occurs: this parses, but a worker given it
            // would disable its ticker.
            val response =
                client.put("/api/apps/default/app/watch") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"cron":"0 0 0 30 2 ?"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(k8sClient.patches.isEmpty())
        }

    @Test
    fun `rollout is in progress until the new generation is observed`() =
        testApplication {
            k8sClient.deployments =
                mutableListOf(
                    deployment().let {
                        // Replica counts still describe generation 1.
                        it.copy(generation = 2, status = it.status.copy(observedGeneration = 1))
                    },
                )
            installModule()

            assertContains(client.get("/api/apps").bodyAsText(), """"inProgress":true""")
        }

    @Test
    fun `rejects an invalid cron expression without patching`() =
        testApplication {
            k8sClient.deployments = mutableListOf(deployment())
            installModule()

            val response =
                client.put("/api/apps/default/app/watch") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"cron":"not a cron"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(k8sClient.patches.isEmpty())
        }

    @Test
    fun `accepts a strategy synonym and stores its canonical name`() =
        testApplication {
            k8sClient.deployments = mutableListOf(deployment())
            installModule()

            val response =
                client.put("/api/apps/default/app/watch") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"strategy":"semver"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            // The controller treats "semver" as version; the stored annotation
            // says what the UI will read back.
            assertContains(k8sClient.patches.single().third, """"${WatchClusterAnnotations.STRATEGY}":"version"""")
        }

    @Test
    fun `reports unreadable events separately from having none`() =
        testApplication {
            k8sClient.deployments = mutableListOf(deployment())
            k8sClient.eventsFailure = RuntimeException("events is forbidden")
            installModule()

            val body = client.get("/api/apps/default/app").bodyAsText()

            assertContains(body, """"eventsUnavailable":true""")
        }

    @Test
    fun `rejects an unknown strategy instead of silently defaulting`() =
        testApplication {
            k8sClient.deployments = mutableListOf(deployment())
            installModule()

            val response =
                client.put("/api/apps/default/app/watch") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"strategy":"rolling"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(k8sClient.patches.isEmpty())
        }

    @Test
    fun `manual check sets the check-now annotation`() =
        testApplication {
            k8sClient.deployments = mutableListOf(deployment())
            installModule()

            val response = client.post("/api/apps/default/app/check")

            assertEquals(HttpStatusCode.Accepted, response.status)
            assertContains(k8sClient.patches.single().third, """"${WatchClusterAnnotations.CHECK_NOW}":"true"""")
        }

    @Test
    fun `manual check on an unwatched deployment is refused`() =
        testApplication {
            k8sClient.deployments = mutableListOf(deployment(annotations = emptyMap()))
            installModule()

            val response = client.post("/api/apps/default/app/check")

            // No worker would ever consume the annotation, so it must not be set.
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(k8sClient.patches.isEmpty())
        }

    @Test
    fun `missing deployment returns 404`() =
        testApplication {
            installModule()

            assertEquals(HttpStatusCode.NotFound, client.post("/api/apps/default/ghost/check").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/apps/default/ghost").status)
        }

    @Test
    fun `unwatch disables without clearing schedule settings`() =
        testApplication {
            k8sClient.deployments = mutableListOf(deployment())
            installModule()

            val response = client.delete("/api/apps/default/app/watch")

            assertEquals(HttpStatusCode.OK, response.status)
            val patch = k8sClient.patches.single().third
            assertContains(patch, """"${WatchClusterAnnotations.ENABLED}":"false"""")
            assertTrue(WatchClusterAnnotations.CRON !in patch)
        }

    @Test
    fun `a failed patch surfaces as an error instead of a success`() =
        testApplication {
            k8sClient.deployments = mutableListOf(deployment())
            k8sClient.patchSucceeds = false
            installModule()

            assertEquals(HttpStatusCode.BadGateway, client.post("/api/apps/default/app/check").status)
        }

    @Test
    fun `bearer token gate rejects unauthenticated api calls but allows health checks`() =
        testApplication {
            k8sClient.deployments = mutableListOf(deployment())
            installModule(authToken = "s3cret")

            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/apps").status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/apps") { header("Authorization", "Bearer wrong") }.status,
            )
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/apps") { header("Authorization", "Bearer s3cret") }.status,
            )
            assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
        }

    @Test
    fun `serves the admin UI from the classpath`() =
        testApplication {
            installModule()

            val response = client.get("/")

            assertEquals(HttpStatusCode.OK, response.status)
            assertContains(response.bodyAsText(), "watch-cluster")
        }

    @Test
    fun `api is open when no token is configured`() =
        testApplication {
            installModule(authToken = null)

            assertEquals(HttpStatusCode.OK, client.get("/api/apps").status)
        }
}
