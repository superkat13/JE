package com.pineapple.sageos2

/**
 * Stable owner-facing entry point for the single Sage experience.
 *
 * MainActivity owns Chat plus its nested Settings/Advanced panels so there cannot be two different
 * Sage homes or an Advanced button that accidentally opens a second empty conversation screen.
 */
class SageHomeActivity : MainActivity()
