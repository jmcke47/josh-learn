package com.josh.learn

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** On-device generation with LiteRT-LM. One engine kept loaded; one generation at a time. */
object LocalLlm {
    private lateinit var app: Context
    private val lock = Any()
    private var engine: Engine? = null
    private var engineKey: String? = null

    fun install(c: Context) {
        app = c.applicationContext
        AiClient.localBackend = { cfg, msgs, onDelta, cancelled -> complete(cfg, msgs, onDelta, cancelled) }
    }

    fun release() = synchronized(lock) {
        runCatching { engine?.close() }
        engine = null; engineKey = null
    }

    private fun engineFor(m: LocalModel, onDelta: ((String) -> Unit)?): Engine {
        val path = LocalModels.file(app, m).absolutePath
        val gpu = LocalModels.useGpu(app)
        val key = "$path|$gpu"
        engine?.let { if (engineKey == key) return it }
        release()
        onDelta?.invoke(AiClient.STATUS + "Loading the offline model (can take 10-30 s the first time)…")
        fun build(useGpu: Boolean) = Engine(EngineConfig(modelPath = path, backend = if (useGpu) Backend.GPU() else Backend.CPU(),
            cacheDir = java.io.File(app.cacheDir, "litertlm").apply { mkdirs() }.absolutePath)).also { it.initialize() }
        val e = try { build(gpu) } catch (t: Throwable) {
            if (!gpu) throw t
            onDelta?.invoke(AiClient.STATUS + "GPU not available, using CPU…")
            build(false)
        }
        engine = e; engineKey = key
        return e
    }

    fun complete(cfg: ProviderConfig, messages: List<Msg>, onDelta: ((String) -> Unit)?, isCancelled: () -> Boolean): String = synchronized(lock) {
        val m = LocalModel.byId(cfg.model)?.takeIf { LocalModels.isInstalled(app, it) }
            ?: throw AiException(AiException.Kind.LOCAL, "The offline model isn't downloaded yet. Download it in Settings → Offline AI.")
        try {
            val e = engineFor(m, onDelta)
            onDelta?.invoke(AiClient.STATUS + "Thinking on your phone…")
            val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
            val turns = messages.filter { it.role != "system" }
            val last = turns.lastOrNull { it.role == "user" } ?: throw AiException(AiException.Kind.LOCAL, "Nothing to answer.")
            val history = turns.subList(0, turns.lastIndexOf(last)).map { if (it.role == "assistant") Message.model(it.content) else Message.user(it.content) }
            val conv = e.createConversation(ConversationConfig(
                systemInstruction = if (system.isNotEmpty()) Contents.of(system) else null,
                initialMessages = history,
                samplerConfig = SamplerConfig(40, 0.95, 0.6, 0),
                maxOutputToken = 1024,
                thinkingConfig = ThinkingConfig(false),
            ))
            try {
                val acc = StringBuilder()
                val done = CountDownLatch(1)
                val error = AtomicReference<Throwable?>(null)
                conv.sendMessageAsync(Message.user(last.content), object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val t = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                        if (t.isEmpty()) return
                        // Treat as delta; tolerate engines that resend the cumulative text.
                        val delta = synchronized(acc) {
                            val cur = acc.toString()
                            val d = if (cur.isNotEmpty() && t.length > cur.length && t.startsWith(cur)) t.substring(cur.length) else t
                            acc.append(d); d
                        }
                        onDelta?.invoke(delta)
                    }
                    override fun onDone() { done.countDown() }
                    override fun onError(throwable: Throwable) { error.set(throwable); done.countDown() }
                })
                while (!done.await(200, TimeUnit.MILLISECONDS)) {
                    if (isCancelled()) { runCatching { conv.cancelProcess() }; done.await(3, TimeUnit.SECONDS); throw AiException(AiException.Kind.CANCELLED, "Cancelled") }
                }
                error.get()?.let { throw it }
                val out = Prompts.stripThinking(synchronized(acc) { acc.toString() })
                if (out.isBlank()) throw AiException(AiException.Kind.PARSE, "The offline model returned nothing. Try again.")
                out
            } finally { runCatching { conv.close() } }
        } catch (e: AiException) { throw e
        } catch (e: UnsatisfiedLinkError) { throw AiException(AiException.Kind.LOCAL, "Offline AI needs a 64-bit ARM phone. Use an online provider instead.", cause = e)
        } catch (e: OutOfMemoryError) { release(); throw AiException(AiException.Kind.LOCAL, "Not enough memory for this model. Try the Fast model, or close other apps.", cause = e)
        } catch (e: Throwable) {
            release()
            throw AiException(AiException.Kind.LOCAL, "Offline model error: ${e.message ?: e.javaClass.simpleName}. If this keeps happening, try the Fast model or an online provider.", cause = e)
        }
    }
}
