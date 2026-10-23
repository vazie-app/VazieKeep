package app.vazie.keep.vault

import app.vazie.keep.core.GroupId
import app.vazie.keep.core.VaultGroup
import app.vazie.keep.core.VaultInfo

// "Web / Work" for a picker; the root is shown by the vault's name.
data class GroupChoice(val id: GroupId, val path: String)

fun groupChoices(groups: List<VaultGroup>, info: VaultInfo, includeRecycleBin: Boolean = false): List<GroupChoice> {
    val byId = groups.associateBy { it.id }
    fun path(group: VaultGroup): String {
        if (group.id == info.rootGroupId) return info.name
        val names = mutableListOf(group.name)
        var parent = group.parentId
        while (parent != null && parent != info.rootGroupId) {
            val next = byId[parent] ?: break
            names.add(0, next.name)
            parent = next.parentId
        }
        return names.joinToString(" / ")
    }
    return groups
        .filter { includeRecycleBin || !isInRecycleBin(groups, info.recycleBinId, it.id) }
        .map { GroupChoice(it.id, path(it)) }
        .sortedWith(compareBy<GroupChoice> { it.id != info.rootGroupId }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.path })
}

fun isInRecycleBin(groups: List<VaultGroup>, bin: GroupId?, group: GroupId): Boolean {
    if (bin == null) return false
    val byId = groups.associateBy { it.id }
    var current: GroupId? = group
    var guard = 0
    while (current != null && guard++ < groups.size + 1) {
        if (current == bin) return true
        current = byId[current]?.parentId
    }
    return false
}

fun descendants(groups: List<VaultGroup>, group: GroupId): Set<GroupId> {
    val found = mutableSetOf<GroupId>()
    val queue = ArrayDeque(listOf(group))
    while (queue.isNotEmpty()) {
        val next = queue.removeFirst()
        groups.filter { it.parentId == next }.forEach { if (found.add(it.id)) queue.addLast(it.id) }
    }
    return found
}
