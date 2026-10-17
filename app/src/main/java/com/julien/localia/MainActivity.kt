package com.julien.localia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.util.Locale

private val Bg = Color(0xFF0B1020)
private val Surface1 = Color(0xFF141A2E)
private val Surface2 = Color(0xFF1C2440)
private val Accent = Color(0xFF5EEAD4)
private val Accent2 = Color(0xFFA78BFA)
private val TextMain = Color(0xFFE6E9F2)
private val TextDim = Color(0xFF8B93AD)
private val Danger = Color(0xFFF87171)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, onPrimary = Bg, secondary = Accent2,
                    background = Bg, surface = Surface1, onSurface = TextMain, onBackground = TextMain,
                    surfaceVariant = Surface2, onSurfaceVariant = TextDim,
                )
            ) { App() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun App(vm: ChatViewModel = viewModel()) {
    val st by vm.ui.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }
    val pick = { picker.launch(arrayOf("*/*")) }

    LaunchedEffect(Unit) {
        while (true) { vm.refreshRam(); delay(3000) }
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextMain),
                title = {
                    Column {
                        Text("LocalIA", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(
                            subtitle(st), fontSize = 12.sp, color = TextDim, maxLines = 1
                        )
                    }
                },
                actions = {
                    if (st.phase == Phase.READY) {
                        IconButton(onClick = { vm.newChat() }) {
                            Icon(Icons.Default.Add, "Nouvelle discussion", tint = Accent)
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, "Réglages", tint = TextDim)
                    }
                }
            )
        }
    ) { pad ->
        Box(
            Modifier
                .padding(pad)
                .consumeWindowInsets(pad)
                .fillMaxSize()
                .imePadding()
        ) {
            when (st.phase) {
                Phase.NO_MODEL -> WelcomeScreen(st, pick)
                Phase.COPYING, Phase.LOADING -> LoadingScreen(st)
                Phase.ERROR -> ErrorScreen(st, onRetry = { vm.loadEngine() }, onPick = pick,
                    onCpu = { vm.applySettings(st.settings.copy(backend = "CPU")); vm.loadEngine() })
                Phase.READY -> ChatScreen(st, onSend = vm::send, onStop = vm::stop)
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            st = st,
            onDismiss = { showSettings = false },
            onApply = { vm.applySettings(it); showSettings = false },
            onPick = { showSettings = false; pick() },
            onDelete = { showSettings = false; vm.deleteModel() },
        )
    }
}

private fun subtitle(st: UiState): String {
    val ram = "RAM libre ${gb(st.ramFreeMb)} / ${gb(st.ramTotalMb)} Go"
    return when (st.phase) {
        Phase.READY -> buildString {
            append(st.backendUsed)
            if (st.speed > 0f) append(" · ${String.format(Locale.FRANCE, "%.1f", st.speed)} tok/s")
            append(" · ").append(ram)
        }
        else -> ram
    }
}

private fun gb(mb: Long) = String.format(Locale.FRANCE, "%.1f", mb / 1024f)

// ---------------- Écrans ----------------

