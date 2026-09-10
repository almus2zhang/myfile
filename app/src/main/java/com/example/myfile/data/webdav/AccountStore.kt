package com.example.myfile.data.webdav

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.myfile.model.WebDavAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.accountStore: DataStore<Preferences> by preferencesDataStore(name = "webdav_accounts")

class AccountStore(private val context: Context) {

    private val key = stringPreferencesKey("accounts_json")

    val accounts: Flow<List<WebDavAccount>> = context.accountStore.data.map { p ->
        p[key]?.let { json ->
            try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    WebDavAccount(
                        id = o.getLong("id"),
                        name = o.getString("name"),
                        url = o.getString("url"),
                        username = o.getString("username"),
                        password = o.getString("password"),
                        extraUrls = o.optJSONArray("extraUrls")?.let { ea ->
                            (0 until ea.length()).map { j -> ea.getString(j) }
                        } ?: emptyList(),
                        isDynamic = o.optBoolean("isDynamic", false),
                        resolvedUrl = o.optString("resolvedUrl", ""),
                        renameToVideoExt = o.optBoolean("renameToVideoExt", true),
                        streamFakeAvi = o.optBoolean("streamFakeAvi", false),
                        isEncrypted = o.optBoolean("isEncrypted", false),
                        encryptPassword = o.optString("encryptPassword", ""),
                        rememberLastPath = o.optBoolean("rememberLastPath", true)
                    )
                }
            } catch (e: Exception) { emptyList() }
        } ?: emptyList()
    }

    suspend fun save(accounts: List<WebDavAccount>) {
        context.accountStore.edit { p ->
            val arr = JSONArray()
            accounts.forEach { a ->
                arr.put(JSONObject().apply {
                    put("id", a.id)
                    put("name", a.name)
                    put("url", a.url)
                    put("username", a.username)
                    put("password", a.password)
                    if (a.extraUrls.isNotEmpty()) {
                        put("extraUrls", JSONArray().apply { a.extraUrls.forEach { put(it) } })
                    }
                    put("isDynamic", a.isDynamic)
                    put("resolvedUrl", a.resolvedUrl)
                    put("renameToVideoExt", a.renameToVideoExt)
                    put("streamFakeAvi", a.streamFakeAvi)
                    put("isEncrypted", a.isEncrypted)
                    put("encryptPassword", a.encryptPassword)
                    put("rememberLastPath", a.rememberLastPath)
                })
            }
            p[key] = arr.toString()
        }
    }

    /**
     * 单独更新某动态账户的当前生效连接地址，保持原始 url 配置不被篡改
     */
    suspend fun updateResolvedUrl(accountId: Long, resolvedUrl: String) {
        context.accountStore.edit { p ->
            val json = p[key] ?: return@edit
            try {
                val arr = JSONArray(json)
                val newArr = JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optLong("id") == accountId) {
                        o.put("resolvedUrl", resolvedUrl)
                    }
                    newArr.put(o)
                }
                p[key] = newArr.toString()
            } catch (_: Exception) {}
        }
    }
}
