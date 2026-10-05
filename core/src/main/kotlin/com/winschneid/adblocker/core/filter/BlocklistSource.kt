package com.winschneid.adblocker.core.filter

/** A remote blocklist the app downloads and keeps up to date. */
data class BlocklistSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    /** Shipped with the app: can be disabled but not deleted. */
    val builtIn: Boolean = false,
    val description: String = "",
    /** Epoch millis of the last successful download, 0 if never. */
    val lastUpdated: Long = 0L,
    val entryCount: Int = 0,
    val lastError: String? = null,
)

object DefaultBlocklists {
    val sources: List<BlocklistSource> = listOf(
        BlocklistSource(
            id = "stevenblack-unified",
            name = "StevenBlack Unified hosts",
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            builtIn = true,
            description = "Adware + malware hosts (MIT License)",
        ),
        BlocklistSource(
            id = "adaway",
            name = "AdAway hosts",
            url = "https://raw.githubusercontent.com/AdAway/adaway.github.io/master/hosts.txt",
            builtIn = true,
            description = "Mobile ad servers (CC BY 3.0)",
        ),
        BlocklistSource(
            id = "yoyo",
            name = "Peter Lowe's Ad and tracking server list",
            url = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
            enabled = false,
            builtIn = true,
            description = "pgl.yoyo.org",
        ),
    )
}
