# Immich Compactor

This application is a WIP, it works, but there's no automated "workflow" (yet?)

Immich is very serious about keeping the original it receives, so this application does not try to circumvent that.

What it does: 

1) Catalogs all assets which haven't been processed by it.
2) Download an asset, check if it can be "compacted" (e.g. a PNG image converted into JPEG XL)
3) If it can, then upload the converted asset, and delete the old one
- The trick here being: Copy the metadata from the old one to the new.
4) Go back to step 2 until there are no more unchecked assets

# User-required setup

You'll need to do 3 steps:

1) On the Immich server you'll need to enable tags:
- Account Settings -> Features -> Tags

2) On the mobile application you'll need to enable Remote deletions:
- Advanced -> Sync Remote deletions (Otherwise the app will keep sending the assets, since they'll be deleted)

3) Create an API key on the Immich server:
- Account Settings -> API Keys

# How to run it

TODO: docker instructions

1) http://localhost:8080

# Setup development environment

Set up the linter git hook, run this ONCE:

`./gradlew addKtlintFormatGitPreCommitHook`

# How to build

- Run build.sh
- `docker run --rm -p 8080:8080 -v immich-compactor-data:/data immich-compactor`

# Build native

./gradlew build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true -Dquarkus.package.jar.enabled=false

# Future plans

- Rewrite it in Rust
- Have a <proper | better> UI