package com.example.myfile.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

/**
 * 构建高亮搜索词的 AnnotatedString。
 *
 * @param text 待展示的文本（如文件名）
 * @param query 用户输入的搜索词（支持空格分隔多个关键词）
 * @param highlightColor 高亮文本的主题颜色（默认采用 primary 色）
 * @param highlightBg 高亮背景色（默认轻微背景衬托，保证视觉清晰）
 */
@Composable
fun buildHighlightedText(
    text: String,
    query: String?,
    highlightColor: Color = MaterialTheme.colorScheme.primary,
    highlightBg: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
): AnnotatedString {
    if (query.isNullOrBlank()) {
        return AnnotatedString(text)
    }

    val keywords = query.trim().split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .map { it.lowercase() }

    if (keywords.isEmpty()) {
        return AnnotatedString(text)
    }

    // 收集所有匹配到的区间 [start, end)
    val textLower = text.lowercase()
    val matches = mutableListOf<IntRange>()

    for (kw in keywords) {
        var startIdx = 0
        while (startIdx < textLower.length) {
            val idx = textLower.indexOf(kw, startIdx)
            if (idx < 0) break
            matches.add(idx until (idx + kw.length))
            startIdx = idx + 1
        }
    }

    if (matches.isEmpty()) {
        return AnnotatedString(text)
    }

    // 合并重叠或相邻的区间
    matches.sortBy { it.first }
    val merged = mutableListOf<IntRange>()
    for (range in matches) {
        if (merged.isEmpty()) {
            merged.add(range)
        } else {
            val last = merged.last()
            if (range.first <= last.last + 1) {
                // 重叠或相邻，合并
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                merged.add(range)
            }
        }
    }

    // 构建 AnnotatedString
    return buildAnnotatedString {
        var cursor = 0
        val highlightStyle = SpanStyle(
            color = highlightColor,
            background = highlightBg,
            fontWeight = FontWeight.Bold
        )

        for (range in merged) {
            val safeStart = range.first.coerceIn(0, text.length)
            val safeEnd = (range.last + 1).coerceIn(0, text.length)
            if (safeStart > cursor) {
                append(text.substring(cursor, safeStart))
            }
            if (safeEnd > safeStart) {
                pushStyle(highlightStyle)
                append(text.substring(safeStart, safeEnd))
                pop()
            }
            cursor = maxOf(cursor, safeEnd)
        }

        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}
