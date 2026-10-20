

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
     *  - The asset list has to be ordered, not custom -> IMAGE, VIDEO, UNKNOWN
     *  - handle assets with UNKNOWN type
     *  - livePhotoVideoId is handled correctly now -> write a test asserting it
     *  - The postgres started on the tests has lots of processes, can it be trimmed ?
     *  - Fix the tag feature checking
     */
    fun run(vararg args: String?): Int = 0
}
