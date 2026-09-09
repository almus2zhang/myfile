package com.example.myfile.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 文件列表/网格视图模式：
 * 1. 详细视图 (DETAILS)
 * 2. 大图标宫格 (GRID_LARGE)
 * 3. 小图标宫格 (GRID_SMALL)
 * 4. 简洁视图 (COMPACT)
 */
enum class ViewMode(
    val title: String,
    val icon: ImageVector
) {
    DETAILS("详细视图", Icons.Filled.ViewList),
    GRID_LARGE("大图标宫格", Icons.Filled.GridView),
    GRID_SMALL("小图标宫格", Icons.Filled.Apps),
    COMPACT("简洁视图", Icons.Filled.ViewHeadline)
}
