package app.zenelo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.data.settings.ZeneloSettings
import app.zenelo.mstream.MStreamSync
import app.zenelo.ui.components.IconTile
import app.zenelo.ui.components.ListRow
import app.zenelo.ui.components.SectionHeader
import app.zenelo.ui.components.appContainer
import app.zenelo.ui.theme.ZeneloColors
import app.zenelo.work.LibraryWork
import kotlinx.coroutines.launch

/**
 * The mStream server: log in (address, user name, password), then its library joins ours
 * (synced in the background); "Sync now" and "Log out".
 */
@Composable
fun MStreamSettings(onMessage: (String) -> Unit) {
    val container = appContainer()
    val settingsFlow = remember { container.settings.settings }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = ZeneloSettings())
    val account = settings.mstream
    if (account == null) LoginForm(onMessage) else Connected(onMessage)
}

@Composable
private fun LoginForm(onMessage: (String) -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    SectionHeader("Connect")
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Your mStream server's library joins this one: stream its tracks, their covers and lyrics.",
            style = MaterialTheme.typography.bodySmall,
            color = ZeneloColors.TextSecondary,
        )
        Field(url, { url = it }, "Server address", "192.168.1.10:3000", KeyboardType.Uri)
        Field(user, { user = it }, "User name", "", KeyboardType.Text)
        Field(password, { password = it }, "Password", "", KeyboardType.Password, secret = true)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZeneloColors.Danger) }
        Button(
            enabled = !busy && url.isNotBlank(),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    runCatching { container.mstream.login(url, user.trim(), password) }
                        .onSuccess { account ->
                            container.settings.setMStream(account)
                            LibraryWork.syncServer(context, force = true)
                            onMessage("Connected to ${account.url}")
                        }
                        .onFailure { error = it.message ?: "Couldn't connect" }
                    busy = false
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = ZeneloColors.Mustard, contentColor = ZeneloColors.OnMustard),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(color = ZeneloColors.OnMustard, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) else Text("Connect")
        }
        Text(
            "No users set up on the server? Leave the name and password empty.",
            style = MaterialTheme.typography.bodySmall,
            color = ZeneloColors.TextMuted,
        )
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, hint: String, type: KeyboardType, secret: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(label) },
        placeholder = if (hint.isEmpty()) null else ({ Text(hint) }),
        keyboardOptions = KeyboardOptions(keyboardType = type),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ZeneloColors.Mustard, cursorColor = ZeneloColors.Mustard),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Connected(onMessage: (String) -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsFlow = remember { container.settings.settings }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = ZeneloSettings())
    val account = settings.mstream ?: return
    val countFlow = remember { container.db.remoteTracks().observeCount() }
    val count by countFlow.collectAsStateWithLifecycle(initialValue = 0)
    val state by container.mstreamSync.state.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }

    SectionHeader("Server")
    ListRow(
        title = account.url.removePrefix("http://").removePrefix("https://"),
        subtitle = if (account.username.isEmpty()) "Connected" else "Connected as ${account.username}",
        leading = { IconTile(Icons.Outlined.Cloud, ZeneloColors.Celadon, ZeneloColors.CeladonTint) },
    )
    ListRow(
        title = "Sync now",
        subtitle = when (val s = state) {
            is MStreamSync.State.Running -> "Syncing… ${s.tracks} tracks"
            is MStreamSync.State.Failed -> "Last sync failed: ${s.message}"
            MStreamSync.State.Idle -> "$count tracks in the library"
        },
        onClick = { LibraryWork.syncServer(context, force = true) },
        leading = { IconTile(Icons.Outlined.Sync, ZeneloColors.Mustard, ZeneloColors.MustardTint) },
        trailing = {
            if (state is MStreamSync.State.Running) {
                CircularProgressIndicator(color = ZeneloColors.Mustard, strokeWidth = 2.dp, modifier = Modifier.padding(12.dp).size(18.dp))
            }
        },
    )
    ListRow(
        title = "Log out",
        subtitle = "The server's tracks leave the library",
        onClick = { confirmLogout = true },
        leading = { IconTile(Icons.AutoMirrored.Outlined.Logout, ZeneloColors.Danger, ZeneloColors.Danger.copy(alpha = 0.14f)) },
    )
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            containerColor = ZeneloColors.Card,
            title = { Text("Log out of mStream?") },
            text = { Text("Its tracks leave the library. Favorites and playlists keep them for when you log in again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    scope.launch {
                        container.settings.setMStream(null)
                        container.mstreamSync.forget()
                        onMessage("Logged out of mStream")
                    }
                }) { Text("Log out", color = ZeneloColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}
