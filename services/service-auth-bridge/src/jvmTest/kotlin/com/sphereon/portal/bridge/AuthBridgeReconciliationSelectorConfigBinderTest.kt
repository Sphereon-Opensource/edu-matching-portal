package com.sphereon.portal.bridge

import com.sphereon.core.api.conf.PropertyResolver
import com.sphereon.attribute.flow.AttributeBag
import com.sphereon.attribute.flow.AttributePath
import com.sphereon.identity.idv.model.MatchOperator
import com.sphereon.identity.reconciliation.model.BindingPolicy
import com.sphereon.identity.reconciliation.model.FailClosed
import com.sphereon.identity.reconciliation.model.KnownHolderState
import com.sphereon.identity.reconciliation.model.ReconciliationDecision
import com.sphereon.identity.reconciliation.model.RunIdv
import com.sphereon.identity.reconciliation.model.StepUp
import com.sphereon.identity.reconciliation.api.ReconciliationSelector
import com.sphereon.identity.reconciliation.model.ReconciliationSelectorInput
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive

class AuthBridgeReconciliationSelectorConfigBinderTest {

    @Test
    fun bindsMinimalRunIdvRule() {
        val resolver = MapPropertyResolver(
            mapOf(
                "identity.reconciliation.rule-version" to "2026-03-17",
                "identity.reconciliation.selector-rules[0].id" to "default-surf",
                "identity.reconciliation.selector-rules[0].priority" to "0",
                "identity.reconciliation.selector-rules[0].plan.decision" to "RUN_IDV",
                "identity.reconciliation.selector-rules[0].plan.provider-id" to "surf",
                "identity.reconciliation.selector-rules[0].plan.method-id" to "surf-oidc",
                "identity.reconciliation.selector-rules[0].plan.material-profile-id" to "holder-only-v1",
            )
        )

        val rules = AuthBridgeReconciliationSelectorConfigBinder.bindSelectorRules(resolver)
        val version = AuthBridgeReconciliationSelectorConfigBinder.bindRuleVersion(resolver)
        val plan = ReconciliationSelector.evaluate(
            rules = rules,
            input = ReconciliationSelectorInput(tenantId = "default"),
            ruleVersion = version,
        )

        assertEquals("2026-03-17", version)
        assertEquals(1, rules.size)
        val runIdv = assertIs<RunIdv>(plan)
        assertEquals("surf", runIdv.providerId)
        assertEquals("surf-oidc", runIdv.methodId)
        assertEquals("holder-only-v1", runIdv.materialProfileId)
        assertEquals("2026-03-17", runIdv.selectorRuleVersion)
    }

