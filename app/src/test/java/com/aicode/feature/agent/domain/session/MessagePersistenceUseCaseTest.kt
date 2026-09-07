package com.aicode.feature.agent.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证落库前的内容净化：剥离内嵌 base64 图片 data URL、超长内容截断，
 * 防止超大 content 触发 SQLite CursorWindow 崩溃（SQLiteBlobTooBigException）。
 */
class MessagePersistenceUseCaseTest {

    @Test
    fun sanitizeContent_stripsInlineBase64Image() {
        val input = "这是回复。看图：![截图](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==) 结束。"

        val result = MessagePersistenceUseCase.sanitizeContent(input)

        assertTrue(result.contains("图片已省略"))
        assertFalse(result.contains("iVBORw0KGgo"))
        assertTrue(result.contains("这是回复"))
    }

    @Test
    fun sanitizeContent_stripsBareDataUrl() {
        val input = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQ=="

        val result = MessagePersistenceUseCase.sanitizeContent(input)

        assertTrue(result.contains("图片已省略"))
        assertFalse(result.contains("/9j/4AAQSk"))
    }

    @Test
    fun sanitizeContent_truncatesOversizedPlainText() {
        val oversized = "a".repeat(300_000)

        val result = MessagePersistenceUseCase.sanitizeContent(oversized)

        assertTrue(result.length <= MessagePersistenceUseCase.MAX_CONTENT_CHARS + 64)
        assertTrue(result.endsWith(MessagePersistenceUseCase.CONTENT_TRUNCATED_MARKER))
    }

    @Test
    fun sanitizeContent_keepsNormalTextUnchanged() {
        val normal = "普通消息，不需要处理。".repeat(10)

        assertEquals(normal, MessagePersistenceUseCase.sanitizeContent(normal))
    }

    @Test
    fun capLargeField_truncatesOversizedJson() {
        val oversized = "[{\"id\":\"" + "a".repeat(300_000) + "\"}]"

        val result = MessagePersistenceUseCase.capLargeField(oversized)

        assertTrue(result.length <= MessagePersistenceUseCase.MAX_CONTENT_CHARS)
    }

    @Test
    fun capLargeField_keepsNormalJsonUnchanged() {
        val normal = "[{\"id\":\"call_1\",\"name\":\"writeFile\"}]"

        assertEquals(normal, MessagePersistenceUseCase.capLargeField(normal))
    }
}
