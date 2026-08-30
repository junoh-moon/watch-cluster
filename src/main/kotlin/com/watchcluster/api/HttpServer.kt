package com.watchcluster.api

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Serves the admin API and UI from the controller process.
 *
 * Co-located deliberately: schedule state and check history live only in the
 * controller's in-memory workers, so a separate process could only ever show
 * what the annotations already say.
 */
class HttpServer(
    private val adminService: AdminService,
    private val port: Int = DEFAULT_PORT,
    private val authToken: String? = null,
) {
    private val engine =
        embeddedServer(Netty, port = port) {
            adminModule(adminService, authToken)
        }

    fun start() {
        engine.start(wait = false)
        logger.info { "Admin UI listening on port $port (auth: ${if (authToken != null) "bearer token" else "none — protect via Ingress"})" }
    }

    fun stop() {
        engine.stop(GRACE_PERIOD_MS, SHUTDOWN_TIMEOUT_MS)
        logger.info { "Admin UI stopped" }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        private const val GRACE_PERIOD_MS = 1_000L
        private const val SHUTDOWN_TIMEOUT_MS = 5_000L

        fun fromEnvironment(adminService: AdminService): HttpServer =
            HttpServer(
                adminService = adminService,
                port = System.getenv("HTTP_PORT")?.toIntOrNull() ?: DEFAULT_PORT,
                // Trimmed: a Secret created from a file usually carries a
                // trailing newline, which would otherwise match nothing.
                authToken = System.getenv("ADMIN_TOKEN")?.trim()?.takeIf { it.isNotEmpty() },
            )
    }
}
