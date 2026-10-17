package com.julien.localia

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Role { USER, MODEL }
enum class Phase { NO_MODEL, COPYING, LOADING, READY, ERROR }

data class ChatMsg(
    val id: Long,
    val role: Role,
    val text: String,
    val thought: String = "",
    val streaming: Boolean = false,
    val error: Boolean = false,
)

data class Settings(
    val systemPrompt: String = DEFAULT_SYSTEM,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val thinking: Boolean = false,
    val backend: String = "AUTO", // AUTO, GPU, CPU
)

data class UiState(
    val phase: Phase = Phase.NO_MODEL,
    val status: String = "",
    val loadStartMs: Long = 0,
    val copyProgress: Float = 0f,
    val modelName: String = "",
    val backendUsed: String = "",
    val messages: List<ChatMsg> = emptyList(),
    val generating: Boolean = false,
    val speed: Float = 0f,
    val ramFreeMb: Long = 0,
    val ramTotalMb: Long = 0,
    val settings: Settings = Settings(),
)

const val DEFAULT_SYSTEM =
    "Tu es un assistant IA qui tourne entièrement hors ligne sur le téléphone de l'utilisateur. " +
        "Réponds en français, de façon claire et concise. Si tu ne sais pas, dis-le."

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()
    private val prefs = app.getSharedPreferences("localia", Context.MODE_PRIVATE)
    private val modelDir = File(app.filesDir, "models").apply { mkdirs() }
    private val modelFile = File(modelDir, "model.litertlm")

    private val _ui = MutableStateFlow(UiState(settings = loadSettings()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var genJob: Job? = null
    private var nextId = 1L

    init {
        refreshRam()
        if (modelFile.exists() && modelFile.length() > 0) {
            _ui.update { it.copy(modelName = prefs.getString("modelName", "model.litertlm") ?: "") }
            loadEngine()
        }
    }

    // ---------- Réglages ----------

    private fun loadSettings() = Settings(
        systemPrompt = prefs.getString("sys", DEFAULT_SYSTEM) ?: DEFAULT_SYSTEM,
        temperature = prefs.getFloat("temp", 0.7f),
        topK = prefs.getInt("topk", 40),
        thinking = prefs.getBoolean("think", false),
        backend = prefs.getString("backend", "AUTO") ?: "AUTO",
    )

    fun applySettings(s: Settings) {
        val old = _ui.value.settings
        prefs.edit()
            .putString("sys", s.systemPrompt)
            .putFloat("temp", s.temperature)
            .putInt("topk", s.topK)
            .putBoolean("think", s.thinking)
            .putString("backend", s.backend)
            .apply()
        _ui.update { it.copy(settings = s) }
        if (_ui.value.phase != Phase.READY) return
        if (old.backend != s.backend) loadEngine() else newChat()
    }

    // ---------- RAM ----------

    fun refreshRam() {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        _ui.update { it.copy(ramFreeMb = mi.availMem / 1_048_576, ramTotalMb = mi.totalMem / 1_048_576) }
    }

    // ---------- Import du modèle ----------

    fun importModel(uri: Uri) {
        if (_ui.value.generating) stop()
        viewModelScope.launch {
            _ui.update { it.copy(phase = Phase.COPYING, copyProgress = 0f, status = "Copie du modèle…") }
            try {
                val (name, size) = withContext(Dispatchers.IO) { queryNameSize(uri) }
                if (!name.endsWith(".litertlm", ignoreCase = true)) {
                    throw IllegalArgumentException("Ce fichier n'est pas un .litertlm ($name)")
                }
                withContext(Dispatchers.IO) { closeEngine() }

                val free = StatFs(modelDir.path).availableBytes + (if (modelFile.exists()) modelFile.length() else 0)
                if (size > 0 && free < size + 200L * 1_048_576) {
                    throw IllegalStateException(
                        "Pas assez de stockage : il faut ${mb(size)} Mo, libre ${mb(free)} Mo"
                    )
                }

                withContext(Dispatchers.IO) {
                    val tmp = File(modelDir, "import.tmp")
                    ctx.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Impossible d'ouvrir le fichier" }
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(4 * 1024 * 1024)
                            var copied = 0L
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                copied += n
                                if (size > 0) {
                                    val pct = (copied * 100 / size).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        _ui.update {
                                            it.copy(
                                                copyProgress = copied.toFloat() / size,
                                                status = "Copie du modèle… ${mb(copied)} / ${mb(size)} Mo"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (modelFile.exists()) modelFile.delete()
                    if (!tmp.renameTo(modelFile)) throw IllegalStateException("Échec de l'enregistrement du modèle")
                    // cache compilé de l'ancien modèle : inutile maintenant
                    ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                }
                prefs.edit().putString("modelName", name).apply()
                _ui.update { it.copy(modelName = name, messages = emptyList()) }
                loadEngine()
            } catch (t: Throwable) {
                File(modelDir, "import.tmp").delete()
                val hasModel = modelFile.exists()
                _ui.update {
                    it.copy(
                        phase = if (hasModel) Phase.ERROR else Phase.NO_MODEL,
                        status = "Import impossible : ${t.message ?: t.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    private fun queryNameSize(uri: Uri): Pair<String, Long> {
        var name = "model.litertlm"
        var size = -1L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        return name to size
    }

    fun deleteModel() {
        stop()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                closeEngine()
                modelFile.delete()
                ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            }
            prefs.edit().remove("modelName").apply()
            _ui.update { UiState(settings = it.settings, ramFreeMb = it.ramFreeMb, ramTotalMb = it.ramTotalMb) }
        }
    }

    // ---------- Moteur ----------

    fun loadEngine() {
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    phase = Phase.LOADING, status = "Chargement du modèle en mémoire…", backendUsed = "",
                    loadStartMs = System.currentTimeMillis(),
                )
            }
            val wanted = _ui.value.settings.backend
            // Un chargement GPU précédent n'est jamais revenu (app tuée ou bloquée) : on passe direct au CPU
            val gpuHung = prefs.getBoolean("gpuPending", false)
            val order = when {
                wanted == "CPU" -> listOf("CPU")
                gpuHung -> listOf("CPU")
                wanted == "GPU" -> listOf("GPU")
                else -> listOf("GPU", "CPU")
            }
            val errors = mutableListOf<String>()
            if (gpuHung) errors += "GPU : le dernier chargement ne s'est pas terminé"
            prefs.edit().putBoolean("gpuPending", false).commit()
            var used: String? = null
            withContext(Dispatchers.IO) {
                closeEngine()
                for (b in order) {
                    _ui.update { it.copy(status = "Chargement du modèle ($b)… ça peut prendre 10 à 30 s") }
                    var e: Engine? = null
                    // commit() synchrone : le drapeau doit être écrit avant un éventuel blocage natif
                    if (b == "GPU") prefs.edit().putBoolean("gpuPending", true).commit()
                    try {
                        e = Engine(
                            EngineConfig(
                                modelPath = modelFile.absolutePath,
                                backend = if (b == "GPU") Backend.GPU() else Backend.CPU(),
                                cacheDir = ctx.cacheDir.path,
                            )
                        )
                        e.initialize()
                        engine = e
                        used = b
                        break
                    } catch (t: Throwable) {
                        errors += "$b : ${t.message ?: t.javaClass.simpleName}"
                        try { e?.close() } catch (_: Throwable) {}
                        System.gc()
                    } finally {
                        if (b == "GPU") prefs.edit().putBoolean("gpuPending", false).commit()
                    }
                }
            }
            refreshRam()
            if (used == null) {
                _ui.update { it.copy(phase = Phase.ERROR, status = "Chargement impossible.\n" + errors.joinToString("\n")) }
                return@launch
            }
            try {
                withContext(Dispatchers.IO) { createConversation() }
                _ui.update {
                    it.copy(
                        phase = Phase.READY,
                        backendUsed = used!!,
                        status = if (errors.isNotEmpty()) "GPU indisponible, bascule sur CPU" else "Prêt"
                    )
                }
            } catch (t: Throwable) {
                _ui.update { it.copy(phase = Phase.ERROR, status = "Erreur conversation : ${t.message}") }
            }
        }
    }

    /**
     * L'init native du moteur ne peut pas être interrompue : on enregistre le choix CPU
     * puis on redémarre le processus de l'app.
     */
    fun restartOnCpu() {
        prefs.edit()
            .putString("backend", "CPU")
            .putBoolean("gpuPending", false)
            .commit()
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent != null) ctx.startActivity(intent)
        Process.killProcess(Process.myPid())
    }

    private fun createConversation() {
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = null
        val eng = engine ?: throw IllegalStateException("Moteur non chargé")
        val s = _ui.value.settings
        val sys = s.systemPrompt.ifBlank { DEFAULT_SYSTEM }
        conversation = eng.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(sys),
                samplerConfig = SamplerConfig(
                    topK = s.topK,
                    topP = 0.95,
                    temperature = s.temperature.toDouble(),
                ),
                thinkingConfig = ThinkingConfig(
                    enableThinking = s.thinking,
                    thinkingTokenBudget = if (s.thinking) 1024 else 0,
                ),
            )
        )
    }

    private fun closeEngine() {
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = null
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
        System.gc()
    }

    // ---------- Discussion ----------

    fun newChat() {
        stop()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { createConversation() }
                _ui.update { it.copy(messages = emptyList(), speed = 0f, status = "Nouvelle discussion") }
            } catch (t: Throwable) {
                _ui.update { it.copy(status = "Erreur : ${t.message}") }
            }
        }
    }

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || _ui.value.generating || _ui.value.phase != Phase.READY) return
        val userMsg = ChatMsg(nextId++, Role.USER, text)
        val botId = nextId++
        _ui.update {
            it.copy(
                messages = it.messages + userMsg + ChatMsg(botId, Role.MODEL, "", streaming = true),
                generating = true,
                speed = 0f,
            )
        }

        genJob = viewModelScope.launch {
            val start = System.currentTimeMillis()
            var chunks = 0
            val acc = StringBuilder()
            val thought = StringBuilder()
            var failed: String? = null
            try {
                val conv = conversation ?: withContext(Dispatchers.IO) { createConversation(); conversation!! }
                conv.sendMessageAsync(text)
                    .flowOn(Dispatchers.IO)
                    .collect { m ->
                        acc.append(m.toString())
                        val th = try { m.channels["thought"]?.toString().orEmpty() } catch (_: Throwable) { "" }
                        thought.append(th)
                        chunks++
                        val secs = (System.currentTimeMillis() - start) / 1000f
                        val (shown, inlineThought) = splitThink(acc.toString())
                        updateBot(botId) {
                            it.copy(text = shown, thought = (thought.toString() + inlineThought).trim())
                        }
                        if (secs > 0.3f) _ui.update { it.copy(speed = chunks / secs) }
                    }
            } catch (c: CancellationException) {
                cancelNative()
                updateBot(botId) { it.copy(text = (it.text + "\n\n⏹ (arrêté)").trim(), streaming = false) }
                _ui.update { it.copy(generating = false) }
                throw c
            } catch (t: Throwable) {
                failed = t.message ?: t.javaClass.simpleName
            }
            updateBot(botId) {
                if (failed != null) it.copy(text = it.text + "\n\n⚠ Erreur : $failed", streaming = false, error = true)
                else it.copy(streaming = false, text = it.text.ifBlank { "(réponse vide)" })
            }
            _ui.update { it.copy(generating = false) }
            refreshRam()
            if (failed != null) {
                // repart sur une conversation propre pour la suite
                try { withContext(Dispatchers.IO) { createConversation() } } catch (_: Throwable) {}
            }
        }
    }

    fun stop() {
        genJob?.cancel()
        genJob = null
    }

    /** Demande au moteur natif d'arrêter la génération en cours (si la version de la lib le permet). */
    private fun cancelNative() {
        val c = conversation ?: return
        for (name in listOf("cancelProcess", "cancel")) {
            try {
                c.javaClass.getMethod(name).invoke(c)
                return
            } catch (_: Throwable) {}
        }
    }

    private fun updateBot(id: Long, f: (ChatMsg) -> ChatMsg) {
        _ui.update { st -> st.copy(messages = st.messages.map { if (it.id == id) f(it) else it }) }
    }

    /** Sépare un éventuel bloc <think>…</think> laissé dans le texte (Qwen3). */
    private fun splitThink(s: String): Pair<String, String> {
        val open = s.indexOf("<think>")
        if (open < 0) return s.trim() to ""
        val close = s.indexOf("</think>", open)
        return if (close < 0) {
            s.substring(0, open).trim() to s.substring(open + 7)
        } else {
            (s.substring(0, open) + s.substring(close + 8)).trim() to s.substring(open + 7, close)
        }
    }

    override fun onCleared() {
        genJob?.cancel()
        closeEngine()
        super.onCleared()
    }

    private fun mb(b: Long) = b / 1_048_576
}
