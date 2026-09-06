package com.aicode.feature.settings.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 内置官方 provider 的展示预设：来自 api.official.json 的 provider 节点（name/type/baseUrl + models 键列表）。 */
@Serializable
data class ProviderPreset(
    val id: String,
    val name: String,
    /** 协议类型：OPENAI / ANTHROPIC / GEMINI。 */
    val type: String,
    /** Base URL，选中后自动填充，可在编辑页修改。 */
    val baseUrl: String,
    /** 关联的模型 id 列表（选中后导入为可用模型）。 */
    val models: List<String> = emptyList()
)

/**
 * 内置官方 provider 预设库：解析 [OFFICIAL_ASSET_FILE_NAME]（api.official.json）的 provider 节点，
 * 得到 name/type/baseUrl 与模型 id 列表。不落库、不覆盖用户自建数据。
 */
object ProviderPresetLibrary {
    const val OFFICIAL_ASSET_FILE_NAME = "api.official.json"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: List<ProviderPreset>? = null

    /** 读取内置官方 provider 预设列表（按 provider id 排序）。 */
    suspend fun loadOfficial(context: Context): List<ProviderPreset> = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        val text = context.assets.open(OFFICIAL_ASSET_FILE_NAME).bufferedReader().use { it.readText() }
        val root = json.parseToJsonElement(text).jsonObject
        val result = root.mapNotNull { (pid, el) ->
            val node = el.jsonObject
            val name = node["name"]?.jsonPrimitive?.content ?: pid
            val type = node["type"]?.jsonPrimitive?.content ?: "OPENAI"
            val baseUrl = node["baseUrl"]?.jsonPrimitive?.content ?: ""
            val modelIds = node["models"]?.jsonObject?.keys.orEmpty().sorted()
            ProviderPreset(
                id = pid,
                name = name,
                type = type,
                baseUrl = baseUrl,
                models = modelIds
            )
        }.sortedBy { it.name.lowercase() }
        cached = result
        result
    }
}