@Composable
private fun WelcomeScreen(st: UiState, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(96.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(Accent, Accent2))),
            contentAlignment = Alignment.Center
        ) { Text("IA", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Bg) }
        Spacer(Modifier.height(24.dp))
        Text("Ton IA, 100 % sur ton téléphone", fontSize = 22.sp, fontWeight = FontWeight.Bold,
            color = TextMain, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            "Choisis ton fichier modèle .litertlm (ex. Qwen3 1,7B). Il sera copié dans l'app " +
                "(prévoir ~2 Go d'espace libre), ensuite plus besoin d'internet.",
            color = TextDim, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onPick, shape = RoundedCornerShape(14.dp), modifier = Modifier.height(52.dp)) {
            Text("Choisir le modèle .litertlm", fontWeight = FontWeight.SemiBold)
        }
        if (st.status.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(st.status, color = Danger, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun LoadingScreen(st: UiState) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (st.phase == Phase.COPYING) {
            LinearProgressIndicator(
                progress = { st.copyProgress },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = Accent, trackColor = Surface2,
            )
        } else {
            CircularProgressIndicator(color = Accent)
        }
        Spacer(Modifier.height(20.dp))
        Text(st.status, color = TextMain, textAlign = TextAlign.Center)
        if (st.modelName.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(st.modelName, color = TextDim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ErrorScreen(st: UiState, onRetry: () -> Unit, onPick: () -> Unit, onCpu: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Le modèle n'a pas pu démarrer", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextMain)
        Spacer(Modifier.height(12.dp))
        Surface(color = Surface1, shape = RoundedCornerShape(12.dp)) {
            Text(st.status, color = Danger, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.padding(14.dp))
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Réessayer") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCpu, modifier = Modifier.fillMaxWidth()) { Text("Forcer le CPU") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("Choisir un autre fichier") }
    }
}

@Composable
private fun ChatScreen(st: UiState, onSend: (String) -> Unit, onStop: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val last = st.messages.lastOrNull()

    LaunchedEffect(st.messages.size, last?.text?.length) {
        if (st.messages.isNotEmpty()) listState.scrollToItem(st.messages.size - 1, Int.MAX_VALUE / 2)
    }

    Column(Modifier.fillMaxSize()) {
        if (st.messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text("Prêt. Hors ligne.", color = Accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(st.modelName, color = TextDim, fontSize = 13.sp, textAlign = TextAlign.Center)
                    if (st.status.contains("CPU")) {
                        Spacer(Modifier.height(6.dp))
                        Text(st.status, color = TextDim, fontSize = 12.sp)
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(st.messages, key = { it.id }) { Bubble(it) }
            }
        }

        Row(
            Modifier.fillMaxWidth().background(Bg).padding(10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Écris ton message…", color = TextDim) },
                maxLines = 6,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = Surface2,
                    focusedContainerColor = Surface1, unfocusedContainerColor = Surface1,
                )
            )
            Spacer(Modifier.width(8.dp))
            if (st.generating) {
                FilledIconButton(
                    onClick = onStop, modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Danger)
                ) { Icon(Icons.Default.Close, "Arrêter", tint = Bg) }
            } else {
                FilledIconButton(
                    onClick = { onSend(input); input = "" },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.size(52.dp)
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Envoyer") }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMsg) {
    val isUser = m.role == Role.USER
    var showThought by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (isUser) Surface2 else Surface1,
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp, bottomEnd = if (isUser) 4.dp else 18.dp
            ),
            modifier = Modifier.widthIn(max = 330.dp).animateContentSize()
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (m.thought.isNotBlank()) {
                    Text(
                        if (showThought) "▾ Réflexion" else "▸ Réflexion",
                        color = Accent2, fontSize = 12.sp,
                        modifier = Modifier.clickable { showThought = !showThought }.padding(bottom = 4.dp)
                    )
                    if (showThought) {
                        Text(m.thought, color = TextDim, fontSize = 13.sp, fontStyle = FontStyle.Italic,
                            modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
                when {
                    m.streaming && m.text.isBlank() -> Text(
                        if (m.thought.isNotBlank()) "réfléchit…" else "…", color = TextDim
                    )
                    else -> SelectionContainer {
                        Text(
                            m.text + if (m.streaming) " ▌" else "",
                            color = if (m.error) Danger else TextMain,
                            fontSize = 15.sp, lineHeight = 21.sp
                        )
                    }
                }
            }
        }
    }
}

// ---------------- Réglages ----------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsSheet(
    st: UiState,
    onDismiss: () -> Unit,
    onApply: (Settings) -> Unit,
    onPick: () -> Unit,
    onDelete: () -> Unit,
) {
    var s by remember { mutableStateOf(st.settings) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface1) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())
        ) {
            Text("Réglages", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextMain)
            Spacer(Modifier.height(4.dp))
            Text("Modèle : ${st.modelName.ifBlank { "aucun" }}", color = TextDim, fontSize = 13.sp)

            Spacer(Modifier.height(18.dp))
            Text("Accélération", color = TextMain, fontWeight = FontWeight.SemiBold)
            Text("Auto = essaie le GPU puis repasse sur CPU si ça échoue.", color = TextDim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("AUTO" to "Auto", "GPU" to "GPU", "CPU" to "CPU").forEach { (k, label) ->
                    FilterChip(selected = s.backend == k, onClick = { s = s.copy(backend = k) }, label = { Text(label) })
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mode réflexion", color = TextMain, fontWeight = FontWeight.SemiBold)
                    Text("Plus intelligent mais beaucoup plus lent (Qwen3).", color = TextDim, fontSize = 12.sp)
                }
                Switch(checked = s.thinking, onCheckedChange = { s = s.copy(thinking = it) })
            }

            Spacer(Modifier.height(16.dp))
            Text("Température : ${String.format(Locale.FRANCE, "%.2f", s.temperature)}",
                color = TextMain, fontWeight = FontWeight.SemiBold)
            Text("Bas = précis, haut = créatif", color = TextDim, fontSize = 12.sp)
            Slider(value = s.temperature, onValueChange = { s = s.copy(temperature = it) }, valueRange = 0.05f..1.5f)

            Text("Top-K : ${s.topK}", color = TextMain, fontWeight = FontWeight.SemiBold)
            Slider(value = s.topK.toFloat(), onValueChange = { s = s.copy(topK = it.toInt().coerceAtLeast(1)) },
                valueRange = 1f..100f)

            Spacer(Modifier.height(8.dp))
            Text("Instruction système", color = TextMain, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = s.systemPrompt, onValueChange = { s = s.copy(systemPrompt = it) },
                modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
            )
            TextButton(onClick = { s = s.copy(systemPrompt = DEFAULT_SYSTEM) }) { Text("Rétablir par défaut") }

            Spacer(Modifier.height(8.dp))
            Button(onClick = { onApply(s) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Appliquer (nouvelle discussion)")
            }
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = Surface2)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("Changer de modèle") }
            if (st.modelName.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Text("Supprimer le modèle de l'app", color = Danger)
                }
            }
        }
    }
}
