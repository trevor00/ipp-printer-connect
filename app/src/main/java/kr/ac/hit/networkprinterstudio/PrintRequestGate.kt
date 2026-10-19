package kr.ac.hit.networkprinterstudio

/** Reserves one submission across name entry, rendering, authentication and network transfer. */
class PrintRequestGate {
    private var nextId = 0L
    private var activeId: Long? = null

    @Synchronized fun tryStart(): Long? {
        if (activeId != null) return null
        nextId += 1
        activeId = nextId
        return nextId
    }

    @Synchronized fun isBusy(): Boolean = activeId != null

    @Synchronized fun isActive(id: Long): Boolean = activeId == id

    @Synchronized fun finish(id: Long): Boolean {
        if (activeId != id) return false
        activeId = null
        return true
    }

    @Synchronized fun clear() { activeId = null }
}
