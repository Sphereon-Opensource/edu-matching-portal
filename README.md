# eduID Wallet Matching Portal

Privacy-preserving identity matching portal that connects eduID wallet credentials with institutional identities via SURFconext federation. Both federated login (OIDC via SURFconext) and wallet-based login (OID4VP) produce identical OIDC tokens from the Security Token Service, so downstream systems are unaware of which authentication method was used.

## Documentation

Full documentation is available at **[docs.sphereon.com/eduid-wallet-matching-portal](https://docs.sphereon.com/eduid-wallet-matching-portal/guides/getting-started)**.

Topics covered:
- [System Architecture](https://docs.sphereon.com/eduid-wallet-matching-portal/guides/architecture)
- [Authentication Flows](https://docs.sphereon.com/eduid-wallet-matching-portal/guides/authentication-flows) (federated, wallet fast-path, wallet reconciliation)
- [Identity Matching](https://docs.sphereon.com/eduid-wallet-matching-portal/guides/matching/overview) (HMAC-SHA256 hashing, identity link bindings)
- [Reconciliation](https://docs.sphereon.com/eduid-wallet-matching-portal/guides/reconciliation/overview) (selector rules, material profiles, IDV sessions)
- [Encryption & Key Management](https://docs.sphereon.com/eduid-wallet-matching-portal/guides/encryption/overview) (Keys A, B, C, AES-256-GCM, key rotation)
- [REST APIs](https://docs.sphereon.com/eduid-wallet-matching-portal/guides/rest-api/overview) (OID4VP sessions, external API, STS endpoints)
- [Database Schema](https://docs.sphereon.com/eduid-wallet-matching-portal/guides/database/overview) (7 tables, SqlDelight)
- [Deployment & Configuration](https://docs.sphereon.com/eduid-wallet-matching-portal/guides/operations/deployment)
- [Security & Privacy](https://docs.sphereon.com/eduid-wallet-matching-portal/guides/security/privacy-architecture)

## Quick Start

```bash
cd deploy/docker
docker compose up
```

Portal: http://localhost:3000 | STS: http://localhost:8092 | Auth Bridge: http://localhost:8090

## Architecture

| Service | Port | Technology | Purpose |
|---------|------|------------|---------|
| Portal (BFF) | 3000 | Next.js 15, NextAuth.js v5 | Frontend, session management, BFF proxy |
| Service-STS | 8092 | Kotlin/JVM, Ktor, IDK | OAuth2/OIDC authorization server |
| Service-Auth-Bridge | 8090 | Kotlin/JVM, Ktor, IDK | OID4VP, identity matching, reconciliation |
| PostgreSQL | 5432 | PostgreSQL 15 | Encrypted identity storage |

## Key Design Principles

- **Zero plaintext identifiers** in the database (HMAC-SHA256 hashed, AES-256-GCM encrypted)
- **Domain-separated keys** (Key A for holder hashing, Key B for institution hashing, Key C for encryption)
- **GDPR-by-architecture** (data minimization, crypto-shredding, right to erasure)
- **Standard OIDC output** (downstream systems don't know which auth method was used)
- **Configuration-driven reconciliation** (no code changes for new providers or rules)

## License

Apache-2.0. Copyright Sphereon International B.V.
