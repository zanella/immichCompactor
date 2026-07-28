
# Setup development environment

Set up the linter git hook, run this ONCE:

`./gradlew addKtlintFormatGitPreCommitHook`

# Build native

./gradlew build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true -Dquarkus.package.jar.enabled=false
