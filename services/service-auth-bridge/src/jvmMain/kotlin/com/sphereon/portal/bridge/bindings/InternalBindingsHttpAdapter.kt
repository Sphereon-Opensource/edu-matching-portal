package com.sphereon.portal.bridge.bindings

import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.http.HttpAdapter
import com.sphereon.core.api.http.command.CommandBackedHttpAdapter
import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpAdapterMount
import com.sphereon.core.api.http.describe.OpenApiHints
import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * HTTP adapter for internal bindings queries.
 * Mounts at /internal/bindings — called by the portal BFF.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesIntoSet(SessionScope::class, binding = binding<HttpAdapter>())
class InternalBindingsHttpAdapter(
    execution: SessionExecution,
    private val listBindingsCommand: ListBindingsCommand,
    private val deleteBindingCommand: DeleteBindingCommand,
) : CommandBackedHttpAdapter(
    id = ID,
    execution = execution,
    mount = HttpAdapterMount(
        serverPrefix = "",
        adapterBasePath = "/internal/bindings"
    )
) {
    companion object {
        const val ID = "internal.bindings.http.adapter"
    }

    override val endpointCommands: List<HttpEndpointCommand> = listOf(
        listBindingsCommand,
        deleteBindingCommand,
    )

    override val openApiHints = OpenApiHints(
        tags = setOf("internal-bindings"),
        operationIdPrefix = "internalBindings"
    )
}
