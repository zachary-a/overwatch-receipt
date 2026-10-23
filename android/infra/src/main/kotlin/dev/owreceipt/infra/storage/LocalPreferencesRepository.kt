package dev.owreceipt.infra.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dev.owreceipt.core.*
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.receiptPreferences by preferencesDataStore(name = "receipt_preferences")
class LocalPreferencesRepository(context: Context) : PreferencesRepository {
    private val store = context.applicationContext.receiptPreferences
    private val font = stringPreferencesKey("font")
    private val theme = stringPreferencesKey("theme")
    private val scenario = stringPreferencesKey("scenario")
    private val period = stringPreferencesKey("period")
    override val preferences = store.data.catch {
        if (it is IOException) emit(emptyPreferences()) else throw it
    }.map {
        UserPreferences(
            enumValue(it[font], ReceiptFont.MEDIUM).forNewReceipt(), enumValue(it[theme], AppTheme.SYSTEM),
            enumValue(it[scenario], DemoScenario.NORMAL), enumValue(it[period], Period.CAREER),
        )
    }
    override suspend fun update(preferences: UserPreferences) {
        store.edit {
            it[font] = preferences.font.name
            it[theme] = preferences.theme.name
            it[scenario] = preferences.scenario.name
            it[period] = preferences.period.name
        }
    }
    private inline fun <reified T : Enum<T>> enumValue(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback
}
