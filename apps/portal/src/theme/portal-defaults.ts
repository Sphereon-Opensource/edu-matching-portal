/**
 * Portal-specific token overrides that customize the theme beyond
 * what the design system palette covers (shape, non-color tokens).
 *
 * Color overrides are now expressed via the DesignSystemPalette in
 * app-providers.tsx — not as flat token overrides here.
 */

import type { ThemeTokenMap, ThemeVariant } from '@sphereon/theme-react'

const sharedOverrides: ThemeTokenMap = {
  // Shape radii — portal uses slightly different values than M3 defaults
  'shape.cornerExtraSmall': '4dp',
  'shape.cornerSmall': '6dp',
  'shape.cornerMedium': '8dp',
  'shape.cornerLarge': '12dp',
  'shape.cornerExtraLarge': '16dp',
}

/** Get portal-specific overrides for a given variant */
export function getPortalDefaults(variant: ThemeVariant): ThemeTokenMap {
  return { ...sharedOverrides }
}
