

class Main {
    /*
     * TODO Not easy to fix:
     *  - The user needs to delete all assets from device, otherwise the Immich app will upload them again
     *  -- Or: turn ON Advanced :: Sync Remote deletions
     *
     * TODO Gradle:
     *  - Compile with GraalVM
     *
     * TODO DB:
     *  - docker volume
     *  - ORRRR: sqlite ?
     *
     * TODO API:
     *  - fetch asset list -> delete one asset -> try to convert it -> ... why ?
     *
     * TODO:
     *  - Can the query sent to Immich already exclude the tagged entries ?
     *  - Ensure only one conversion request is handled at a time: mutex
     *  - handle assets left in between
     *  - When an asset is deemed not "improvable" (the size ends up being larger than original), the UI shows
     *      Removed, not good :-), change it to smth like skipped, and keep the View old button
     *      AND -> keep the ID on the converted_assets table
     *
     * TODO - known bugs:
     *  - Immich not always tags the assets, need to fetch the asset and tag until it sticks
     *  - Verify the new asset is retrievable -> once I saw Immich fail to load the new asset
     *
     * TODO: tests:
     *  - list assets
     *  - fetch asset, deal with 404
     *  - delete asset
     *  - convert asset
     */
    fun run(vararg args: String?): Int = 0
}
