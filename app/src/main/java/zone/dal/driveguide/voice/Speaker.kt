package zone.dal.driveguide.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Pleos TextToSpeech SDK로 한국어 안내를 읽는다.
 *
 * Pleos 차량·에뮬레이터의 음성 엔진(ai.pleos.playground.caas)은 안드로이드 기본 TextToSpeech가 찾지 못하므로
 * Pleos SDK를 쓴다. 앱 등록 없이 쓸 수 있도록 기기 안 엔진(ON_DEVICE)만 쓴다.
 * SDK 호출은 메인 스레드를 막지 않도록 전용 스레드에서 하고, 초기화 전에 들어온 마지막 안내는 준비되면 읽는다.
 */
class Speaker(context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)

    /**
     * 읽는 중인 안내 하나. [onStarted]는 소리가 나기 시작할 때, [onEnded]는 끝날 때 메인 스레드에서 한 번씩 부른다.
     * [onEnded]의 인자는 끝까지 읽었으면 true, 멈췄거나 다른 안내에 끊겼거나 오류면 false.
     * SDK 이벤트에는 어느 안내인지 표시가 없어서, 새 안내를 시작하며 앞의 것을 멈출 때 오는 onStop을
     * 새 안내의 끝으로 오해하지 않도록 onStart를 받은 뒤의 끝 이벤트만 센다.
     */
    private class Utterance(val onStarted: (() -> Unit)?, val onEnded: ((completed: Boolean) -> Unit)?) {
        var started = false
    }

    // 아래 상태는 executor 스레드에서만 읽고 쓴다.
    private var ready = false
    private var released = false
    private var current: Utterance? = null
    private var pending: Pair<String, Utterance>? = null

    private val events = object : EventListener {
        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onStart() = onWorker {
            current?.takeUnless { it.started }?.let { u ->
                u.started = true
                u.onStarted?.let { callback -> main.post { callback() } }
            }
        }
        override fun onDone() = finishIfStarted(completed = true)
        override fun onStop() = finishIfStarted(completed = false)
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "TTS error: $errorType")
            onWorker { current?.let { finish(it, completed = false) } }
        }
    }

    init {
        executor.execute {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    executor.execute {
                        if (released) return@execute
                        tts.setLocale(Locale.KOREA)
                        tts.addEventListener(events)
                        ready = true
                        pending?.let { (text, u) -> say(text, u) }
                        pending = null
                    }
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "TTS init failed: $errorType")
                }
            })
        }
    }

    /** 앞의 안내를 끊고 [text]를 읽는다. 소리가 나기 시작하면 [onStarted], 끝나거나 끊기면 [onEnded]를 부른다. */
    fun speak(
        text: String,
        onStarted: (() -> Unit)? = null,
        onEnded: ((completed: Boolean) -> Unit)? = null,
    ) {
        val u = Utterance(onStarted, onEnded)
        executor.execute {
            when {
                released -> Unit
                ready -> say(text, u)
                else -> {
                    pending?.second?.let { notifyEnded(it, completed = false) }
                    pending = text to u
                }
            }
        }
    }

    /** 읽던 안내를 멈춘다. */
    fun stop() {
        executor.execute {
            current?.let { finish(it, completed = false) }
            pending?.second?.let { notifyEnded(it, completed = false) }
            pending = null
            if (ready && !released) tts.stop()
        }
    }

    fun release() {
        executor.execute {
            released = true
            if (ready) {
                tts.stop()
                tts.removeEventListener(events)
            }
            tts.release()
        }
        executor.shutdown()
    }

    private fun say(text: String, u: Utterance) {
        // 읽던 것이 있을 때만 멈춘다. 끝난 뒤 바로 다음 문장을 읽을 때 불필요한 멈춤으로 늦어지지 않게.
        current?.let {
            finish(it, completed = false)
            tts.stop()
        }
        current = u
        tts.speak(text, true)
    }

    private fun finishIfStarted(completed: Boolean) =
        onWorker { current?.takeIf { it.started }?.let { finish(it, completed) } }

    /** SDK 이벤트(바인더 스레드)를 executor로 넘긴다. release 뒤에 늦게 온 이벤트는 버린다. */
    private fun onWorker(block: () -> Unit) {
        try {
            executor.execute(block)
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun finish(u: Utterance, completed: Boolean) {
        if (current === u) current = null
        notifyEnded(u, completed)
    }

    private fun notifyEnded(u: Utterance, completed: Boolean) {
        u.onEnded?.let { callback -> main.post { callback(completed) } }
    }

    private companion object {
        const val TAG = "Speaker"
    }
}
