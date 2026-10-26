package com.brau.sportsplus

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.brau.sportsplus.core.load.PlaylistRepository
import com.brau.sportsplus.core.net.UrlCheck
import com.brau.sportsplus.core.net.UrlPolicy
import com.brau.sportsplus.core.net.UrlRejection
import com.brau.sportsplus.core.playlist.Catalog
import com.brau.sportsplus.core.playlist.CatalogResult
import com.brau.sportsplus.core.playlist.Channel
import com.brau.sportsplus.core.playlist.ChannelFilters
import com.brau.sportsplus.core.playlist.ChannelFilters.ALL
import com.brau.sportsplus.core.playlist.ChannelFilters.FAVORITES
import com.brau.sportsplus.core.playlist.ChannelFilters.SPORT_PREFIX
import com.brau.sportsplus.core.playlist.LoadError
import com.brau.sportsplus.core.playlist.PlaylistException
import com.brau.sportsplus.net.NetInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var repository: PlaylistRepository
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var channelList: RecyclerView
    private lateinit var header: TextView
    private lateinit var emptyText: TextView
    private lateinit var search: EditText

    private var all: List<Channel> = emptyList()
    private var visible: List<Channel> = emptyList()
    private var currentCategory = FAVORITES
    private var autoPlayDone = false
    private var focusedOnce = false

    /** Solo una actualización activa: la nueva cancela la anterior. */
    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)
        repository = AppState.repository(this)
        autoPlayDone = savedInstanceState != null

        header = findViewById(R.id.header)
        emptyText = findViewById(R.id.empty)
        search = findViewById(R.id.search)

        categoryAdapter = CategoryAdapter(::selectCategory)
        findViewById<RecyclerView>(R.id.categories).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = categoryAdapter
            itemAnimator = null // sin animación para no perder el foco del control
        }

        channelAdapter = ChannelAdapter(::play, ::toggleFavorite)
        channelList = findViewById<RecyclerView>(R.id.channels).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            adapter = channelAdapter
            itemAnimator = null
        }

        findViewById<Button>(R.id.btnReload).setOnClickListener {
            repository.committed.value?.url?.let { startOperation(it) }
        }
        findViewById<Button>(R.id.btnPlaylist).setOnClickListener { askPlaylistUrl() }
        search.doAfterTextChanged { applyFilter() }

        // La interfaz SOLO muestra la lista confirmada (la misma que está en disco).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.committed.collect { c -> if (c != null && c.result.channels !== all) setChannels(c.result.channels) }
            }
        }
        if (savedInstanceState == null || repository.committed.value == null) startOperation(url = null)
    }

    override fun onResume() {
        super.onResume()
        if (all.isNotEmpty()) channelAdapter.updateFavorites(store.favorites())
    }

    /**
     * Única puerta para cargar listas. Cada llamada cancela la operación anterior y toma un turno
     * nuevo del repositorio; solo el turno vigente puede mostrar o guardar algo.
     *  - [url] == null: arranque. Lee la lista guardada y, si el turno sigue vigente, la actualiza.
     *  - [url] != null: cambio de lista o recarga.
     * Si falla, se conserva la lista vigente (en pantalla y en disco).
     */
    private fun startOperation(url: String?) {
        refreshJob?.cancel()
        header.text = getString(R.string.loading)
        refreshJob = lifecycleScope.launch {
            val token = repository.begin()
            try {
                val target = url ?: run {
                    val stored = repository.loadStored(token)
                    stored?.url ?: store.legacyPlaylistUrl
                }
                if (target == null) {
                    if (repository.isCurrent(token)) {
                        updateHeader()
                        askPlaylistUrl()
                    }
                    return@launch
                }
                val committed = repository.refresh(target, token)
                store.clearLegacyPlaylistUrl()
                if (repository.isCurrent(token)) toast(summary(committed.result))
            } catch (e: CancellationException) {
                throw e // cancelada u obsoleta: no es un error y no cambió nada
            } catch (e: PlaylistException) {
                if (repository.isCurrent(token)) {
                    toast(e.error.toMessage(this@MainActivity))
                    updateHeader()
                }
            } catch (e: Exception) {
                // Cualquier otro fallo: mensaje fijo, sin detalles; la lista vigente no cambia.
                if (repository.isCurrent(token)) {
                    toast(getString(R.string.err_unexpected))
                    updateHeader()
                }
            }
        }
    }

    private fun summary(result: CatalogResult): String {
        val live = result.channels.count { it.isEvent }
        var text = getString(R.string.loaded_summary, result.channels.size - live, live)
        if (result.blockedSources > 0) text += "\n" + getString(R.string.blocked_summary, result.blockedSources)
        if (result.truncated) text += "\n" + getString(R.string.truncated_summary)
        return text
    }

    private fun setChannels(list: List<Channel>) {
        all = list
        val names = categoryItems().map { it.first }
        if (currentCategory !in names) currentCategory = ALL
        if (currentCategory == FAVORITES && favoriteChannels().isEmpty()) {
            currentCategory = if (all.any { it.isEvent }) Catalog.LIVE else ALL
        }
        refreshCategories()
        applyFilter()

        if (!focusedOnce) {
            focusedOnce = true
            channelList.post { channelList.getChildAt(0)?.requestFocus() }
        }
        maybeAutoPlay()
    }

    /** Favoritos, En vivo (con sus deportes), Todos y luego las secciones con canales. */
    private fun categoryItems(): List<Pair<String, Int>> {
        val items = mutableListOf(FAVORITES to favoriteChannels().size)
        val events = all.filter { it.isEvent }
        if (events.isNotEmpty()) {
            items += Catalog.LIVE to events.size
            val sports = events.mapNotNull { it.sport }.distinct()
                .sortedBy { Catalog.SPORT_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }
            sports.forEach { s -> items += (SPORT_PREFIX + s) to events.count { it.sport == s } }
        }
        items += ALL to all.size
        all.filter { !it.isEvent }.map { it.section }.distinct().forEach { sec ->
            items += sec to all.count { !it.isEvent && it.section == sec }
        }
        return items
    }

    private fun refreshCategories() = categoryAdapter.submit(categoryItems(), currentCategory)

    private fun favoriteChannels(): List<Channel> {
        val favs = store.favorites()
        return all.filter { it.key in favs }
    }

    private fun selectCategory(name: String) {
        if (name == currentCategory && search.text.isNullOrEmpty()) return
        currentCategory = name
        categoryAdapter.select(name)
        if (!search.text.isNullOrEmpty()) search.setText("") else applyFilter()
    }

    private fun applyFilter() {
        val query = search.text.toString().trim()
        val favs = store.favorites()
        visible = ChannelFilters.apply(all, currentCategory, query, favs)
        channelAdapter.submit(visible, favs)
        channelList.scrollToPosition(0)

        emptyText.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = when {
            all.isEmpty() -> getString(R.string.empty_no_list)
            query.isNotEmpty() -> getString(R.string.empty_search, query)
            currentCategory == FAVORITES -> getString(R.string.empty_favorites)
            else -> getString(R.string.empty_category)
        }
        updateHeader()
    }

    private fun currentTitle(): String =
        if (search.text.toString().isNotBlank()) getString(R.string.search_title) else currentCategory.removePrefix(SPORT_PREFIX)

    private fun updateHeader() {
        header.text = when {
            all.isEmpty() -> getString(R.string.app_name)
            else -> "${currentTitle()} · ${visible.size}"
        }
    }

    /** Partido con varias transmisiones: se elige cuál ver. Lo demás arranca directo. */
    private fun play(index: Int) {
        val ch = visible.getOrNull(index) ?: return
        if (ch.isEvent && ch.sources.size > 1) {
            AlertDialog.Builder(this)
                .setTitle(ch.name)
                .setItems(ch.sources.map { it.label }.toTypedArray()) { _, which -> openPlayer(ch, which) }
                .show()
        } else {
            openPlayer(ch, 0)
        }
    }

    /**
     * Al reproductor se le pasa CÓMO reconstruir su lista (categoría, búsqueda y canal),
     * no la lista en memoria: así funciona aunque Android haya matado el proceso.
     */
    private fun openPlayer(channel: Channel, source: Int, category: String = currentCategory, query: String = search.text.toString()) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_KEY, channel.key)
                .putExtra(PlayerActivity.EXTRA_SOURCE, source)
                .putExtra(PlayerActivity.EXTRA_CATEGORY, category)
                .putExtra(PlayerActivity.EXTRA_QUERY, query)
                .putExtra(PlayerActivity.EXTRA_TITLE, currentTitle()),
        )
    }

    /** Al abrir la app arranca directo en el último canal visto, como una tele. */
    private fun maybeAutoPlay() {
        if (autoPlayDone) return
        autoPlayDone = true
        val last = store.lastChannelKey ?: return
        val channel = all.firstOrNull { it.key == last } ?: return
        openPlayer(channel, 0, ALL, "")
    }

    private fun toggleFavorite(channel: Channel) {
        val added = store.toggleFavorite(channel.key)
        toast(getString(if (added) R.string.fav_added else R.string.fav_removed, channel.name))
        refreshCategories()
        if (currentCategory == FAVORITES) applyFilter() else channelAdapter.updateFavorites(store.favorites())
    }

    private fun askPlaylistUrl() {
        val input = EditText(this).apply {
            hint = getString(R.string.playlist_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        // Nunca se muestra la URL completa (puede llevar tokens): solo esquema y host.
        val current = repository.committed.value?.url?.let { getString(R.string.playlist_current, UrlPolicy.redact(it)) }.orEmpty()
        AlertDialog.Builder(this)
            .setTitle(R.string.playlist_title)
            .setMessage(getString(R.string.playlist_help) + current)
            .setView(box)
            .setPositiveButton(R.string.playlist_load) { _, _ ->
                val url = input.text.toString().trim()
                when (val check = NetInfo.policy.check(url)) {
                    is UrlCheck.Allowed -> startOperation(url)
                    is UrlCheck.Rejected -> toast(
                        (if (check.reason == UrlRejection.CLEARTEXT_NOT_ALLOWED) LoadError.CLEARTEXT_NOT_ALLOWED else LoadError.BLOCKED_URL)
                            .toMessage(this),
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
