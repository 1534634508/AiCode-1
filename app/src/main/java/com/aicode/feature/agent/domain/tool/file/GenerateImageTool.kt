package com.aicode.feature.agent.domain.tool.file

import android.util.Base64
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.remote.openai.ImageGenerationRequest
import com.aicode.feature.agent.data.remote.openai.ImageGenerationResponse
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.provider.enrichWithHttpErrorBody
import com.aicode.feature.agent.domain.provider.joinUrl
import com.aicode.feature.agent.domain.provider.resolveCustomHeaders
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.settings.data.repository.ImageGenModelSettingsRepository
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 生图工具：调用 OpenAI Images API（POST /v1/images/generations）按提示词生成位图。
 *
 * 使用「设置 → 默认模型 → 生图模型」配置的 provider + 模型（如 gpt-image-1 / dall-e-3）；
 * 未配置时返回带设置指引的错误。生成的图片：
 * - 始终以 [AgentImage] 返回给工作流，聊天区可直接渲染；
 * - 传入 `output_path` 时同时落盘到工作区（多张时自动加序号后缀）。
 *
 * 请求体兼容 dall-e 系（显式 `response_format=b64_json`）与 GPT image 系
 * （不支持该参数、总是返回 base64）；调用失败时把错误信息原样作为工具结果返回。
 */
