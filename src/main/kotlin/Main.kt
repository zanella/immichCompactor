

class Main {
    /*
     * TODO Not easy to fix:
     *  - The user needs to delete all assets from device, otherwise the Immich app will upload them again
     *  -- Or: turn ON Advanced :: Sync Remote deletions
     *
     * TODO DB:
     *  - docker volume
     *  - ORRRR: sqlite ?
     *
     * TODO:
     *  - Ensure only one conversion request is handled at a time: mutex
     *  - handle assets left in between
     *
     */
    fun run(vararg args: String?): Int = 0
}
