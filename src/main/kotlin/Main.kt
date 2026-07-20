import integration.SearchAssetsRequest
import io.quarkus.runtime.QuarkusApplication
import io.quarkus.runtime.annotations.QuarkusMain
import jakarta.inject.Inject
import services.ImmichService

data class Config (
    val dbUserName: String,
    val dbPassword: String,
    val dbHost: String,
    val dbPort: Int,
    val dbOwnDbName: String,

    val immichServerUrl: String,
)

// TODO: entity
data class UserInfo (
    // val id: Int,
    val name: String,
    val apiKey: String,
)

@QuarkusMain
class Main : QuarkusApplication {

    @Inject
    lateinit var immichService: ImmichService

    private val defaultConfig = Config(
        dbUserName = "postgres",
        dbPassword = "q1w2e3",
        dbHost = "localhost",
        dbPort = 5445,
        dbOwnDbName = "immich_compactor",

        immichServerUrl = "http://localhost:2283",
    )

    private val defaultUserInfo = UserInfo (
        name = "dev_sandbox",
        apiKey = "4kIF0A1ObDCEf5Znluug6nWXcsncXlPkk1jGH0nRF8"
    )

    override fun run(vararg args: String?): Int {
        // If this goes through, enough to see the server is up and we can auth

        /* require(immichService.client.authValidateToken(defaultUserInfo.apiKey).authStatus) {
            "The API key is invalid."
        } */

        immichService.findRecentAssets(defaultUserInfo.apiKey).also {
            println(it)
        }

        return 0
    }
}