package com.will.ambienthome.model

/**
 * Детерминированная логика упорядочивания и сдвига плиток приложений (без Android-зависимостей).
 * Поддерживает строгую границу между закреплёнными (★) и обычными приложениями.
 */
object AppOrdering {

    data class ShiftResult(
        val newDockOrder: List<String>,
        val newPinnedOrder: List<String>?
    )

    data class MoveBounds(
        val canMoveLeft: Boolean,
        val canMoveRight: Boolean
    )

    /**
     * Детерминированная сортировка видимых приложений:
     * 1) Закреплённые (pinned) в строгом порядке пользователя;
     * 2) Сохранённый пользовательский порядок (dockOrder);
     * 3) Дефолтный приоритет ТВ-приложений;
     * 4) Остальные по алфавиту label (case-insensitive).
     */
    fun orderPackageNames(
        installed: List<Pair<String, String>>,
        hidden: Set<String>,
        pinned: List<String>,
        dockOrder: List<String>,
        defaultPriority: List<String>
    ): List<String> {
        val visibleByPkg = collectVisiblePackages(installed, hidden)
        val result = ArrayList<String>(visibleByPkg.size)
        val added = HashSet<String>(visibleByPkg.size)

        appendFromOrder(pinned, visibleByPkg, added, result)
        appendFromOrder(dockOrder, visibleByPkg, added, result)
        appendFromOrder(defaultPriority, visibleByPkg, added, result)
        appendRemainingAlphabetical(visibleByPkg, added, result)
        return result
    }

    /** Доступность сдвига ← / → с соблюдением жёсткой границы между pinned и unpinned. */
    fun computeMoveBounds(
        index: Int,
        visibleSize: Int,
        pinnedCount: Int,
        isPinned: Boolean
    ): MoveBounds {
        if (index !in 0 until visibleSize) return MoveBounds(false, false)
        val canLeft = if (isPinned) index > 0 else index > pinnedCount
        val canRight = if (isPinned) index < pinnedCount - 1 else index < visibleSize - 1
        return MoveBounds(canLeft, canRight)
    }

    /**
     * Немутирующий расчёт нового порядка после сдвига плитки на delta (-1 или +1).
     * Закреплённые двигаются строго внутри зоны [0, pinnedCount), незакреплённые —
     * строго внутри [pinnedCount, visibleSize). Невидимые закреплённые сохраняются в хвосте.
     */
    fun shiftPackageOrder(
        visiblePkgs: List<String>,
        allPinned: List<String>,
        pkg: String,
        delta: Int
    ): ShiftResult? {
        val idx = visiblePkgs.indexOf(pkg)
        if (idx < 0 || delta == 0) return null

        val pinnedSet = allPinned.toSet()
        val pinnedCount = visiblePkgs.count { it in pinnedSet }
        val isPinned = pkg in pinnedSet
        val target = idx + delta

        return if (isPinned) {
            shiftPinned(visiblePkgs, allPinned, idx, target, pinnedCount)
        } else {
            if (idx < pinnedCount || target !in pinnedCount until visiblePkgs.size) return null
            ShiftResult(newDockOrder = moveElement(visiblePkgs, idx, target), newPinnedOrder = null)
        }
    }

    private fun collectVisiblePackages(
        installed: List<Pair<String, String>>,
        hidden: Set<String>
    ): LinkedHashMap<String, String> {
        val visible = LinkedHashMap<String, String>()
        for ((pkg, label) in installed) {
            if (pkg !in hidden && pkg !in visible) {
                visible[pkg] = label
            }
        }
        return visible
    }

    private fun appendFromOrder(
        order: List<String>,
        visibleByPkg: Map<String, String>,
        added: MutableSet<String>,
        out: MutableList<String>
    ) {
        for (pkg in order) {
            if (pkg in visibleByPkg && added.add(pkg)) out.add(pkg)
        }
    }

    private fun appendRemainingAlphabetical(
        visibleByPkg: Map<String, String>,
        added: Set<String>,
        out: MutableList<String>
    ) {
        val rest = visibleByPkg.entries
            .filter { it.key !in added }
            .sortedWith(compareBy<Map.Entry<String, String>> { it.value.lowercase() }.thenBy { it.key })
        for (entry in rest) {
            out.add(entry.key)
        }
    }

    private fun shiftPinned(
        visiblePkgs: List<String>,
        allPinned: List<String>,
        idx: Int,
        target: Int,
        pinnedCount: Int
    ): ShiftResult? {
        if (idx !in 0 until pinnedCount || target !in 0 until pinnedCount) return null
        val visiblePinned = moveElement(visiblePkgs.subList(0, pinnedCount), idx, target)
        val offscreenPinned = allPinned.filter { it !in visiblePinned }
        return ShiftResult(
            newDockOrder = moveElement(visiblePkgs, idx, target),
            newPinnedOrder = visiblePinned + offscreenPinned
        )
    }

    private fun moveElement(source: List<String>, from: Int, to: Int): List<String> {
        val out = source.toMutableList()
        val item = out.removeAt(from)
        out.add(to, item)
        return out
    }
}
