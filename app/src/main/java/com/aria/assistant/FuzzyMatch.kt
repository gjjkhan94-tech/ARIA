package com.aria.assistant

/**
 * Small utility for tolerating near-miss spellings - e.g. voice-to-text turning
 * "Ahmad" into "Ahmed" or "Ihmad". Used as a LAST-resort matching tier, after
 * exact/prefix/whole-word matches have already failed.
 */
object FuzzyMatch {

    /** Classic edit-distance: how many single-character changes turn a into b. */
    fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[a.length][b.length]
    }

    /**
     * Returns true if `a` and `b` are close enough to be considered a likely mishear/typo.
     * Threshold scales with word length so short names still need to be quite close,
     * while longer names tolerate a bit more drift.
     */
    fun isCloseMatch(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        val maxLen = maxOf(a.length, b.length)
        val allowedDistance = when {
            maxLen <= 4 -> 1
            maxLen <= 7 -> 2
            else -> 3
        }
        return levenshtein(a.lowercase(), b.lowercase()) <= allowedDistance
    }
}
