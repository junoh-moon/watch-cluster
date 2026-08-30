package com.watchcluster.api

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import mu.KotlinLogging
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

/**
 * Admin API and static UI.
 *
 * [authToken] is the opt-in bearer gate: when null the API is open and
 * protection is expected to come from the Ingress, which is the documented
 * default. Setting it closes the in-cluster hole where any pod could reach
 * the Service directly.
 */
fun Application.adminModule(
    adminService: AdminService,
    authToken: String? = null,
) {
    install(ContentNegotiation) {
        jackson {
            registerModule(KotlinModule.Builder().build())
            disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
        }
    }

    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorDto(cause.message ?: "Malformed request"))
        }
        exception<Throwable> { call, cause ->
            logger.error(cause) { "Unhandled error serving ${call.request.local.uri}" }
            call.respond(HttpStatusCode.InternalServerError, ErrorDto(cause.message ?: "Internal error"))
        }
    }

    routing {
        get("/healthz") { call.respond(MessageDtoOk) }
        get("/readyz") { call.respond(MessageDtoOk) }

        route("/api") {
            if (authToken != null) {
                requireBearerToken(authToken)
            }

            get("/apps") {
                call.respond(adminService.listApps())
            }

            get("/apps/{namespace}/{name}") {
                val (namespace, name) = call.deploymentRef()
                when (val detail = adminService.detail(namespace, name)) {
                    null -> call.respond(HttpStatusCode.NotFound, ErrorDto("Deployment $namespace/$name not found"))
                    else -> call.respond(detail)
                }
            }

            post("/apps/{namespace}/{name}/check") {
                val (namespace, name) = call.deploymentRef()
                // Accepted, not OK: the controller consumes the annotation
                // asynchronously and the check runs after this returns.
                call.respondOutcome(adminService.requestCheck(namespace, name), HttpStatusCode.Accepted)
            }

            put("/apps/{namespace}/{name}/watch") {
                val (namespace, name) = call.deploymentRef()
                val request = call.receiveNullable<WatchRequest>() ?: WatchRequest()
                call.respondOutcome(adminService.applyWatch(namespace, name, request))
            }

            delete("/apps/{namespace}/{name}/watch") {
                val (namespace, name) = call.deploymentRef()
                call.respondOutcome(adminService.unwatch(namespace, name))
            }

            get("/deployments") {
                call.respond(adminService.listCandidates())
            }
        }

        staticResources("/", "web", index = "index.html")
    }
}

private val MessageDtoOk = MessageDto("ok")

private fun Route.requireBearerToken(authToken: String) {
    val expected = authToken.toByteArray()
    intercept(ApplicationCallPipeline.Plugins) {
        val presented =
            call.request.headers["Authorization"]
                ?.removePrefix("Bearer ")
                ?.trim()
                ?.toByteArray()

        // Constant-time compare so the gate does not leak the token's prefix
        // through response timing.
        if (presented == null || !MessageDigest.isEqual(presented, expected)) {
            call.respond(HttpStatusCode.Unauthorized, ErrorDto("Missing or invalid bearer token"))
            finish()
        }
    }
}

private fun ApplicationCall.deploymentRef(): Pair<String, String> {
    val namespace = parameters["namespace"]?.takeIf { it.isNotBlank() }
    val name = parameters["name"]?.takeIf { it.isNotBlank() }
    if (namespace == null || name == null) {
        throw BadRequestException("Both namespace and name are required")
    }
    return namespace to name
}

private suspend fun ApplicationCall.respondOutcome(
    outcome: AdminService.Outcome,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
) {
    when (outcome) {
        is AdminService.Outcome.Ok -> respond(successStatus, MessageDto(outcome.message))
        is AdminService.Outcome.NotFound -> respond(HttpStatusCode.NotFound, ErrorDto("Deployment not found"))
        is AdminService.Outcome.Invalid -> respond(HttpStatusCode.BadRequest, ErrorDto(outcome.message))
        is AdminService.Outcome.Failed -> respond(HttpStatusCode.BadGateway, ErrorDto(outcome.message))
    }
}
