package space.linuxct.glyphworks.ui

import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import space.linuxct.glyphworks.BuildConfig
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.matrix.PanelMask
import space.linuxct.glyphworks.ui.theme.NothingRed
import space.linuxct.glyphworks.ui.theme.dialogSurface
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private val ObservatoryBlack = Color(0xFF101115)
private const val CREATOR_URL = "https://github.com/linuxct"
private const val PROJECT_URL = "https://github.com/linuxct/glyphworks"

@Composable
internal fun AboutRow() {
    var open by rememberSaveable { mutableStateOf(false) }
    PrefRow(
        lines = PrefRowLines.TWO,
        leading = { SignatureDots(Modifier.size(24.dp)) },
        trailing = {
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        onClick = { open = true },
    ) {
        Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.about_row_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) AboutDialog { open = false }
}

@Composable
private fun SignatureDots(modifier: Modifier = Modifier, ink: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val pitch = size.minDimension / 3
        for (y in 0..2) for (x in 0..2) {
            val dimmed = (x == 1 && y == 1) || (x == 2 && y == 2)
            drawCircle(if (dimmed) Color.Gray else ink,
                radius = pitch * 0.21f, center = Offset((x + 0.5f) * pitch, (y + 0.5f) * pitch))
        }
    }
}

@Composable
internal fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val title = stringResource(R.string.about_title)
    val maxHeight = (LocalWindowInfo.current.containerDpSize.height - 64.dp).coerceAtLeast(180.dp)
    MotionDialog(onDismiss, fullScreen = true) { dismiss ->
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            // A separate backdrop leaves every control inside the card independently interactive.
            Box(Modifier.matchParentSize().pointerInput(dismiss) { detectTapGestures { dismiss() } })
            Surface(
                modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 440.dp).fillMaxWidth()
                    .heightIn(max = maxHeight).semantics { paneTitle = title }
                    .pointerInput(Unit) { detectTapGestures { } },
                shape = RoundedCornerShape(32.dp),
                color = dialogSurface(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().background(ObservatoryBlack).padding(start = 20.dp, end = 6.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SignatureDots(Modifier.size(20.dp), Color.White)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.about_wordmark), color = Color.White,
                            style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = dismiss) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.about_close), tint = Color.White)
                        }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        ObservatoryHero()
                        CreatorCredit()
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = { openAboutLink(context, CREATOR_URL) }) {
                            Text(stringResource(R.string.about_profile))
                        }
                        Button(onClick = { openAboutLink(context, PROJECT_URL) }) {
                            Icon(Icons.Outlined.Code, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.about_source))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ObservatoryHero() {
    val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val orbit = if (animationsEnabled) {
        rememberInfiniteTransition(label = "aboutOrbit").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "aboutOrbitPhase",
        )
    } else rememberUpdatedState(0.12f)
    val ripples = remember { mutableStateListOf<Animatable<Float, AnimationVector1D>>() }
    val scope = rememberCoroutineScope()
    var touched by rememberSaveable { mutableStateOf(false) }
    val portraitDescription = stringResource(R.string.about_portrait_description)

    Column(Modifier.fillMaxWidth().background(ObservatoryBlack)) {
        Box(Modifier.fillMaxWidth().height(218.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
                // Read animation state in the draw phase, without recomposing the text or portrait.
                val turn = orbit.value * (2 * PI).toFloat()
                val rippleProgress = ripples.map { it.value }
                val radius = min(size.width, size.height) * 0.465f
                drawCircle(brush = Brush.radialGradient(
                    listOf(Color(0xFF535C7D).copy(alpha = 0.32f), Color.Transparent), center, radius * 1.1f),
                    radius = radius * 1.1f)
                drawCircle(Color.White.copy(alpha = 0.1f), radius * 0.75f, style = Stroke(0.7.dp.toPx()))
                drawCircle(Color.White.copy(alpha = 0.12f), radius, style = Stroke(0.7.dp.toPx()))
                val pitch = radius * 2 / 25
                for (y in 0 until 25) for (x in 0 until 25) {
                    if (!PanelMask.contains(x, y, 25)) continue
                    val dx = (x - 12) * pitch
                    val dy = (y - 12) * pitch
                    val distance = sqrt(dx * dx + dy * dy) / radius
                    val sweep = ((cos(atan2(dy, dx) - turn) + 1) / 2).pow(16)
                    var wave = 0f
                    for (progress in rippleProgress) {
                        wave += exp(-((distance - progress * 1.5f) / 0.14f).pow(2)) * (1 - progress) * 0.85f
                    }
                    val alpha = (0.12f + sweep * 0.38f + wave).coerceIn(0f, 1f)
                    drawCircle(Color.White.copy(alpha = alpha), radius = pitch * 0.16f,
                        center = center + Offset(dx, dy))
                }
                val beacon = center + Offset(cos(turn) * radius, sin(turn) * radius)
                drawCircle(NothingRed.copy(alpha = 0.12f), 9.dp.toPx(), beacon)
                drawCircle(NothingRed, 3.dp.toPx(), beacon)
                val satellite = center + Offset(cos(-turn + 2) * radius * 0.75f, sin(-turn + 2) * radius * 0.75f)
                drawCircle(Color.White.copy(alpha = 0.8f), 2.dp.toPx(), satellite)
            }
            Surface(
                modifier = Modifier.size(112.dp).semantics { contentDescription = portraitDescription }
                    .clip(CircleShape).clickable(role = Role.Button,
                        onClickLabel = stringResource(R.string.about_portrait_action)) {
                        touched = true
                        if (animationsEnabled) {
                            // Each tap owns its animation; a new ripple never cancels an older one.
                            val ripple = Animatable(0f)
                            ripples.add(ripple)
                            scope.launch {
                                try {
                                    ripple.animateTo(1f, tween(1_500, easing = LinearEasing))
                                } finally {
                                    ripples.remove(ripple)
                                }
                            }
                        }
                    },
                shape = CircleShape,
                border = BorderStroke(2.dp, Color.White.copy(alpha = 0.85f)),
                shadowElevation = 12.dp,
            ) {
                // Bundled from the creator's public GitHub avatar; About never needs a network request.
                Image(painterResource(R.drawable.about_linuxct), contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(stringResource(R.string.about_headline), color = Color.White,
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp, lineHeight = 33.sp),
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(10.dp))
            AnimatedContent(touched, label = "aboutLightThanks") { sent ->
                Text(stringResource(if (sent) R.string.about_touch_thanks else R.string.about_touch_hint),
                    color = Color.White.copy(alpha = 0.64f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CreatorCredit() {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.about_created_by), style = MaterialTheme.typography.labelSmall,
                color = muted, letterSpacing = 1.6.sp, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = muted)
        }
        Spacer(Modifier.height(5.dp))
        Text(stringResource(R.string.about_creator), style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.about_bio), style = MaterialTheme.typography.bodyMedium, color = muted)
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.about_collaboration), style = MaterialTheme.typography.labelSmall,
            color = muted, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.about_claude), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text("  +  ", style = MaterialTheme.typography.titleMedium, color = NothingRed)
            Text(stringResource(R.string.about_codex), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.about_open_source), style = MaterialTheme.typography.bodySmall, color = muted)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.about_license), style = MaterialTheme.typography.labelSmall,
            color = muted, fontFamily = FontFamily.Monospace)
    }
}

private fun openAboutLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.about_link_unavailable, Toast.LENGTH_SHORT).show()
    }
}
