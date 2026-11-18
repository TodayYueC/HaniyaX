package dev.haniya.x.agents

/**
 * Minimal semantic-version handling for agent versions ("2.1.325", "0.169.0", "2.3.0",
 * "0.0.0-beta-17236", "1.2.3-alpha.4"). Build metadata (+…) is ignored for ordering.
 * Pure Kotlin — unit tested on the JVM.
 */
data class Semver(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** Pre-release identifiers (split on '.'); empty = release. */
    val pre: List<String> = emptyList(),
) : Comparable<Semver> {

    val isPrerelease: Boolean get() = pre.isNotEmpty()

    override fun compareTo(other: Semver): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        // A release ranks above any of its pre-releases.
        if (pre.isEmpty() && other.pre.isEmpty()) return 0
        if (pre.isEmpty()) return 1
        if (other.pre.isEmpty()) return -1
        val n = minOf(pre.size, other.pre.size)
        for (i in 0 until n) {
            val c = compareIdentifier(pre[i], other.pre[i])
            if (c != 0) return c
        }
        return pre.size.compareTo(other.pre.size)
    }

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        if (pre.isNotEmpty()) append('-').append(pre.joinToString("."))
    }

    companion object {
        private val STRICT = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        /** Finds the first x.y.z[-pre] inside arbitrary output such as "codex-cli 0.169.0" or "2.1.325 (Claude Code)". */
        val IN_TEXT = Regex("""(?<![0-9A-Za-z.])v?(\d+\.\d+\.\d+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?)""")

        fun parse(text: String?): Semver? {
            val m = STRICT.matchEntire(text?.trim() ?: return null) ?: return null
            val (a, b, c, p) = m.destructured
            return Semver(
                a.toIntOrNull() ?: return null,
                b.toIntOrNull() ?: return null,
                c.toIntOrNull() ?: return null,
                if (p.isEmpty()) emptyList() else p.split('.').filter { it.isNotEmpty() },
            )
        }

        /** Extracts a version string from command output using [regex] (group 1 when present). */
        fun extract(output: String, regex: Regex = IN_TEXT): String? {
            val m = regex.find(output) ?: return null
            val raw = (if (m.groupValues.size > 1) m.groupValues[1] else m.value).trim()
            return raw.removePrefix("v").trimEnd('.', '-').ifEmpty { null }
        }

        /** True when [candidate] is strictly newer than [current]. Unparseable versions are never "newer". */
        fun isNewer(candidate: String?, current: String?): Boolean {
            val a = parse(candidate) ?: return false
            val b = parse(current) ?: return false
            return a > b
        }

        fun compare(a: String, b: String): Int {
            val x = parse(a); val y = parse(b)
            return when {
                x != null && y != null -> x.compareTo(y)
                x != null -> 1
                y != null -> -1
                else -> a.compareTo(b)
            }
        }

        private fun compareIdentifier(a: String, b: String): Int {
            val x = a.toLongOrNull(); val y = b.toLongOrNull()
            return when {
                x != null && y != null -> x.compareTo(y)
                x != null -> -1 // numeric identifiers rank below alphanumeric ones
                y != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
