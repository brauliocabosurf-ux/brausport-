package com.brau.sportsplus

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.brau.sportsplus.core.Limits
import com.brau.sportsplus.core.player.FailoverAction
import com.brau.sportsplus.core.player.FailoverController
import com.brau.sportsplus.core.player.RecoveryTimers
import com.brau.sportsplus.core.player.ScheduledTask
import com.brau.sportsplus.core.playlist.Catalog
import com.brau.sportsplus.core.playlist.Channel
import com.brau.sportsplus.core.playlist.ChannelFilters
import com.brau.sportsplus.net.NetInfo
import com.brau.sportsplus.player.SafeMediaSources
import kotlinx.coroutines.launch

/**
 * Reproductor con vista dividida (video ~70 % + lista) y pantalla completa.
 *
 * Ciclo de vida:
 * - Una sola instancia de ExoPlayer: se crea en onStart y se libera en onStop.
 * - Al liberar se desvincula de PlayerView y se cancelan todos los callbacks pendientes.
 * - La lista se reconstruye con categoría + búsqueda + clave del canal. Si el proceso murió,
 *   se lee la lista guardada en disco.
 * - Reintentos acotados por [FailoverController] (intentos y plazo absoluto por episodio) y
 *   programados con [RecoveryTimers]. Cada entrada en buffering abre un episodio si no hay uno
 *   activo; solo se cierra tras reproducción estable con avance real. Al agotarse se detiene la
 *   carga y aparece "Reintentar" (enfocable con el mando).
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KEY = "channel_key"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_QUERY = "query"
        const val EXTRA_TITLE = "title"

        private const val INFO_HIDE_MS = 4_000L
        private const val FOCUS_OFFSET_PX = 80

        /** Muchos enlaces IPTV no dicen si son HLS; solo se fuerza HLS si la URL lo indica. */
        private fun looksLikeHls(url: String) = url.contains(".m3u8", ignoreCase = true)
    }

    private lateinit var playerView: PlayerView
    private lateinit var info: TextView
    private lateinit var errorText: TextView
    private lateinit var loading: ProgressBar
    private lateinit var store: Store
    private lateinit var sidePanel: View
    private lateinit var sideList: RecyclerView
    private lateinit var sideHeader: TextView
    private lateinit var retryButton: Button
    private lateinit var mediaSources: SafeMediaSources

    private var sideAdapter: SideAdapter? = null
    /** false = vista dividida (video + lista), true = video a pantalla completa. */
    private var fullscreen = false
    private var started = false

    private var player: ExoPlayer? = null
    private var list: List<Channel> = emptyList()
    private var index = 0
    /** Presupuesto de reintentos del canal actual (ver core/player/FailoverController). */
    private var failover: FailoverController? = null
    private val sourceIndex get() = failover?.source ?: 0

    private val channel get() = list[index]
    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { info.visibility = View.GONE }
    /**
     * Watchdog de carga y plazo absoluto del episodio (ver core/player/RecoveryTimers). Cada cambio
     * de canal, reintento manual o salida abre una época nueva: callbacks viejos se descartan.
     */
    private val timers = RecoveryTimers(
        scheduler = { delay, task ->
            val r = Runnable(task)
            handler.postDelayed(r, delay)
            ScheduledTask { handler.removeCallbacks(r) }
        },
        clock = SystemClock::uptimeMillis, // mismo reloj que usa Handler para los retardos
        stallMs = Limits.STALL_TIMEOUT_MS,
    )
    private val progressCheck = object : Runnable {
        override fun run() {
            val p = player ?: return
            val f = failover
            if (p.isPlaying && f != null) {
                f.onPlaybackProgress(p.currentPosition)
                if (f.deadline == null) timers.armDeadline(null) {} // reproducción estable: fin del episodio
            }
            handler.postDelayed(this, Limits.PROGRESS_CHECK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        store = Store(this)
        mediaSources = SafeMediaSources(NetInfo.client, NetInfo.policy)
        playerView = findViewById(R.id.playerView)
        info = findViewById(R.id.info)
        errorText = findViewById(R.id.error)
        loading = findViewById(R.id.loading)
        retryButton = findViewById<Button>(R.id.retryButton).apply { setOnClickListener { manualRetry() } }
        sidePanel = findViewById(R.id.sidePanel)
        sideHeader = findViewById(R.id.sideHeader)
        sideList = findViewById<RecyclerView>(R.id.sideList).apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity)
            itemAnimator = null
        }
        playerView.useController = false
        playerView.keepScreenOn = true

        // Atrás: de pantalla completa regresa a la vista dividida; de ahí, a la lista principal.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) setFullscreen(false) else finish()
            }
        })

        val key = savedInstanceState?.getString(EXTRA_KEY) ?: intent.getStringExtra(EXTRA_KEY)
        val source = savedInstanceState?.getInt(EXTRA_SOURCE) ?: intent.getIntExtra(EXTRA_SOURCE, 0)
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            // Lista confirmada en memoria o, si Android mató el proceso, la guardada en disco.
            // Sin turno: nunca reemplaza una lista más reciente.
            val all = AppState.repository(this@PlayerActivity).loadStored()?.result?.channels.orEmpty()
            val filtered = ChannelFilters.apply(
                all,
                intent.getStringExtra(EXTRA_CATEGORY) ?: ChannelFilters.ALL,
                intent.getStringExtra(EXTRA_QUERY).orEmpty(),
                store.favorites(),
            )
            val i = filtered.indexOfFirst { it.key == key }
            if (i < 0) {
                finish()
                return@launch
            }
            onListReady(filtered, i, source)
        }
    }

    private fun onListReady(channels: List<Channel>, startIndex: Int, startSource: Int) {
        list = channels
        index = startIndex
        newFailover(startSource)
        sideHeader.text = getString(R.string.side_header, intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.channels), list.size)
        sideAdapter = SideAdapter(list, ::onSideClick).also { sideList.adapter = it }
        setFullscreen(false)
        if (started) startPlayer()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (list.isNotEmpty()) {
            outState.putString(EXTRA_KEY, channel.key)
            outState.putInt(EXTRA_SOURCE, sourceIndex)
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (list.isNotEmpty()) startPlayer()
    }

    override fun onStop() {
        super.onStop()
        started = false
        releasePlayer()
    }

    private fun startPlayer() {
        if (player != null) return // nunca dos instancias
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                Limits.PLAYER_MIN_BUFFER_MS,
                Limits.PLAYER_MAX_BUFFER_MS,
                Limits.PLAYER_START_BUFFER_MS,
                Limits.PLAYER_REBUFFER_MS,
            )
            .setTargetBufferBytes(Limits.PLAYER_TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()
            .also {
                it.addListener(listener)
                playerView.player = it
            }
        timers.reset()
        failover?.restart() // al volver a la app, presupuesto y plazo nuevos
        playCurrent()
    }

    private fun releasePlayer() {
        timers.reset()
        handler.removeCallbacksAndMessages(null)
        val p = player ?: return
        player = null
        playerView.player = null
        p.removeListener(listener)
        p.release()
    }

    private fun onSideClick(position: Int) {
        if (position !in list.indices) return
        if (position == index) {
            setFullscreen(true)
        } else {
            index = position
            newFailover(0)
            playCurrent()
        }
    }

    private fun newFailover(source: Int) {
        timers.reset() // cancela temporizadores del canal anterior
        failover = FailoverController(channel.sources.size, source, SystemClock::uptimeMillis)
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        sidePanel.visibility = if (on) View.GONE else View.VISIBLE
        if (!on && list.isNotEmpty()) focusRow(index)
        if (player != null) showInfo()
    }

    private fun focusRow(position: Int) {
        (sideList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, FOCUS_OFFSET_PX)
        sideList.post { sideList.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
    }

    private fun playCurrent() {
        val p = player ?: return
        val f = failover ?: return
        val ch = channel
        val source = ch.sources[f.source]
        store.lastChannelKey = ch.key
        sideAdapter?.playing = index
        hideError()
        loading.visibility = View.VISIBLE
        timers.onNotBuffering()
        armDeadline(f)

        // Si falla como video directo, el controlador pide reintentarlo como HLS (triedAsHls).
        val asHls = f.triedAsHls || looksLikeHls(source.url)
        val mediaSource = mediaSources.create(source.url, asHls)
        if (mediaSource == null) {
            apply(f.onSourceBlocked(), blocked = true)
            return
        }
        p.setMediaSource(mediaSource)
        p.prepare()
        p.playWhenReady = true
        showInfo()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val f = failover ?: return
            when (state) {
                Player.STATE_BUFFERING -> {
                    loading.visibility = View.VISIBLE
                    // Tras una reproducción estable no hay episodio: el plazo de 120 s empieza YA.
                    // Si ya había uno, se conserva su fecha límite (rearmar al mismo instante absoluto).
                    f.beginRecoveryIfNeeded()
                    armDeadline(f)
                    timers.onBuffering { if (failover === f) apply(f.onStall()) }
                }
                else -> {
                    timers.onNotBuffering()
                    loading.visibility = View.GONE
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(progressCheck)
            if (isPlaying) handler.post(progressCheck) else failover?.onNotPlaying()
        }

        override fun onPlayerError(error: PlaybackException) {
            val f = failover ?: return
            val url = channel.sources[f.source].url
            val action = f.onError(
                behindLiveWindow = error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
                explicitHls = looksLikeHls(url),
            )
            apply(action, errorCode = error.errorCodeName)
        }
    }

    /** Ejecuta la decisión del presupuesto de reintentos. */
    private fun apply(action: FailoverAction, errorCode: String? = null, blocked: Boolean = false) {
        val p = player ?: return
        failover?.let { armDeadline(it) }
        when (action) {
            FailoverAction.SeekToLive -> {
                p.seekToDefaultPosition()
                p.prepare()
            }
            FailoverAction.RetryAsHls -> playCurrent()
            is FailoverAction.SwitchSource -> {
                toast(getString(R.string.source_failed, channel.sources[action.index].label))
                playCurrent()
            }
            FailoverAction.GiveUp -> giveUp(errorCode, blocked)
        }
    }

    /** Sin más intentos: se detiene la carga (cierra conexiones) y se ofrece reintento manual. */
    private fun giveUp(errorCode: String?, blocked: Boolean) {
        timers.cancelAll()
        handler.removeCallbacks(progressCheck)
        player?.stop()
        loading.visibility = View.GONE
        val code = when {
            blocked -> getString(R.string.source_blocked)
            errorCode != null -> errorCode
            else -> getString(R.string.no_response)
        }
        errorText.text = getString(R.string.channel_error, channel.name, code)
        errorText.visibility = View.VISIBLE
        info.visibility = View.GONE
        retryButton.visibility = View.VISIBLE
        retryButton.requestFocus()
    }

    private fun hideError() {
        errorText.visibility = View.GONE
        retryButton.visibility = View.GONE
    }

    /**
     * Programa el vencimiento ABSOLUTO del episodio. Los cambios automáticos de fuente no lo mueven
     * (el controlador conserva el inicio del episodio). Comprueba que el callback sea del mismo
     * controlador para que nunca detenga un canal distinto.
     */
    private fun armDeadline(f: FailoverController) {
        timers.armDeadline(f.deadline) {
            if (failover === f) f.onDeadline()?.let { apply(it) }
        }
    }

    /** Reintento manual (botón o OK): presupuesto y plazo completos desde la fuente actual. */
    private fun manualRetry() {
        val f = failover ?: return
        timers.reset()
        f.restart()
        playCurrent()
        if (!fullscreen) focusRow(index)
    }

    private fun zap(delta: Int) {
        if (list.isEmpty()) return
        index = (index + delta).mod(list.size)
        newFailover(0)
        playCurrent()
        if (!fullscreen) focusRow(index)
    }

    private fun switchSource(delta: Int) {
        if (list.isEmpty()) return
        val count = channel.sources.size
        if (count < 2) {
            toast(getString(R.string.single_source))
            return
        }
        timers.reset()
        failover?.restart((sourceIndex + delta).mod(count))
        playCurrent()
    }

    private fun showInfo() {
        if (list.isEmpty()) return
        val ch = channel
        val where = if (ch.isEvent) "${Catalog.LIVE} · ${ch.sport.orEmpty()}" else ch.section
        val src = if (ch.sources.size > 1) {
            "\n" + getString(R.string.source_line, sourceIndex + 1, ch.sources.size, ch.sources[sourceIndex].label)
        } else ""
        info.text = "${index + 1}/${list.size}   ${ch.name}\n$where$src"
        info.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        // En vista dividida el cartel se queda fijo; en pantalla completa se oculta solo.
        if (fullscreen) handler.postDelayed(hideInfo, INFO_HIDE_MS)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // ◀ ▶ siempre cambian de fuente; CH+/CH- siempre cambian de canal.
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> { switchSource(+1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { switchSource(-1); return true }
            KeyEvent.KEYCODE_CHANNEL_UP -> { zap(+1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
        }
        // En vista dividida ▲ ▼ y OK los maneja la lista de la derecha.
        if (!fullscreen) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { zap(+1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { zap(-1); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (retryButton.visibility == View.VISIBLE) manualRetry() else setFullscreen(false)
                true
            }
            KeyEvent.KEYCODE_INFO -> { showInfo(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
