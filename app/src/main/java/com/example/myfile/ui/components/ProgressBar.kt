package com.example.myfile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.myfile.model.ChunkStatusUi
import com.example.myfile.model.ChunkUi
import com.example.myfile.ui.theme.ChunkCompleted
import com.example.myfile.ui.theme.ChunkDownloading
import com.example.myfile.ui.theme.ChunkFailed
import com.example.myfile.ui.theme.ChunkPending

@Composable
fun ChunkProgressGrid(chunks: List<ChunkUi>, modifier: Modifier = Modifier) {
    if (chunks.isEmpty()) return
    val columns = 16
    val rows = (chunks.size + columns - 1) / columns
    Column(modifier = modifier.fillMaxWidth()) {
        for (r in 0 until rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (c in 0 until columns) {
                    val idx = r * columns + c
                    if (idx < chunks.size) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(8.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(colorFor(chunks[idx].status))
                        )
                    } else {
                        Box(modifier = Modifier.weight(1f).height(8.dp))
                    }
                }
            }
            if (r < rows - 1) Spacer(modifier = Modifier.height(2.dp))
        }
    }
}

private fun colorFor(status: ChunkStatusUi): Color = when (status) {
    ChunkStatusUi.PENDING -> ChunkPending
    ChunkStatusUi.DOWNLOADING -> ChunkDownloading
    ChunkStatusUi.COMPLETED -> ChunkCompleted
    ChunkStatusUi.FAILED -> ChunkFailed
    ChunkStatusUi.PAUSED -> ChunkPending
}

@Composable
fun ProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
    }
}
