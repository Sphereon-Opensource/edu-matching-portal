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

export const env = {
  // STS (OIDC Provider)
  STS_ISSUER_URL: optionalEnv('STS_ISSUER_URL', 'http://localhost:8080'),
  STS_CLIENT_ID: optionalEnv('STS_CLIENT_ID', 'portal'),
  STS_CLIENT_SECRET: optionalEnv('STS_CLIENT_SECRET', ''),

  // Auth Bridge
  AUTH_BRIDGE_URL: optionalEnv('AUTH_BRIDGE_URL', 'http://localhost:8090'),

  // next-auth
  NEXTAUTH_URL: optionalEnv('NEXTAUTH_URL', 'http://localhost:3000'),
  NEXTAUTH_SECRET: optionalEnv('NEXTAUTH_SECRET', ''),
} as const
