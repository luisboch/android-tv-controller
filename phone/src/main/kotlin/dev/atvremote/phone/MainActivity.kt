package dev.atvremote.phone

import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atvremote.common.Command
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding().padding(24.dp)) {
                        when (val s = vm.state) {
                            is UiState.Unpaired -> PairScreen(s, vm::startPairing, vm::connectToIp)
                            UiState.Searching -> Progress("Procurando a TV na rede…")
                            UiState.Connecting -> Progress("Conectando…")
                            is UiState.ChooseTv -> ChooseScreen(s.tvs, vm::connect)
                            is UiState.EnterPin -> PinScreen(s, vm::submitPin, vm::cancelPairing)
                            is UiState.Paired -> RemoteScreen(s.tvName, vm::press, vm::sendText, vm::unpair)
                        }
                    }
                }
            }
        }
    }

    /** The phone's own volume buttons control the TV while paired. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (vm.state is UiState.Paired && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            vm.press(keyCode)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun Progress(message: String) = Centered {
    CircularProgressIndicator()
    Text(message)
}

@Composable
private fun PairScreen(state: UiState.Unpaired, onPair: () -> Unit, onConnectIp: (String) -> Unit) = Centered {
    Text("ATV Remote", style = MaterialTheme.typography.headlineMedium)
    Button(onClick = onPair, modifier = Modifier.width(200.dp).height(56.dp)) {
        Text("Parear", fontSize = 18.sp)
    }
    if (state.error != null) {
        Text(state.error, color = MaterialTheme.colorScheme.error)
        var ip by remember { mutableStateOf("") }
        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            label = { Text("IP da TV (opcional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedButton(onClick = { onConnectIp(ip) }, enabled = ip.isNotBlank()) { Text("Conectar pelo IP") }
    }
}

@Composable
private fun ChooseScreen(tvs: List<FoundTv>, onChoose: (FoundTv) -> Unit) = Centered {
    Text("Escolha a TV", style = MaterialTheme.typography.titleLarge)
    tvs.forEach { tv ->
        OutlinedButton(onClick = { onChoose(tv) }, modifier = Modifier.fillMaxWidth()) {
            Text("${tv.name}  (${tv.address.hostAddress})")
        }
    }
}

@Composable
private fun PinScreen(state: UiState.EnterPin, onSubmit: (String) -> Unit, onCancel: () -> Unit) = Centered {
    var pin by remember(state.error) { mutableStateOf("") }
    Text("Digite o código que apareceu em ${state.tvName}", textAlign = TextAlign.Center)
    OutlinedTextField(
        value = pin,
        onValueChange = { v ->
            pin = v.filter(Char::isDigit).take(4)
            if (pin.length == 4) onSubmit(pin)
        },
        enabled = !state.busy,
        singleLine = true,
        isError = state.error != null,
        supportingText = state.error?.let { { Text(it) } },
        textStyle = MaterialTheme.typography.displaySmall.copy(textAlign = TextAlign.Center, letterSpacing = 12.sp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.width(220.dp),
    )
    if (state.busy) CircularProgressIndicator()
    TextButton(onClick = onCancel) { Text("Cancelar") }
}

@Composable
private fun RemoteScreen(tvName: String, press: (Int) -> Unit, sendText: (String) -> Unit, onUnpair: () -> Unit) {
    var typing by remember { mutableStateOf(false) }
    if (typing) TextDialog(onSend = { typing = false; sendText(it) }, onDismiss = { typing = false })

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tvName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { typing = true }) { Icon(Icons.Default.Keyboard, "Enviar texto") }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Mais") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Desparear") }, onClick = { menu = false; onUnpair() })
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RemoteKey(Icons.Default.PowerSettingsNew, "Ligar/desligar", { press(KeyEvent.KEYCODE_POWER) }, danger = true)
            RemoteKey(Icons.AutoMirrored.Filled.VolumeOff, "Mudo", { press(KeyEvent.KEYCODE_VOLUME_MUTE) })
        }

        Spacer(Modifier.weight(1f))
        DPad(press)

        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RemoteKey(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", { press(KeyEvent.KEYCODE_BACK) })
            RemoteKey(Icons.Default.Home, "Início", { press(KeyEvent.KEYCODE_HOME) })
            RemoteKey(Icons.Default.Menu, "Menu", { press(KeyEvent.KEYCODE_MENU) })
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RemoteKey(Icons.AutoMirrored.Filled.VolumeDown, "Volume -", { press(KeyEvent.KEYCODE_VOLUME_DOWN) }, repeat = true)
            RemoteKey(Icons.Default.FastRewind, "Retroceder", { press(KeyEvent.KEYCODE_MEDIA_REWIND) })
            RemoteKey(Icons.Default.PlayArrow, "Play/pause", { press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) })
            RemoteKey(Icons.Default.FastForward, "Avançar", { press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) })
            RemoteKey(Icons.AutoMirrored.Filled.VolumeUp, "Volume +", { press(KeyEvent.KEYCODE_VOLUME_UP) }, repeat = true)
        }
        Spacer(Modifier.weight(1f))
    }
}

/** Text typed with the phone keyboard; the TV inserts it into the focused field, or drops it if there is none. */
@Composable
private fun TextDialog(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val send = { if (text.isNotEmpty()) onSend(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enviar texto") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> if (v.toByteArray().size <= Command.MAX_TEXT_BYTES) text = v },
                placeholder = { Text("Digite aqui") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = send, enabled = text.isNotEmpty()) { Text("Enviar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun DPad(press: (Int) -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(272.dp)) {
        Box {
            RemoteKey(Icons.Default.KeyboardArrowUp, "Cima", { press(KeyEvent.KEYCODE_DPAD_UP) }, Modifier.align(Alignment.TopCenter).padding(8.dp), size = 80.dp, repeat = true, flat = true)
            RemoteKey(Icons.Default.KeyboardArrowDown, "Baixo", { press(KeyEvent.KEYCODE_DPAD_DOWN) }, Modifier.align(Alignment.BottomCenter).padding(8.dp), size = 80.dp, repeat = true, flat = true)
            RemoteKey(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Esquerda", { press(KeyEvent.KEYCODE_DPAD_LEFT) }, Modifier.align(Alignment.CenterStart).padding(8.dp), size = 80.dp, repeat = true, flat = true)
            RemoteKey(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Direita", { press(KeyEvent.KEYCODE_DPAD_RIGHT) }, Modifier.align(Alignment.CenterEnd).padding(8.dp), size = 80.dp, repeat = true, flat = true)
            RemoteKey(null, "OK", { press(KeyEvent.KEYCODE_DPAD_CENTER) }, Modifier.align(Alignment.Center), size = 96.dp)
        }
    }
}

/** Round key that fires on touch down (feels like a real remote) and optionally repeats while held. */
@Composable
private fun RemoteKey(
    icon: ImageVector?,
    label: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    repeat: Boolean = false,
    flat: Boolean = false,
    danger: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val currentOnPress by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val color = when {
        pressed -> colors.primary
        danger -> colors.errorContainer
        flat -> colors.surfaceContainerHigh
        else -> colors.secondaryContainer
    }
    val contentColor = when {
        pressed -> colors.onPrimary
        danger -> colors.onErrorContainer
        else -> colors.onSecondaryContainer
    }
    Surface(
        shape = if (flat) RoundedCornerShape(24.dp) else CircleShape,
        color = color,
        contentColor = contentColor,
        modifier = modifier.size(size).pointerInput(repeat) {
            awaitEachGesture {
                awaitFirstDown()
                pressed = true
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                currentOnPress()
                val repeater = if (repeat) {
                    scope.launch {
                        delay(400)
                        while (true) {
                            currentOnPress()
                            delay(110)
                        }
                    }
                } else {
                    null
                }
                waitForUpOrCancellation()
                repeater?.cancel()
                pressed = false
            }
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(icon, label, Modifier.size(size * 0.45f))
            } else {
                Text(label, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        }
    }
}
