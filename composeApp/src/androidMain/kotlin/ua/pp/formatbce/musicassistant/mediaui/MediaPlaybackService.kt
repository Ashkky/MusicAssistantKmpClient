package ua.pp.formatbce.musicassistant.mediaui

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.media.MediaBrowserServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import ua.pp.formatbce.musicassistant.data.source.PlayerData
import ua.pp.formatbce.musicassistant.data.source.ServiceDataSource
import ua.pp.formatbce.musicassistant.ui.compose.main.PlayerAction

class MediaPlaybackService : MediaBrowserServiceCompat() {
    private val scope = CoroutineScope(Dispatchers.IO)

    private lateinit var mediaSessionHelper: MediaSessionHelper
    private lateinit var mediaNotificationManager: MediaNotificationManager

    private val dataSource: ServiceDataSource by inject()
    private val players = dataSource.playersData
        .map { list -> list.filter { it.queue?.currentItem != null } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val activePlayerIndex = MutableStateFlow(0)
    private val currentPlayerData =
        combine(players, activePlayerIndex) { players, index ->
            players.getOrNull(index) ?: players.getOrNull(0)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    override fun onCreate() {
        super.onCreate()
        mediaSessionHelper =
            MediaSessionHelper(this, createCallback())
        mediaNotificationManager = MediaNotificationManager(this, mediaSessionHelper)
        sessionToken = mediaSessionHelper.getSessionToken()
        scope.launch { currentPlayerData.collect { updatePlaybackState(it, players.value.size > 1) } }
    }

    private fun createCallback(): MediaSessionCompat.Callback =
        object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                currentPlayerData.value?.let {
                    dataSource.playerAction(it, PlayerAction.TogglePlayPause)
                }
            }

            override fun onPause() {
                currentPlayerData.value?.let {
                    dataSource.playerAction(it, PlayerAction.TogglePlayPause)
                }
            }

            override fun onSkipToNext() {
                currentPlayerData.value?.let { dataSource.playerAction(it, PlayerAction.Next) }
            }

            override fun onSkipToPrevious() {
                currentPlayerData.value?.let {
                    dataSource.playerAction(it, PlayerAction.Previous)
                }
            }

            override fun onCustomAction(action: String, extras: Bundle?) {
                when (action) {
                    "ACTION_SWITCH_PLAYER" -> switchPlayer()
                    "ACTION_TOGGLE_SHUFFLE" -> currentPlayerData.value?.let { playerData ->
                        playerData.queue?.let {
                            dataSource.playerAction(
                                playerData,
                                PlayerAction.ToggleShuffle(current = it.shuffleEnabled)
                            )
                        }
                    }

                    "ACTION_TOGGLE_REPEAT" -> currentPlayerData.value?.let { playerData ->
                        playerData.queue?.let {
                            dataSource.playerAction(
                                playerData,
                                PlayerAction.ToggleRepeatMode(current = it.repeatMode)
                            )
                        }
                    }
                }
            }
        }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onGetRoot(p0: String, p1: Int, p2: Bundle?): BrowserRoot? = null

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
    }

    private fun switchPlayer() {
        activePlayerIndex.update {
            if (players.value.size > 1) {
                (it + 1) % players.value.size
            } else 0
        }
    }

    private fun updatePlaybackState(player: PlayerData?, showPlayersSwitch: Boolean) {
        mediaSessionHelper.updatePlaybackState(player, showPlayersSwitch)
        val notification =
            mediaNotificationManager.createNotification(player)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                MediaNotificationManager.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(MediaNotificationManager.NOTIFICATION_ID, notification)
        }
    }
}
