FROM node:22-alpine AS base

FROM base AS deps
RUN apk add --no-cache libc6-compat
WORKDIR /app

# Copy the pre-built SDK tarballs
COPY apps/portal/sphereon-theme-react-0.1.0.tgz /tmp/sphereon-theme-react-0.1.0.tgz
COPY apps/portal/sphereon-ui-react-0.1.0.tgz /tmp/sphereon-ui-react-0.1.0.tgz

# Copy package files and install dependencies
COPY apps/portal/package.json apps/portal/package-lock.json* ./
# Rewrite the local file dependencies to use the copied tarballs
RUN sed -i 's|"@sphereon/theme-react":.*|"@sphereon/theme-react": "file:/tmp/sphereon-theme-react-0.1.0.tgz",|' package.json && \
    sed -i 's|"@sphereon/ui-react":.*|"@sphereon/ui-react": "file:/tmp/sphereon-ui-react-0.1.0.tgz",|' package.json
RUN npm install && npm install @parcel/watcher-linux-x64-musl --save-optional 2>/dev/null || true

FROM base AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY apps/portal/ .

ENV NEXT_TELEMETRY_DISABLED=1

# Branding build args — NEXT_PUBLIC_* vars are inlined at build time
ARG NEXT_PUBLIC_APP_NAME="KW1C Portal"
ARG NEXT_PUBLIC_PRIMARY_COLOR="#0085CA"
ARG NEXT_PUBLIC_SECONDARY_COLOR="#EF2B2D"
ARG NEXT_PUBLIC_LOGO_URL="/branding/kw1c-logo.svg"
ARG NEXT_PUBLIC_LOGO_DARK_URL="/branding/kw1c-logo-dark.svg"
ENV NEXT_PUBLIC_APP_NAME=$NEXT_PUBLIC_APP_NAME
ENV NEXT_PUBLIC_PRIMARY_COLOR=$NEXT_PUBLIC_PRIMARY_COLOR
ENV NEXT_PUBLIC_SECONDARY_COLOR=$NEXT_PUBLIC_SECONDARY_COLOR
ENV NEXT_PUBLIC_LOGO_URL=$NEXT_PUBLIC_LOGO_URL
ENV NEXT_PUBLIC_LOGO_DARK_URL=$NEXT_PUBLIC_LOGO_DARK_URL

RUN npm run build

FROM base AS runner
WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1

RUN addgroup --system --gid 1001 nodejs
RUN adduser --system --uid 1001 nextjs

COPY --from=builder --chown=nextjs:nodejs /app/public ./public
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static

USER nextjs

EXPOSE 3000
ENV PORT=3000
ENV HOSTNAME="0.0.0.0"

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:3000/ || exit 1

CMD ["node", "server.js"]
