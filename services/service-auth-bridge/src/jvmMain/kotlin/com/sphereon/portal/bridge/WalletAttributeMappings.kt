package com.sphereon.portal.bridge

/**
 * Typed wrapper around wallet attribute mappings (wallet key -> canonical attribute name).
 *
 * Used as an injectable type to avoid ambiguity with generic `Map<String, String>` bindings.
 * Derived from canonical attribute rules via [AttributeMappingDeriver].
 *
 * Example: `{given_name=given_name, family_name=family_name, is_library-walk-in=is_library_walk_in}`
 */
data class WalletAttributeMappings(val mappings: Map<String, String>) {
    /** Look up the canonical attribute name for a wallet attribute key. */
    fun resolve(walletKey: String): String? = mappings[walletKey]
}
