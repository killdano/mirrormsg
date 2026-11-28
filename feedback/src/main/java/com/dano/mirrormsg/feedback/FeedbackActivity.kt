package com.dano.mirrormsg.feedback

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrorapps.kit.KitColors
import com.mirrorapps.kit.feedback.FeedbackPane

/**
 * The Feedback screen: MirrorMsg's header and the owner code setting, then
 * the kit's Feedback chat, which shows "Logs in bug reports" at its top (the
 * same chat as the other apps). Test builds only.
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
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) {
                darkColorScheme(primary = MsgTheme.accent)
            } else {
                lightColorScheme(primary = MsgTheme.accent)
            }
            MaterialTheme(colorScheme = scheme) {
                FeedbackScreen()
            }
        }
    }
}

@Composable
private fun FeedbackScreen() {
    val c = MsgTheme.colors()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.accent)
                .statusBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 18.dp),
        ) {
            Text("Feedback", color = c.accentFg, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "Tell us what went wrong or what you'd like",
                color = c.accentFg.copy(alpha = 0.85f),
                fontSize = 14.sp,
            )
        }
        SettingsCard(c)
        Spacer(modifier = Modifier.height(8.dp))
        FeedbackPane(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SettingsCard(c: KitColors) {
    val code by MsgFeedback.ownerCode.collectAsState()
    var editing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
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