    @Test
    fun bindsRichRuleDimensionsAndPlanFields() {
        val resolver = MapPropertyResolver(
            mapOf(
                "identity.reconciliation.rule-version" to "2026-03-17",
                "identity.reconciliation.selector-rules[0].id" to "kw1c-student-step-up",
                "identity.reconciliation.selector-rules[0].enabled" to "true",
                "identity.reconciliation.selector-rules[0].priority" to "90",
                "identity.reconciliation.selector-rules[0].tenants[0]" to "kw1c",
                "identity.reconciliation.selector-rules[0].entry-point-types[0]" to "oid4vp",
                "identity.reconciliation.selector-rules[0].trigger-types[0]" to "idv_required",
                "identity.reconciliation.selector-rules[0].query-ids[0]" to "kw1c-enrollment",
                "identity.reconciliation.selector-rules[0].dcql-credential-query-ids[0]" to "student_credential",
                "identity.reconciliation.selector-rules[0].dcql-credential-set-refs[0]" to "set-student",
                "identity.reconciliation.selector-rules[0].credential-types[0]" to "EduIDCredential",
                "identity.reconciliation.selector-rules[0].issuers[0]" to "https://issuer.example",
                "identity.reconciliation.selector-rules[0].known-holder-states[0]" to "matched_claim_tuple",
                "identity.reconciliation.selector-rules[0].requested-projections[0]" to "sts-default",
                "identity.reconciliation.selector-rules[0].attribute-predicates[0].attribute-path" to "affiliation",
                "identity.reconciliation.selector-rules[0].attribute-predicates[0].operator" to "equals",
                "identity.reconciliation.selector-rules[0].attribute-predicates[0].value" to "student",
                "identity.reconciliation.selector-rules[0].plan.decision" to "step-up",
                "identity.reconciliation.selector-rules[0].plan.provider-id" to "surf",
                "identity.reconciliation.selector-rules[0].plan.method-id" to "surf-oidc",
                "identity.reconciliation.selector-rules[0].plan.material-profile-id" to "personalia-v1",
                "identity.reconciliation.selector-rules[0].plan.required-attribute-names[0]" to "given_name",
                "identity.reconciliation.selector-rules[0].plan.required-attribute-names[1]" to "family_name",
                "identity.reconciliation.selector-rules[0].plan.minimum-assurance" to "substantial",
                "identity.reconciliation.selector-rules[0].plan.binding-policy" to "create_new",
                "identity.reconciliation.selector-rules[1].id" to "fail-closed",
                "identity.reconciliation.selector-rules[1].priority" to "10",
                "identity.reconciliation.selector-rules[1].plan.decision" to "FAIL_CLOSED",
                "identity.reconciliation.selector-rules[1].plan.fail-reason" to "No provider available",
            )
        )

        val rules = AuthBridgeReconciliationSelectorConfigBinder.bindSelectorRules(resolver)

        assertEquals(2, rules.size)
        val firstRule = rules.first()
        assertEquals(setOf("kw1c"), firstRule.tenants)
        assertEquals(setOf("oid4vp"), firstRule.entryPointTypes)
        assertEquals(setOf("idv_required"), firstRule.triggerTypes)
        assertEquals(setOf("kw1c-enrollment"), firstRule.queryIds)
        assertEquals(setOf("student_credential"), firstRule.dcqlCredentialQueryIds)
        assertEquals(setOf("set-student"), firstRule.dcqlCredentialSetRefs)
        assertEquals(setOf("EduIDCredential"), firstRule.credentialTypes)
        assertEquals(setOf("https://issuer.example"), firstRule.issuers)
        assertEquals(setOf(KnownHolderState.MATCHED_CLAIM_TUPLE), firstRule.knownHolderStates)
        assertEquals(setOf("sts-default"), firstRule.requestedProjections)
        assertEquals(1, firstRule.attributePredicates?.size)
        assertEquals(MatchOperator.EQUALS, firstRule.attributePredicates?.single()?.operator)
        assertEquals("affiliation", firstRule.attributePredicates?.single()?.attributePath?.value)
        assertEquals("student", firstRule.attributePredicates?.single()?.value)
        assertEquals(ReconciliationDecision.STEP_UP, firstRule.plan.decision)
        assertEquals(BindingPolicy.CREATE_NEW, firstRule.plan.bindingPolicy)
        assertEquals(setOf("given_name", "family_name"), firstRule.plan.requiredAttributeNames)

        val stepUpPlan = ReconciliationSelector.evaluate(
            rules = rules,
            input = ReconciliationSelectorInput(
                tenantId = "kw1c",
                entryPointType = "oid4vp",
                triggerType = "idv_required",
                queryId = "kw1c-enrollment",
                dcqlCredentialQueryIds = setOf("student_credential"),
                dcqlCredentialSetRefs = setOf("set-student"),
                presentedCredentialTypes = setOf("EduIDCredential"),
                issuers = setOf("https://issuer.example"),
                knownHolderState = KnownHolderState.MATCHED_CLAIM_TUPLE,
                requestedProjection = "sts-default",
                availableAttributes = AttributeBag.empty().with(AttributePath("affiliation"), JsonPrimitive("student")),
            ),
            ruleVersion = "2026-03-17",
        )
        assertIs<StepUp>(stepUpPlan)

        val failPlan = ReconciliationSelector.evaluate(
            rules = rules.drop(1),
            input = ReconciliationSelectorInput(tenantId = "default"),
            ruleVersion = "2026-03-17",
        )
        assertIs<FailClosed>(failPlan)
        assertEquals("No provider available", failPlan.reason)

        val unmatched = ReconciliationSelector.evaluate(
            rules = listOf(firstRule),
            input = ReconciliationSelectorInput(tenantId = "other"),
            ruleVersion = "2026-03-17",
        )
        assertNull(unmatched)
    }

    private class MapPropertyResolver(
        private val properties: Map<String, String>,
    ) : PropertyResolver {
        override fun containsProperty(key: String): Boolean = properties.containsKey(key)

        override fun <T : Any> getProperty(key: String, targetType: KClass<T>, defaultValue: T?): T? {
            val value = properties[key] ?: return defaultValue
            @Suppress("UNCHECKED_CAST")
            return when (targetType) {
                String::class -> value as T
                else -> defaultValue
            }
        }

        override fun <T : Any> getRequiredProperty(key: String, targetType: KClass<T>, defaultValue: T?): T =
            getProperty(key, targetType, defaultValue)
                ?: error("Missing property: $key")

        override fun getPropertyAsString(key: String, defaultValue: String?): String? =
            properties[key] ?: defaultValue

        override fun getRequiredPropertyAsString(key: String, defaultValue: String?): String =
            getPropertyAsString(key, defaultValue) ?: error("Missing property: $key")

        override fun getAllProperties(): Map<String, Any> = properties

        override fun getAllPropertiesAsString(redact: Boolean): Map<String, String> = properties

        override fun getSubProperties(prefixes: Set<String>, stripPrefix: Boolean): Map<String, Any> =
            properties
                .filterKeys { key -> prefixes.any { prefix -> key == prefix || key.startsWith("$prefix.") } }
                .mapValues { it.value }

        override fun getSubPropertiesAsString(
            prefixes: Set<String>,
            stripPrefix: Boolean,
            redact: Boolean,
        ): Map<String, String> =
            properties.filterKeys { key -> prefixes.any { prefix -> key == prefix || key.startsWith("$prefix.") } }
    }
}
