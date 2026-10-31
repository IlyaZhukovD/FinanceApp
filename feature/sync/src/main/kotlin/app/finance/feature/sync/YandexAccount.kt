package app.finance.feature.sync

import app.finance.core.network.SecretStore
import app.finance.core.network.YandexAuth
import app.finance.core.network.YandexToken
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Аккаунт Яндекса этого компьютера: Client ID приложения и токен.
 *
 * Client ID не секрет и лежит в `yandex.properties` каталога данных; токен — только в
 * [SecretStore] (SPEC.md F7.10). Токен кэшируется в памяти: на Windows чтение секрета —
 * вызов PowerShell, а транспорт спрашивает токен на каждый запрос.
 */
class YandexAccount(
    private val directory: Path,
    private val secrets: SecretStore,
) {
    private val file: Path get() = directory.resolve(FILE_NAME)

    @Volatile
    private var cached: YandexToken? = null

    @Volatile
    private var loaded = false

    fun clientId(): String = runCatching {
        if (!Files.exists(file)) return ""
        val props = Properties()
        Files.newBufferedReader(file).use(props::load)
        props.getProperty(KEY_CLIENT_ID).orEmpty().trim()
    }.getOrDefault("")

    fun saveClientId(value: String) {
        Files.createDirectories(directory)
        val props = Properties()
        props.setProperty(KEY_CLIENT_ID, value.trim())
        Files.newBufferedWriter(file).use { props.store(it, "Finance: приложение Яндекс OAuth для синхронизации") }
    }

    /** Токен; первое обращение читает хранилище секретов, поэтому зовётся не из UI-потока. */
    fun token(): YandexToken? {
        if (!loaded) {
            cached = auth().currentToken()
            loaded = true
        }
        return cached
    }

    fun accessToken(): String? = token()?.accessToken

    suspend fun signInWithBrowser(openBrowser: (String) -> Unit): YandexToken =
        auth().signInWithBrowser(openBrowser).also(::remember)

    fun startWithCode(): YandexAuth.Pending = auth().startWithCode()

    suspend fun finishWithCode(pending: YandexAuth.Pending, code: String): YandexToken =
        auth().finishWithCode(pending, code).also(::remember)

    fun signOut() {
        auth().signOut()
        cached = null
        loaded = true
    }

    private fun remember(token: YandexToken) {
        cached = token
        loaded = true
    }

    private fun auth() = YandexAuth(clientId(), secrets)

    private companion object {
        const val FILE_NAME = "yandex.properties"
        const val KEY_CLIENT_ID = "clientId"
    }
}
