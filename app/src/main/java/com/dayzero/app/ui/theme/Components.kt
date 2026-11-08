package com.dayzero.app.ui.theme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val DzCorner = RoundedCornerShape(20.dp)

/** Primary black button with a pressed-in 3D edge. [secondary] renders the soft grey variant. */
@Composable
fun DzButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secondary: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val depth = 4.dp
    val face = 54.dp
    val dy by animateDpAsState(if (pressed) depth else 0.dp, tween(80), label = "press")
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.fillMaxWidth().height(face + depth).alpha(if (enabled) 1f else 0.4f)) {
        Box(
            Modifier.fillMaxWidth().height(face).offset(y = depth).clip(shape)
                .background(if (secondary) Dz.Divider else Dz.Edge),
        )
        Box(
            Modifier.fillMaxWidth().height(face).offset(y = dy).clip(shape)
                .background(if (secondary) Dz.Surface else Dz.Black)
                .clickable(
                    interactionSource = interaction, indication = null,
                    enabled = enabled, role = Role.Button, onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text, style = MaterialTheme.typography.labelLarge,
                color = if (secondary) Dz.Black else Dz.White,
            )
        }
    }
}

/** Small tracked uppercase caption used above every value. */
@Composable
fun DzLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelSmall, color = Dz.Muted)
}

/** Soft grey tile showing one label and one big value. */
@Composable
fun DzStat(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null) {
    Column(
        modifier.clip(DzCorner).background(Dz.Surface).padding(16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        DzLabel(label)
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp))
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = Dz.Muted)
    }
}

/** Circular completion indicator. [progress] is clamped to 0..1. */
@Composable
fun DzProgressRing(progress: Float, size: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(600), label = "ring")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(Dz.Surface, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            drawArc(Dz.Black, -90f, 360f * p, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        content()
    }
}

/** Compact selectable pill used for priority, status and filters. */
@Composable
fun DzPill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) Dz.Black else Dz.Surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(), style = MaterialTheme.typography.labelSmall,
            color = if (selected) Dz.White else Dz.Muted,
        )
    }
}
