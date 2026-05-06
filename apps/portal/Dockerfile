# Bookworm (Debian) instead of Alpine — glibc is required by Next.js SWC and lightningcss native bindings
FROM node:22-bookworm-slim AS base

FROM base AS deps
WORKDIR /app

# Copy the built SDK packages where the lockfile's file: paths expect them
# package.json references file:../../idk/lib/{conf/theme,ui}/react — relative to WORKDIR /app
COPY idk/lib/conf/theme/react ../../idk/lib/conf/theme/react
COPY idk/lib/ui/react ../../idk/lib/ui/react

# Copy package files and install dependencies
COPY apps/portal/package.json apps/portal/package-lock.json ./
RUN npm install --install-links

FROM base AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY apps/portal/ .

ENV NEXT_TELEMETRY_DISABLED=1

# Branding build args — NEXT_PUBLIC_* vars are inlined at build time
ARG NEXT_PUBLIC_APP_NAME="KW1C Portal"
ARG NEXT_PUBLIC_PRIMARY_COLOR="#0085CA"
#ARG NEXT_PUBLIC_SECONDARY_COLOR="#EF2B2D"
ARG NEXT_PUBLIC_LOGO_URL="/branding/kw1c-logo.svg"
ARG NEXT_PUBLIC_LOGO_DARK_URL="/branding/kw1c-logo-dark.svg"
ENV NEXT_PUBLIC_APP_NAME=$NEXT_PUBLIC_APP_NAME
ENV NEXT_PUBLIC_PRIMARY_COLOR=$NEXT_PUBLIC_PRIMARY_COLOR
#ENV NEXT_PUBLIC_SECONDARY_COLOR=$NEXT_PUBLIC_SECONDARY_COLOR
ENV NEXT_PUBLIC_LOGO_URL=$NEXT_PUBLIC_LOGO_URL
ENV NEXT_PUBLIC_LOGO_DARK_URL=$NEXT_PUBLIC_LOGO_DARK_URL

RUN npm run build

FROM base AS runner
WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1

RUN apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/*
RUN groupadd --system --gid 1001 nodejs
RUN useradd --system --uid 1001 --gid nodejs nextjs

COPY --from=builder --chown=nextjs:nodejs /app/public ./public
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static
# Diagnostic fetch shim — loaded via NODE_OPTIONS when AUTH_DEBUG=true. Logs the
# URL + underlying cause for every failing outbound fetch (Auth.js / oauth4webapi
# only logs the opaque "fetch failed", which makes container-network and TLS
# misconfig hard to pin down). No-op when not preloaded.
COPY --from=builder --chown=nextjs:nodejs /app/fetch-trace.cjs ./fetch-trace.cjs

USER nextjs

EXPOSE 3000
ENV PORT=3000
ENV HOSTNAME="0.0.0.0"

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD wget -q --tries=1 --spider http://localhost:3000/ || exit 1

CMD ["node", "--require", "./fetch-trace.cjs", "server.js"]
