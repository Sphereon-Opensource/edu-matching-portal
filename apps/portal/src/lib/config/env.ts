function requiredEnv(name: string): string {
  const value = process.env[name]
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`)
  }
  return value
}

function optionalEnv(name: string, defaultValue: string): string {
  return process.env[name] || defaultValue
}

// Browser-facing STS URL (issuer, redirect URIs, what the user's browser sees).
// MUST match what's registered with the upstream IdP (e.g. SURFconext).
const stsIssuer = optionalEnv('STS_ISSUER_URL', 'http://localhost:8080')

export const env = {
  // STS (OIDC Provider)
  STS_ISSUER_URL: stsIssuer,
  /**
   * Server-side URL the Next.js portal uses to reach the STS from inside the docker
   * network (e.g. `http://service-sts:8092`). Used for OIDC discovery, token, and
   * userinfo fetches — none of which the browser ever sees. Falls back to
   * [STS_ISSUER_URL] when not set, which is correct outside of containerized
   * deployments where the server can reach the issuer URL directly.
   */
  STS_INTERNAL_URL: optionalEnv('STS_INTERNAL_URL', stsIssuer),
  STS_CLIENT_ID: optionalEnv('STS_CLIENT_ID', 'portal'),
  STS_CLIENT_SECRET: optionalEnv('STS_CLIENT_SECRET', ''),

  // Auth Bridge
  AUTH_BRIDGE_URL: optionalEnv('AUTH_BRIDGE_URL', 'http://localhost:8090'),

  // next-auth
  NEXTAUTH_URL: optionalEnv('NEXTAUTH_URL', 'http://localhost:3000'),
  NEXTAUTH_SECRET: optionalEnv('NEXTAUTH_SECRET', ''),
} as const
