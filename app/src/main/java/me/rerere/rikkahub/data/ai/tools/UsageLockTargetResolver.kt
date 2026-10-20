package me.rerere.rikkahub.data.ai.tools

internal data class UsageLockTarget(val packageName: String, val label: String)

internal sealed interface UsageLockTargetResolution {
    data class Resolved(val target: UsageLockTarget) : UsageLockTargetResolution
    data class Rejected(val reason: String) : UsageLockTargetResolution
}

internal fun resolveUsageLockTarget(
    packageName: String?,
    label: String?,
    installedApps: Map<String, String>,
): UsageLockTargetResolution {
    val requestedPackage = packageName?.trim()?.takeIf(String::isNotEmpty)
    val requestedLabel = label?.trim()?.takeIf(String::isNotEmpty)
        ?: return UsageLockTargetResolution.Rejected(
            "Provide the exact installed app name in target_label before locking."
        )

    val exactLabelMatches = installedApps.filterValues { it.equals(requestedLabel, ignoreCase = true) }
    if (requestedPackage != null) {
        val installedLabel = installedApps[requestedPackage]
        if (installedLabel == null) {
            val labelPackages = exactLabelMatches.keys.sorted().joinToString()
            val correction = if (labelPackages.isEmpty()) "" else " Exact package for '$requestedLabel': $labelPackages."
            return UsageLockTargetResolution.Rejected(
                "Package '$requestedPackage' is not an available launcher app.$correction"
            )
        }
        if (!installedLabel.equals(requestedLabel, ignoreCase = true)) {
            val labelPackages = exactLabelMatches.keys.sorted().joinToString()
            val correction = if (labelPackages.isEmpty()) "" else " Exact package for '$requestedLabel': $labelPackages."
            return UsageLockTargetResolution.Rejected(
                "Package '$requestedPackage' belongs to '$installedLabel', not '$requestedLabel'.$correction"
            )
        }
        return UsageLockTargetResolution.Resolved(UsageLockTarget(requestedPackage, installedLabel))
    }

    return when (exactLabelMatches.size) {
        1 -> exactLabelMatches.entries.single().let { (installedPackage, installedLabel) ->
            UsageLockTargetResolution.Resolved(UsageLockTarget(installedPackage, installedLabel))
        }
        in 2..Int.MAX_VALUE -> UsageLockTargetResolution.Rejected(
            "App name '$requestedLabel' is ambiguous. Provide its exact package name too: ${exactLabelMatches.keys.sorted().joinToString()}."
        )
        else -> {
            val suggestions = installedApps.entries
                .filter { (_, installedLabel) -> installedLabel.contains(requestedLabel, ignoreCase = true) }
                .sortedBy { it.value.lowercase() }
                .take(8)
                .joinToString { "${it.value} (${it.key})" }
            val suffix = if (suggestions.isEmpty()) "" else " Similar installed apps: $suggestions."
            UsageLockTargetResolution.Rejected(
                "No installed app has the exact name '$requestedLabel'.$suffix"
            )
        }
    }
}
