// ── 4KHDHub CloudStream Extension ──────────────────────────────────────────
// Place this folder inside the `app/src/main/kotlin/` extensions directory
// of your CloudStream fork, or build with the CS3 plugin template.

version = 1  // bump when you push updates

cloudstream {
    // Shown in the extension list
    description = "Movies & TV Shows from 4KHDHub — 4K, HDR, 1080p, multi-audio (Hindi/English/Tamil/Telugu)"
    authors     = listOf("YourName")

    /**
     * Status
     *   0 = Down / broken
     *   1 = Ok
     *   2 = Slow
     *   3 = Beta
     */
    status  = 1
    tvTypes = listOf("Movie", "TvSeries")

    iconUrl = "https://4khdhub.one/images/4KHDHUB-Dark-Logo.png"
}
