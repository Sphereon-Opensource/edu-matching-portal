package com.sphereon.portal.bridge

import com.sphereon.core.api.conf.PropertyResolver
import com.sphereon.attribute.flow.AttributePath
import com.sphereon.identity.idv.model.AttributePredicate
import com.sphereon.identity.idv.model.MatchOperator
import com.sphereon.identity.reconciliation.model.BindingPolicy
import com.sphereon.identity.reconciliation.model.KnownHolderState
import com.sphereon.identity.reconciliation.model.ReconciliationDecision
import com.sphereon.identity.reconciliation.model.ReconciliationPlanTemplate
import com.sphereon.identity.reconciliation.model.ReconciliationSelectorRule

/**
 * Binds reconciliation selector rules from indexed configuration entries.
 *
 * The auth-bridge currently owns deployment-specific selector values, so it reads
 * them from `identity.reconciliation.selector-rules[*]` and provides the resulting
 * rules as DI inputs for [com.sphereon.portal.bridge.orchestration.ReconciliationOrchestrator].
 */
object AuthBridgeReconciliationSelectorConfigBinder {

    internal const val CONFIG_PREFIX = "identity.reconciliation"
    private const val SELECTOR_RULES_PREFIX = "$CONFIG_PREFIX.selector-rules"

    fun bindRuleVersion(configService: PropertyResolver): String =
        configService.getPropertyAsString("$CONFIG_PREFIX.rule-version", null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "unversioned"

    fun bindSelectorRules(configService: PropertyResolver): List<ReconciliationSelectorRule> {
        val rules = mutableListOf<ReconciliationSelectorRule>()
        var index = 0
        while (true) {
            val rulePrefix = "$SELECTOR_RULES_PREFIX[$index]"
            val id = configService.getPropertyAsString("$rulePrefix.id", null)?.trim() ?: break
            rules += ReconciliationSelectorRule(
                id = id,
                enabled = configService.getPropertyAsString("$rulePrefix.enabled", "true")?.toBoolean() != false,
                priority = configService.getPropertyAsString("$rulePrefix.priority", "0")?.toIntOrNull() ?: 0,
                tenants = readOptionalStringSet(configService, "$rulePrefix.tenants"),
                entryPointTypes = readOptionalStringSet(configService, "$rulePrefix.entry-point-types"),
                triggerTypes = readOptionalStringSet(configService, "$rulePrefix.trigger-types"),
                queryIds = readOptionalStringSet(configService, "$rulePrefix.query-ids"),
                dcqlCredentialQueryIds = readOptionalStringSet(configService, "$rulePrefix.dcql-credential-query-ids"),
                dcqlCredentialSetRefs = readOptionalStringSet(configService, "$rulePrefix.dcql-credential-set-refs"),
                credentialTypes = readOptionalStringSet(configService, "$rulePrefix.credential-types"),
                issuers = readOptionalStringSet(configService, "$rulePrefix.issuers"),
                attributePredicates = readAttributePredicates(configService, "$rulePrefix.attribute-predicates"),
                knownHolderStates = readKnownHolderStates(configService, "$rulePrefix.known-holder-states"),
                requestedProjections = readOptionalStringSet(configService, "$rulePrefix.requested-projections"),
                plan = readPlan(configService, "$rulePrefix.plan"),
            )
            index++
        }
        return rules
    }

    private fun readPlan(
        configService: PropertyResolver,
        prefix: String,
    ): ReconciliationPlanTemplate {
        val decision = requireEnum<ReconciliationDecision>(
            configService.getPropertyAsString("$prefix.decision", null),
            "$prefix.decision"
        )
        return ReconciliationPlanTemplate(
            decision = decision,
            providerId = configService.getPropertyAsString("$prefix.provider-id", null)?.ifBlank { null },
            methodId = configService.getPropertyAsString("$prefix.method-id", null)?.ifBlank { null },
            materialProfileId = configService.getPropertyAsString("$prefix.material-profile-id", null)?.ifBlank { null },
            requiredAttributeNames = readStringSet(configService, "$prefix.required-attribute-names"),
            minimumAssurance = configService.getPropertyAsString("$prefix.minimum-assurance", null)?.ifBlank { null },
            bindingPolicy = parseEnum<BindingPolicy>(configService.getPropertyAsString("$prefix.binding-policy", null))
                ?: BindingPolicy.REUSE_OR_CREATE,
            failReason = configService.getPropertyAsString("$prefix.fail-reason", null)?.ifBlank { null },
        )
    }

    private fun readAttributePredicates(
        configService: PropertyResolver,
        prefix: String,
    ): List<AttributePredicate>? {
        val predicates = mutableListOf<AttributePredicate>()
        var index = 0
        while (true) {
            val itemPrefix = "$prefix[$index]"
            val attributePath = configService.getPropertyAsString("$itemPrefix.attribute-path", null)
                ?: configService.getPropertyAsString("$itemPrefix.path", null)
                ?: break
            val operator = requireEnum<MatchOperator>(
                configService.getPropertyAsString("$itemPrefix.operator", null),
                "$itemPrefix.operator"
            )
            predicates += AttributePredicate(
                attributePath = AttributePath(attributePath),
                operator = operator,
                value = configService.getPropertyAsString("$itemPrefix.value", null)?.ifBlank { null },
            )
            index++
        }
        return predicates.ifEmpty { null }
    }

    private fun readKnownHolderStates(
        configService: PropertyResolver,
        prefix: String,
    ): Set<KnownHolderState>? =
        readStringSet(configService, prefix)
            .map { requireEnum<KnownHolderState>(it, prefix) }
            .toSet()
            .ifEmpty { null }

    private fun readOptionalStringSet(
        configService: PropertyResolver,
        key: String,
    ): Set<String>? =
        readStringSet(configService, key).ifEmpty { null }

    private fun readStringSet(
        configService: PropertyResolver,
        key: String,
    ): Set<String> {
        val values = linkedSetOf<String>()
        configService.getPropertyAsString(key, null)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach(values::add)

        var index = 0
        while (true) {
            val value = configService.getPropertyAsString("$key[$index]", null)?.trim() ?: break
            if (value.isNotEmpty()) {
                values += value
            }
            index++
        }
        return values
    }

    private inline fun <reified T : Enum<T>> parseEnum(value: String?): T? =
        value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.replace('-', '_')
            ?.uppercase()
            ?.let { normalized -> enumValues<T>().firstOrNull { it.name == normalized } }

    private inline fun <reified T : Enum<T>> requireEnum(value: String?, key: String): T =
        parseEnum<T>(value) ?: error("Missing or invalid value for $key: $value")
}
