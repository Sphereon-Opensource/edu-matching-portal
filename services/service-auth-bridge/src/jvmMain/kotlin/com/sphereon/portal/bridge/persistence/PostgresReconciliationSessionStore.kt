package com.sphereon.portal.bridge.persistence

import com.sphereon.identity.matching.crypto.EncryptedPayload
import com.sphereon.identity.matching.model.IdentifierType
import com.sphereon.identity.reconciliation.model.ReconciliationSession
import com.sphereon.identity.reconciliation.model.ReconciliationSessionStatus
import com.sphereon.identity.reconciliation.impl.store.InMemoryReconciliationSessionStore
import com.sphereon.identity.reconciliation.store.ReconciliationSessionStore
import com.sphereon.portal.bridge.db.AuthBridgeDatabase
import kotlinx.datetime.Instant
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(
    AppScope::class,
    binding = binding<ReconciliationSessionStore>(),
    replaces = [InMemoryReconciliationSessionStore::class]
)
class PostgresReconciliationSessionStore(
    private val database: AuthBridgeDatabase,
) : ReconciliationSessionStore {

    override suspend fun findById(tenantId: String, sessionId: String): ReconciliationSession? {
        return database.authBridgeQueries
            .findSessionById(tenantId, sessionId)
            .executeAsOneOrNull()
            ?.toDomain()
    }

    override suspend fun findByState(tenantId: String, state: String): ReconciliationSession? {
        return database.authBridgeQueries
            .findSessionByState(state)
            .executeAsOneOrNull()
            ?.toDomain()
    }

    override suspend fun create(session: ReconciliationSession): ReconciliationSession {
        database.authBridgeQueries.insertSession(
            id = session.id,
            tenantId = session.tenantId,
            status = session.status.name,
            identifierHash = session.identifierHash,
            identifierType = session.identifierType.value,
            providerId = session.providerId,
            authorizationUrl = session.authorizationUrl,
            state = session.state,
            nonce = session.nonce,
            codeVerifier = session.codeVerifier,
            redirectUri = session.redirectUri,
            tokenEndpoint = session.tokenEndpoint,
            encryptedIdentity = session.encryptedIdentity?.ciphertext,
            encryptedIdentityKeyVersion = session.encryptedIdentity?.keyVersion,
            errorMessage = session.errorMessage,
            createdAt = session.createdAt,
            expiresAt = session.expiresAt,
        )
        return session
    }

    override suspend fun update(session: ReconciliationSession): ReconciliationSession {
        database.authBridgeQueries.updateSession(
            sessionId = session.id,
            tenantId = session.tenantId,
            status = session.status.name,
            authorizationUrl = session.authorizationUrl,
            state = session.state,
            nonce = session.nonce,
            codeVerifier = session.codeVerifier,
            redirectUri = session.redirectUri,
            tokenEndpoint = session.tokenEndpoint,
            encryptedIdentity = session.encryptedIdentity?.ciphertext,
            encryptedIdentityKeyVersion = session.encryptedIdentity?.keyVersion,
            errorMessage = session.errorMessage,
        )
        return session
    }

    override suspend fun delete(tenantId: String, sessionId: String): Boolean {
        database.authBridgeQueries.deleteSession(tenantId, sessionId)
        return true
    }

    override suspend fun findExpired(tenantId: String, cutoff: Instant): List<ReconciliationSession> {
        return database.authBridgeQueries
            .findExpiredSessions(cutoff)
            .executeAsList()
            .map { it.toDomain() }
    }

    private fun com.sphereon.portal.bridge.db.Reconciliation_session.toDomain(): ReconciliationSession {
        val encryptedId = if (encrypted_identity != null && encrypted_identity_key_version != null) {
            EncryptedPayload(ciphertext = encrypted_identity, keyVersion = encrypted_identity_key_version)
        } else null

        return ReconciliationSession(
            id = id,
            tenantId = tenant_id,
            status = ReconciliationSessionStatus.valueOf(status),
            identifierHash = identifier_hash,
            identifierType = IdentifierType(identifier_type),
            providerId = provider_id,
            authorizationUrl = authorization_url,
            state = state,
            nonce = nonce,
            codeVerifier = code_verifier,
            redirectUri = redirect_uri,
            tokenEndpoint = token_endpoint,
            encryptedIdentity = encryptedId,
            errorMessage = error_message,
            createdAt = created_at,
            expiresAt = expires_at,
        )
    }
}
