package com.example.myfile.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 文件列表/网格视图模式：
 * 1. 详细视图（列表，无图片/视频缩略图，无视频时长，极速省流）
 * 2. 详细视图含缩略图和视频时长（列表，加载远程缩略图及视频时长）
 * 3. 大图标宫格视图（卡片式大网格）
 * 4. 小图标宫格视图（紧凑型小网格）
 * 5. 简洁视图（小图标 + 单行文件名，高密度纯粹）
 */
enum class ViewMode(
    val title: String,
    val icon: ImageVector
) {
    DETAILS_NO_MEDIA("详细视图", Icons.Filled.ViewAgenda),
    DETAILS_WITH_MEDIA("详细视图(含缩略图)", Icons.Filled.ViewList),
    GRID_LARGE("大图标宫格", Icons.Filled.GridView),
    GRID_SMALL("小图标宫格", Icons.Filled.Apps),
    COMPACT("简洁视图", Icons.Filled.ViewHeadline)
}
