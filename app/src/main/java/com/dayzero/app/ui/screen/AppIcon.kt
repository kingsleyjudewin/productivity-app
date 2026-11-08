package com.dayzero.app.ui.screen

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.dayzero.app.ui.theme.Dz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object AppIcons {
    private val cache = LruCache<String, ImageBitmap>(64)
    fun cached(pkg: String): ImageBitmap? = cache.get(pkg)
    suspend fun load(ctx: Context, pkg: String): ImageBitmap? = cached(pkg) ?: withContext(Dispatchers.IO) {
        runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
            ?.also { cache.put(pkg, it) }
    }
}

@Composable
fun AppIcon(pkg: String, size: Dp = 40.dp) {
    val ctx = LocalContext.current
    val bmp by produceState(AppIcons.cached(pkg), pkg) { value = AppIcons.load(ctx, pkg) }
    val shape = RoundedCornerShape(10.dp)
    val b = bmp
    if (b != null) Image(b, contentDescription = null, modifier = Modifier.size(size).clip(shape))
    else Box(Modifier.size(size).clip(shape).background(Dz.Surface))
}
