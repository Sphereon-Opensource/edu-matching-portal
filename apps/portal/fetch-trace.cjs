// Diagnostic shim: wrap global fetch so every outbound HTTP call leaves a trace.
// Loaded via NODE_OPTIONS=--require so it runs before the Next.js standalone
// server starts and intercepts every Auth.js / oauth4webapi outbound request.
//
// Behaviour:
//  - Always logs the method + URL on entry (so you can see the call sequence).
//  - On HTTP error (>= 400), logs status + a short body snippet so OIDC error
//    JSON like `{"error":"invalid_client","error_description":"..."}` is visible.
//  - On network failure, logs the message + underlying cause.
//  - Skips noisy URLs (health checks, Next.js internals) to avoid drowning the
//    actual signal.
const orig = globalThis.fetch
function shouldSkip(url) {
  return (
    url.includes('/_next') ||
    url.includes('/api/health/') ||
    url.includes('/api/federation/providers')
  )
}
globalThis.fetch = async function tracedFetch(input, init) {
  const url = typeof input === 'string' ? input : (input && input.url) || String(input)
  const method = init?.method || (typeof input !== 'string' && input?.method) || 'GET'
  if (shouldSkip(url)) return orig(input, init)
  console.log(`[fetch-trace] -> ${method} ${url}`)
  try {
    const res = await orig(input, init)
    if (!res.ok) {
      // Read the body (clone so the caller can still read it).
      let snippet = ''
      try {
        const cloned = res.clone()
        const text = await cloned.text()
        snippet = text.length > 400 ? text.slice(0, 400) + '…' : text
      } catch (e) {
        snippet = `<body read failed: ${e.message}>`
      }
      console.log(`[fetch-trace] <- ${method} ${url} ${res.status} :: ${snippet}`)
    } else {
      console.log(`[fetch-trace] <- ${method} ${url} ${res.status}`)
    }
    return res
  } catch (err) {
    const causeMsg =
      err?.cause?.code || err?.cause?.message || (err?.cause && String(err.cause))
    console.log(
      `[fetch-trace] FAIL ${method} ${url}: ${err.message}${causeMsg ? ` | cause=${causeMsg}` : ''}`,
    )
    throw err
  }
}
console.log('[fetch-trace] installed')
