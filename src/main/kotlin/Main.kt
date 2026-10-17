

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
     *  - handle assets with UNKNOWN type
     *
     * Test this hypothesis, if the new asset has almost the same name, e.g. original = blah.MP4, new =
      blah.mp4, then Immich does not show the original on the trash, even if it's marked for deletion
     *
     */
    fun run(vararg args: String?): Int = 0
}
