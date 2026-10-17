

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
     *  - There's smth special about the videos from an iPhone: when one gets replaced, Immich doesn't show them
     *      on the trash page, but, if you go directly to one of them you get the option to delete permanently,
     *      meaning: they are marked as deleted, no idea why they don't show up on the trash then...
     */
    fun run(vararg args: String?): Int = 0
}