class GenerateImageTool @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val imageGenModelSettingsRepository: ImageGenModelSettingsRepository,
    private val aiProviderRepository: AIProviderRepository,
    private val openAIApi: OpenAIApi,
    private val httpClient: OkHttpClient
) : AbstractContextualTool() {

    override val name = "generateImage"
    override val description = "根据文本描述生成图片并在聊天区展示给用户。支持指定尺寸与张数，可选通过 output_path 同时保存为工作区文件。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.NETWORK_WRITE, ToolCapability.WRITE_WORKSPACE)
    override val parameters = mapOf(
        "prompt" to ToolParameter(
            name = "prompt",
            type = ParameterType.STRING,
            description = "图片内容的详细描述（主体、风格、构图、色调、氛围等）。",
            required = true
        ),
        "size" to ToolParameter(
            name = "size",
            type = ParameterType.STRING,
            description = "图片尺寸，如 1024x1024（默认）、1024x1536、1536x1024。",
            required = false
        ),
        "n" to ToolParameter(
            name = "n",
            type = ParameterType.INTEGER,
            description = "生成张数，默认 1，最多 4。dall-e-3 只支持 1 张。",
            required = false
        ),
        "quality" to ToolParameter(
            name = "quality",
            type = ParameterType.STRING,
            description = "画质：GPT Image 系列支持 low / medium / high / auto（默认 auto）；dall-e-3 支持 standard / hd（默认 standard）。",
            required = false
        ),
        "background" to ToolParameter(
            name = "background",
            type = ParameterType.STRING,
            description = "背景：transparent / opaque / auto（默认 auto），仅 GPT Image 系列模型支持；transparent 需配合 png 或 webp 输出格式。",
            required = false
        ),
        "moderation" to ToolParameter(
            name = "moderation",
            type = ParameterType.STRING,
            description = "内容审核级别：low / auto（默认 auto），仅 GPT Image 系列模型支持。",
            required = false
        ),
        "style" to ToolParameter(
            name = "style",
            type = ParameterType.STRING,
            description = "风格：vivid / natural，仅 dall-e-3 支持。",
            required = false
        ),
        "output_format" to ToolParameter(
            name = "output_format",
            type = ParameterType.STRING,
            description = "输出格式：png / jpeg / webp（默认 png），仅 GPT Image 系列模型支持。",
            required = false
        ),
        "output_path" to ToolParameter(
            name = "output_path",
            type = ParameterType.STRING,
            description = "保存路径（可选）。不传默认保存到 ~/.aicode/generated-images/；传了则保存到指定路径，如 ~/workspace/assets/image.png。",
            required = false
        )
    )

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val outputPath = args["output_path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val count = args["n"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认生成图片",
            summary = "AI 请求调用生图模型生成图片",
            details = if (outputPath.isBlank()) {
                "提示词：$prompt\n数量：$count\n（仅展示，不保存文件）"
            } else {
                "提示词：$prompt\n数量：$count\n保存到：$outputPath"
            },
            argsPreview = argsPreview
        )
    }

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (prompt.isEmpty()) {
            return ToolResult.Error("缺少 prompt 参数：请描述想生成的图片内容。", "MISSING_PROMPT")
        }
        val size = args["size"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
            ?: DEFAULT_SIZE
        val n = (args["n"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1).coerceIn(1, MAX_IMAGES)
        val quality = args["quality"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
        val background = args["background"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
        val moderation = args["moderation"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
        val style = args["style"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
        val outputFormat = args["output_format"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
        val outputPath = args["output_path"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }

        return try {
            val provider = resolveImageGenProvider()
            val model = provider.effectiveModel
            val isGptImage = model.startsWith("gpt-image", ignoreCase = true)
            val isDalle2 = model.equals("dall-e-2", ignoreCase = true)
            val isDalle3 = model.equals("dall-e-3", ignoreCase = true)
            validateImageParams(model, isGptImage, isDalle2, isDalle3, n, quality, background, moderation, style, outputFormat)?.let {
                return ToolResult.Error(it, "INVALID_PARAMS")
            }
            FileLogger.i(TAG, "generateImage provider=${provider.id} model=$model prompt=$prompt n=$n size=$size")

            val url = if (provider.useFullUrl) provider.baseUrl else joinUrl(provider.baseUrl, "v1/images/generations")
            // 兼容性规则：
            // 1. 只有真正的官方 GPT Image 系列模型（如 gpt-image-1 / gpt-image-1.5）才支持 output_format，且其默认就返回 base64、不支持 response_format；
            // 2. 其它模型（DALL-E、各类聚合网关如 agnes/OneAPI/NewAPI/Flux/SD 等）必须传 response_format=b64_json 才会返回 base64，
            //    且绝对不能传 output_format（带上会直接被网关报 HTTP 400：output_format is not supported）。
            val request = ImageGenerationRequest(
                model = model,
                prompt = prompt,
                n = n,
                size = size,
                quality = quality,
                response_format = if (!isGptImage) "b64_json" else null,
                output_format = if (isGptImage) outputFormat else null,
                background = if (isGptImage) background else null,
                moderation = if (isGptImage) moderation else null,
                style = if (isDalle3) style else null
            )

            val response = openAIApi.createImage(
                url = url,
                authorization = "Bearer ${provider.firstUsableApiKey}",
                extraHeaders = resolveCustomHeaders(provider.customHeaders, context.sessionId, provider.firstUsableApiKey),
                request = request
            )
            buildSuccess(response, n, outputPath, model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            FileLogger.e(TAG, "generateImage 失败", enriched)
            ToolResult.Error(enriched.message ?: "生图调用失败", "IMAGE_GEN_FAILED")
        }
    }

    private suspend fun resolveImageGenProvider(): com.aicode.feature.settings.domain.model.AIProviderConfig {
        val providerId = imageGenModelSettingsRepository.getImageGenProviderId().trim()
        val model = imageGenModelSettingsRepository.getImageGenModel().trim()
        if (providerId.isEmpty() || model.isEmpty()) {
            throw IllegalStateException("未配置生图模型：请到「设置 → 默认模型 → 生图模型」中选择支持图像输出的模型（如 gpt-image-1 / dall-e-3）。")
        }
        val config = aiProviderRepository.getProviderById(providerId)
            ?: throw IllegalStateException("生图模型配置的提供商不存在或已被删除，请到「设置 → 默认模型 → 生图模型」重新选择。")
        if (!config.isEnabled) throw IllegalStateException("生图模型配置的提供商「${config.name}」未启用。")
        if (!config.hasUsableApiKey) throw IllegalStateException("生图模型配置的提供商「${config.name}」未填写 API Key。")
        return config.copy(selectedModel = model)
    }

    private suspend fun buildSuccess(
        response: ImageGenerationResponse,
        requestedN: Int,
        outputPath: String?,
        model: String
    ): ToolResult {
        if (response.data.isEmpty()) {
            return ToolResult.Error("生图服务未返回任何图片数据", "EMPTY_RESULT")
        }
        val isDefaultDir = outputPath == null
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val effectiveBasePath = outputPath ?: "$DEFAULT_OUTPUT_DIR/gen_$timestamp"

        val agentImages = mutableListOf<AgentImage>()
        val savedDisplayPaths = mutableListOf<String>()
        val filesList = mutableListOf<JsonObject>()
        var itemIndex = 0
        for (item in response.data) {
            val base64 = if (!item.b64_json.isNullOrBlank()) {
                item.b64_json
            } else if (!item.url.isNullOrBlank()) {
                // 服务端只返回了 URL 时，自动下载并转为 base64，彻底保证在 UI 中渲染和落盘
                try {
                    downloadImageAsBase64(item.url)
                } catch (e: Exception) {
                    FileLogger.w(TAG, "从 URL 下载生图结果失败: ${item.url}", e)
                    null
                }
            } else {
                null
            } ?: continue

            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val format = detectImageFormat(bytes)
            val targetPath = buildTargetPath(effectiveBasePath, itemIndex, format)
            val realMime = mimeForFormat(format)
            fileAccess.writeBytes(targetPath, bytes, overwrite = true)
            val displayPath = fileAccess.toDisplayPath(targetPath)
            val localFile = fileAccess.copyToLocal(targetPath)
            val fileName = targetPath.substringAfterLast('/')

            agentImages.add(
                AgentImage(
                    mimeType = realMime,
                    base64Data = base64,
                    path = displayPath
                )
            )
            savedDisplayPaths.add(displayPath)

            filesList.add(
                JsonObject(
                    mapOf(
                        "path" to JsonPrimitive(displayPath),
                        "local_path" to JsonPrimitive(localFile.absolutePath),
                        "name" to JsonPrimitive(fileName),
                        "mime_type" to JsonPrimitive(realMime),
                        "size_bytes" to JsonPrimitive(bytes.size.toLong()),
                        "is_image" to JsonPrimitive(true)
                    )
                )
            )
            itemIndex++
        }

        if (agentImages.isEmpty()) {
            return ToolResult.Error("未能获取到生成的图片内容（base64 与 URL 均无效）", "EMPTY_RESULT")
        }

        val content = buildString {
            append("已生成 ${agentImages.size} 张图片")
            if (isDefaultDir) {
                append("，已保存至 $DEFAULT_OUTPUT_DIR/：")
            } else {
                append("，已保存至指定路径：")
            }
            savedDisplayPaths.forEach { p -> append("\n- ").append(p) }
        }
        return ToolResult.Success(
            data = JsonObject(
                mapOf(
                    "status" to JsonPrimitive("generated"),
                    "content" to JsonPrimitive(content),
                    "image_count" to JsonPrimitive(agentImages.size),
                    "model" to JsonPrimitive(model),
                    "requested_count" to JsonPrimitive(requestedN),
                    "usage" to (response.usage?.let { u ->
                        JsonObject(
                            mapOf(
                                "input_tokens" to JsonPrimitive(u.input_tokens),
                                "output_tokens" to JsonPrimitive(u.output_tokens),
                                "total_tokens" to JsonPrimitive(u.total_tokens)
                            )
                        )
                    } ?: JsonNull),
                    "files" to JsonArray(filesList)
                )
            ),
            images = agentImages
        )
    }

    private suspend fun downloadImageAsBase64(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载图片失败 HTTP ${resp.code}: $url")
            val body = resp.body ?: throw IOException("图片响应体为空: $url")
            val bytes = body.bytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }

    /** 输出路径派生：去掉调用方后缀、按真实格式落盘，多张在文件名后加 _1/_2 序号。 */
    private fun buildTargetPath(basePath: String, index: Int, format: String): String {
        val dot = basePath.lastIndexOf('.')
        val base = if (dot > 0) basePath.substring(0, dot) else basePath
        val suffix = if (index == 0) "" else "_${index + 1}"
        return "$base$suffix.${extForFormat(format)}"
    }

    /** 从字节流 magic number 判定真实图片格式（网关可能忽略 output_format，直接看内容最可靠）。 */
    private fun detectImageFormat(bytes: ByteArray): String {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        ) return FORMAT_PNG
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        ) return FORMAT_JPEG
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) return FORMAT_WEBP
        return FORMAT_PNG
    }

    private fun mimeForFormat(format: String): String = when (format) {
        FORMAT_JPEG -> "image/jpeg"
        FORMAT_WEBP -> "image/webp"
        else -> "image/png"
    }

    private fun extForFormat(format: String): String = if (format == FORMAT_JPEG) "jpg" else format

    /** 按官方模型参数约束做前置校验，避免参数组合不匹配直接撞网关 HTTP 400。错误信息带模型名，方便定位。 */
    private fun validateImageParams(
        model: String,
        isGptImage: Boolean,
        isDalle2: Boolean,
        isDalle3: Boolean,
        n: Int,
        quality: String?,
        background: String?,
        moderation: String?,
        style: String?,
        outputFormat: String?
    ): String? {
        if (!isGptImage && background != null) return "background 参数仅 GPT Image 系列模型支持，当前模型 $model 不支持。"
        if (!isGptImage && moderation != null) return "moderation 参数仅 GPT Image 系列模型支持，当前模型 $model 不支持。"
        if (!isGptImage && outputFormat != null) return "output_format 参数仅 GPT Image 系列模型支持，当前模型 $model 不支持。"
        if (!isDalle3 && style != null) return "style 参数仅 dall-e-3 支持，当前模型 $model 不支持。"
        if (isDalle3 && n > 1) return "dall-e-3 一次只能生成 1 张（n=1），需要多张请改用 GPT Image 系列模型。"
        if (quality != null) {
            val allowed = when {
                isGptImage -> QUALITY_GPT_IMAGE
                isDalle3 -> QUALITY_DALLE3
                isDalle2 -> QUALITY_DALLE2
                else -> return "quality 参数仅 OpenAI 官方生图模型（gpt-image 系列 / dall-e-2 / dall-e-3）支持，当前模型 $model 不支持。"
            }
            if (quality !in allowed) return "quality 取值 $quality 当前模型 $model 不支持，可选：${allowed.joinToString(" / ")}。"
        }
        return null
    }

    private companion object {
        const val TAG = "GenerateImageTool"
        const val DEFAULT_OUTPUT_DIR = "~/.aicode/generated-images"
        const val DEFAULT_SIZE = "1024x1024"
        const val MAX_IMAGES = 4
        const val FORMAT_PNG = "png"
        const val FORMAT_JPEG = "jpeg"
        const val FORMAT_WEBP = "webp"
        val QUALITY_GPT_IMAGE = setOf("low", "medium", "high", "auto")
        val QUALITY_DALLE3 = setOf("standard", "hd")
        val QUALITY_DALLE2 = setOf("standard")
    }
}