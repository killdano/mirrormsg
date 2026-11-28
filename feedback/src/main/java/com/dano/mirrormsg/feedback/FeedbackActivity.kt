package com.dano.mirrormsg.feedback

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrorapps.kit.KitColors
import com.mirrorapps.kit.LogsMode
import com.mirrorapps.kit.feedback.FeedbackPane

/**
 * The Feedback screen, the last tab of the app's home screen: MirrorMsg's
 * header and the home tabs (Feedback selected), the owner code and "Logs in
 * bug reports" settings, then the kit's Feedback chat. A tap on another tab
 * closes it, and the home screen shows that tab. Test builds only.
 */
class FeedbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated on its own after the process died: the app sets the kit
        // up when it opens this screen, so go back to the app.
        if (!MsgFeedback.isInitialized) {
            finish()
            return
        }
        // A tab of the home screen: no slide in or out (below Android 14,
        // MsgFeedback.open and showTab turn the slide off).
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        enableEdgeToEdge()
        val tabs = MsgFeedback.tabs(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) {
                darkColorScheme(primary = MsgTheme.accent)
            } else {
                lightColorScheme(primary = MsgTheme.accent)
            }
            MaterialTheme(colorScheme = scheme) {
                FeedbackScreen(tabs, ::showTab)
            }
        }
    }

    /** A home tab tapped here: close, and the home screen shows it. */
    private fun showTab(position: Int) {
        if (isFinishing) return
        setResult(RESULT_OK, MsgFeedback.tabResult(position))
        finish()
        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}

@Composable
private fun FeedbackScreen(tabs: List<String>, onTab: (Int) -> Unit) {
    val c = MsgTheme.colors()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        HomeHeader(c)
        HomeTabs(c, tabs, onTab)
        SettingsCard(c)
        Spacer(modifier = Modifier.height(8.dp))
        FeedbackPane(modifier = Modifier.weight(1f))
    }
}

// The header and tab row copy MainActivity.renderHome, which draws them with
// Views: the same text, colours and sizes, its whole-pixel dp, its status bar
// height and TextView's font padding, so they don't move when the tab changes.

/** Text laid out like a TextView's. */
@Suppress("DEPRECATION")
private val ViewText = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = true))

/** MainActivity's dp(): whole pixels, rounded down. */
@Composable
private fun vdp(v: Int): Dp {
    val density = LocalDensity.current
    return with(density) { (v * density.density).toInt().toDp() }
}

/** MainActivity's statusBarHeight(). */
@SuppressLint("DiscouragedApi", "InternalInsetResource")
@Composable
private fun statusBarHeight(): Dp {
    val res = LocalContext.current.resources
    val id = res.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) with(LocalDensity.current) { res.getDimensionPixelSize(id).toDp() } else vdp(28)
}

@Composable
private fun HomeHeader(c: KitColors) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.accent)
            .padding(start = vdp(20), end = vdp(20), top = statusBarHeight() + vdp(6), bottom = vdp(18)),
    ) {
        Text(
            "MirrorMsg",
            color = c.accentFg,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            style = ViewText,
        )
        Text(
            "Messages on your Pebble",
            color = c.accentFg.copy(alpha = 0.85f),
            fontSize = 14.sp,
            style = ViewText,
        )
    }
}

/** The home tabs, [tabs] then Feedback (selected); a tap on one calls [onTab] with its position. */
@Composable
private fun HomeTabs(c: KitColors, tabs: List<String>, onTab: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = vdp(16), top = vdp(16), end = vdp(16), bottom = vdp(8)),
    ) {
        tabs.forEachIndexed { i, label ->
            HomeTab(c, label, selected = false, onClick = { onTab(i) })
            Spacer(modifier = Modifier.width(vdp(6)))
        }
        HomeTab(c, "Feedback", selected = true, onClick = null)
    }
}

@Composable
private fun RowScope.HomeTab(c: KitColors, label: String, selected: Boolean, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(vdp(10))
    val stroke = vdp(1)
    var modifier = Modifier
        .weight(1f)
        .clip(shape)
        .background(if (selected) c.accent else c.cardBg)
    if (!selected) modifier = modifier.border(stroke, c.cardDivider, shape)
    if (onClick != null) {
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
    }
    Box(
        modifier = modifier.padding(horizontal = vdp(4), vertical = vdp(14)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) c.accentFg else c.fg,
            fontSize = 14.sp,
            maxLines = 1,
            softWrap = false,
            style = ViewText,
        )
    }
}

@Composable
private fun SettingsCard(c: KitColors) {
    val context = LocalContext.current
    val code by MsgFeedback.ownerCode.collectAsState()
    var mode by remember { mutableStateOf(MsgFeedback.logsMode(context)) }
    var editing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.cardBg)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { editing = true }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Owner code", color = c.fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Test builds only. Your access code: sends photos and voice, marks reports as yours",
                    color = c.helpFg,
                    fontSize = 11.sp,
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(if (code != null) "Set" else "Not set", color = c.accent, fontSize = 14.sp)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Logs in bug reports",
                color = c.fg,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in listOf(LogsMode.FULL, LogsMode.REDACTED, LogsMode.NONE)) {
                    val selected = m == mode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) c.accent else Color.Transparent)
                            .clickable {
                                mode = m
                                MsgFeedback.setLogsMode(context, m)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            label(m),
                            color = if (selected) c.accentFg else c.fg,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }

    if (editing) {
        OwnerCodeDialog(
            hasCode = code != null,
            onSave = {
                MsgFeedback.setOwnerCode(it)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}

private fun label(m: LogsMode) = when (m) {
    LogsMode.FULL -> "Full"
    LogsMode.REDACTED -> "Redacted"
    LogsMode.NONE -> "None"
}

@Composable
private fun OwnerCodeDialog(hasCode: Boolean, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Owner code") },
        text = {
            Column {
                Text(
                    "Paste your access code. It's stored encrypted on this phone and only " +
                        "sent with your Feedback messages.",
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    placeholder = { Text(if (hasCode) "Saved (paste to replace)" else "Access code") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (hasCode) {
                    TextButton(onClick = { onSave(null) }) { Text("Clear") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
