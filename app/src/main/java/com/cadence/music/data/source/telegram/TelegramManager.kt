package com.cadence.music.data.source.telegram

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface TelegramAuthState {
    data object Uninitialized : TelegramAuthState
    data object WaitParameters : TelegramAuthState
    data object WaitPhoneNumber : TelegramAuthState
    data class WaitCode(val phone: String) : TelegramAuthState
    data class WaitPassword(val hint: String?) : TelegramAuthState
    data object Ready : TelegramAuthState
    data class Error(val message: String) : TelegramAuthState
}

data class TelegramChatItem(
    val id: Long,
    val title: String,
    val typeName: String,
    val isSavedMessages: Boolean = false,
)

class TelegramManager private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Uninitialized)
    val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()
    private val _connectionState = MutableStateFlow<String>("Connecting...")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    @Volatile private var client: Client? = null
    private val started = AtomicBoolean(false)
    private var pendingPhone: String = ""
    @Volatile private var cachedUserId: Long? = null
    val myUserId: Long? get() = cachedUserId

    // In-memory cache of resolved files and thumbnails
    private val remoteFileMap = ConcurrentHashMap<String, Int>()

    val isLibraryAvailable: Boolean by lazy {
        runCatching {
            System.loadLibrary("tdjni")
            true
        }.onFailure {
            Log.w(TAG, "TDLib native library (tdjni) could not be loaded: ${it.message}")
        }.getOrDefault(false)
    }

    fun isReady(): Boolean = _authState.value is TelegramAuthState.Ready

    @Synchronized
    fun start() {
        if (!isLibraryAvailable) {
            _authState.value = TelegramAuthState.Error("TDLib native binaries are not supported on this platform")
            return
        }
        if (started.getAndSet(true)) return

        try {
            Client.execute(TdApi.SetLogVerbosityLevel(1))
            client = Client.create(
                { update -> onUpdate(update) },
                { exception ->
                    Log.e(TAG, "TDLib uncaught exception: ${exception.message}", exception)
                    _authState.value = TelegramAuthState.Error(exception.message ?: "TDLib error")
                },
                null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TDLib client: ${e.message}", e)
            _authState.value = TelegramAuthState.Error(e.message ?: "Failed to start Telegram client")
        }
    }

    @Synchronized
    fun restart() {
        started.set(false)
        runCatching { client?.send(TdApi.Close(), null) }
        client = null
        _authState.value = TelegramAuthState.Uninitialized
        start()
    }

    private fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> {
                handleAuthState(update.authorizationState)
            }
            is TdApi.UpdateConnectionState -> {
                val stateName = when (update.state) {
                    is TdApi.ConnectionStateWaitingForNetwork -> "Waiting for network..."
                    is TdApi.ConnectionStateConnectingToProxy -> "Connecting to proxy..."
                    is TdApi.ConnectionStateConnecting -> "Connecting to Telegram..."
                    is TdApi.ConnectionStateUpdating -> "Updating..."
                    is TdApi.ConnectionStateReady -> "Connected"
                    else -> "Connecting..."
                }
                _connectionState.value = stateName
            }
            is TdApi.UpdateFile -> {
                // Background download progress
            }
        }
    }

    private fun handleAuthState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                scope.launch {
                    val dbDir = File(appContext.filesDir, "tdlib_db").apply { mkdirs() }
                    val filesDir = File(appContext.cacheDir, "tdlib_files").apply { mkdirs() }
                    val params = TdApi.SetTdlibParameters().apply {
                        useTestDc = false
                        databaseDirectory = dbDir.absolutePath
                        filesDirectory = filesDir.absolutePath
                        databaseEncryptionKey = byteArrayOf()
                        useFileDatabase = true
                        useChatInfoDatabase = true
                        useMessageDatabase = true
                        useSecretChats = false
                        // Official Telegram Android app API credentials (safe and standard for open source clients)
                        apiId = 94575
                        apiHash = "a3406de8d171bb422bb6ddf3bbd800e2"
                        systemLanguageCode = "en"
                        deviceModel = "Android " + Build.MODEL
                        systemVersion = Build.VERSION.RELEASE ?: "Unknown"
                        applicationVersion = "0.14.1"
                    }
                    runCatching {
                        send(params)
                    }.onFailure {
                        _authState.value = TelegramAuthState.Error("Failed to set TDLib parameters: ${it.message}")
                    }
                }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                _authState.value = TelegramAuthState.WaitPhoneNumber
            }
            is TdApi.AuthorizationStateWaitCode -> {
                _authState.value = TelegramAuthState.WaitCode(pendingPhone)
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                _authState.value = TelegramAuthState.WaitPassword(state.passwordHint)
            }
            is TdApi.AuthorizationStateReady -> {
                scope.launch {
                    val me = runCatching { send(TdApi.GetMe()) }.getOrNull()
                    cachedUserId = me?.id
                }
                _authState.value = TelegramAuthState.Ready
            }
            is TdApi.AuthorizationStateLoggingOut -> {
                _authState.value = TelegramAuthState.Uninitialized
            }
            is TdApi.AuthorizationStateClosed -> {
                started.set(false)
                client = null
                _authState.value = TelegramAuthState.Uninitialized
            }
        }
    }

    suspend fun <R : TdApi.Object> send(query: TdApi.Function<R>, timeoutMs: Long = 20_000): R =
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val c = client ?: run {
                    cont.resumeWithException(IllegalStateException("TDLib client not initialized"))
                    return@suspendCancellableCoroutine
                }
                c.send(query, { result ->
                    if (!cont.isActive) return@send
                    if (result is TdApi.Error) {
                        cont.resumeWithException(IOException(result.message ?: "Telegram error code ${result.code}"))
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        cont.resume(result as R)
                    }
                })
            }
        }

    suspend fun sendPhoneNumber(phone: String) {
        val trimmed = phone.trim().replace(" ", "").replace("-", "")
        val formatted = if (trimmed.startsWith("+")) trimmed else "+$trimmed"
        pendingPhone = formatted

        start()

        // Wait up to 12s for TDLib parameters to be applied and state to reach WaitPhoneNumber
        val reached = withTimeoutOrNull(12_000) {
            authState.first { state ->
                state is TelegramAuthState.WaitPhoneNumber || state is TelegramAuthState.Ready || state is TelegramAuthState.Error
            }
        }

        if (reached == null) {
            val conn = _connectionState.value
            throw IOException("Telegram connection timed out ($conn). If Telegram is restricted in your region, please connect via VPN or set a proxy.")
        }

        val current = authState.value
        if (current is TelegramAuthState.Error) {
            throw IOException(current.message)
        }
        if (current is TelegramAuthState.Ready) {
            return
        }

        val settings = TdApi.PhoneNumberAuthenticationSettings().apply {
            allowFlashCall = false
            allowMissedCall = false
            isCurrentPhoneNumber = false
            allowSmsRetrieverApi = false
        }
        send(TdApi.SetAuthenticationPhoneNumber(formatted, settings), timeoutMs = 25_000)
    }

    suspend fun sendAuthCode(code: String) {
        send(TdApi.CheckAuthenticationCode(code.trim()), timeoutMs = 25_000)
    }

    suspend fun sendPassword(password: String) {
        send(TdApi.CheckAuthenticationPassword(password), timeoutMs = 25_000)
    }

    suspend fun sendBotToken(token: String) {
        val trimmed = token.trim()
        start()
        val reached = withTimeoutOrNull(12_000) {
            authState.first { state ->
                state is TelegramAuthState.WaitPhoneNumber || state is TelegramAuthState.Ready || state is TelegramAuthState.Error
            }
        }
        if (reached == null) {
            val conn = _connectionState.value
            throw IOException("Telegram connection timed out ($conn). Please check your internet or VPN.")
        }
        val current = authState.value
        if (current is TelegramAuthState.Error) throw IOException(current.message)
        if (current is TelegramAuthState.Ready) return
        send(TdApi.CheckAuthenticationBotToken(trimmed), timeoutMs = 25_000)
    }

    suspend fun addSocks5Proxy(server: String, port: Int, username: String = "", password: String = "") {
        val proxyType = TdApi.ProxyTypeSocks5(username, password)
        val proxy = TdApi.Proxy(server.trim(), port, proxyType)
        send(TdApi.AddProxy(proxy, true, "Cadence SOCKS5"), timeoutMs = 10_000)
    }

    suspend fun addMtprotoProxy(server: String, port: Int, secret: String) {
        val proxyType = TdApi.ProxyTypeMtproto(secret.trim())
        val proxy = TdApi.Proxy(server.trim(), port, proxyType)
        send(TdApi.AddProxy(proxy, true, "Cadence MTProto"), timeoutMs = 10_000)
    }

    suspend fun disableProxy() {
        runCatching { send(TdApi.DisableProxy(), timeoutMs = 5_000) }
    }

    private val chatTitleCache = ConcurrentHashMap<Long, String>()

    suspend fun getMe(): TdApi.User? {
        val me = runCatching { send(TdApi.GetMe()) }.getOrNull()
        if (me != null) cachedUserId = me.id
        return me
    }

    suspend fun getMyUserId(): Long? = cachedUserId ?: getMe()?.id

    suspend fun getChat(chatId: Long): TdApi.Chat? =
        runCatching { send(TdApi.GetChat(chatId)) }.getOrNull()

    suspend fun getChatTitle(chatId: Long): String? {
        chatTitleCache[chatId]?.let { return it }
        val chat = getChat(chatId)
        if (chat != null) {
            val title = chat.title.ifBlank { "Chat $chatId" }
            chatTitleCache[chatId] = title
            return title
        }
        return null
    }

    suspend fun logOut() {
        cachedUserId = null
        chatTitleCache.clear()
        remoteFileMap.clear()
        runCatching { send(TdApi.LogOut()) }
    }

    /** Returns chats the user has access to (Saved Messages, channels, groups). */
    suspend fun getChats(limit: Int = 100): List<TdApi.Chat> {
        if (!isReady()) return emptyList()
        val chats = mutableListOf<TdApi.Chat>()
        runCatching {
            send(TdApi.LoadChats(null, limit))
        }
        val chatList = runCatching {
            send(TdApi.GetChats(null, limit))
        }.getOrNull() ?: return emptyList()

        for (id in chatList.chatIds) {
            runCatching {
                send(TdApi.GetChat(id))
            }.getOrNull()?.let { chats += it }
        }
        return chats
    }

    /** Returns chats formatted for user selection in the UI. */
    suspend fun getAvailableMusicChats(limit: Int = 100): List<TelegramChatItem> {
        if (!isReady()) return emptyList()
        val me = getMe()
        val rawChats = getChats(limit)
        val result = mutableListOf<TelegramChatItem>()

        if (me != null) {
            val savedTitle = runCatching { send(TdApi.GetChat(me.id)) }.getOrNull()?.title?.ifBlank { "Saved Messages" } ?: "Saved Messages"
            chatTitleCache[me.id] = savedTitle
            result += TelegramChatItem(
                id = me.id,
                title = savedTitle,
                typeName = "Saved Messages",
                isSavedMessages = true,
            )
        }

        for (chat in rawChats) {
            if (me != null && chat.id == me.id) continue
            val typeName = when (val t = chat.type) {
                is TdApi.ChatTypeSupergroup -> if (t.isChannel) "Channel" else "Supergroup"
                is TdApi.ChatTypeBasicGroup -> "Group"
                is TdApi.ChatTypePrivate -> "Private Chat"
                is TdApi.ChatTypeSecret -> "Secret Chat"
                else -> "Chat"
            }
            val title = chat.title.ifBlank { "Chat ${chat.id}" }
            chatTitleCache[chat.id] = title
            result += TelegramChatItem(
                id = chat.id,
                title = title,
                typeName = typeName,
                isSavedMessages = false,
            )
        }
        return result
    }

    /** Searches and paginates all audio messages in the given chat. */
    suspend fun getAudioMessages(chatId: Long, maxCount: Int = 500): List<TdApi.Message> {
        if (!isReady()) return emptyList()
        val result = mutableListOf<TdApi.Message>()
        var fromMessageId = 0L
        var offset = 0
        while (result.size < maxCount) {
            val batchSize = minOf(100, maxCount - result.size)
            val found = runCatching {
                send(
                    TdApi.SearchChatMessages(
                        chatId,
                        null,
                        "",
                        null,
                        fromMessageId,
                        offset,
                        batchSize,
                        TdApi.SearchMessagesFilterAudio(),
                    )
                )
            }.getOrNull() ?: break

            if (found.messages.isEmpty()) break
            result.addAll(found.messages)
            if (found.nextFromMessageId == 0L || found.nextFromMessageId == fromMessageId) break
            fromMessageId = found.nextFromMessageId
            offset = 0
        }
        return result
    }

    /** Resolves a permanent remote file id to a current TDLib file object. */
    suspend fun getRemoteFile(remoteFileId: String, fileType: TdApi.FileType = TdApi.FileTypeAudio()): TdApi.File {
        remoteFileMap[remoteFileId]?.let { cachedId ->
            runCatching { return send(TdApi.GetFile(cachedId)) }
        }
        val file = send(TdApi.GetRemoteFile(remoteFileId, fileType))
        remoteFileMap[remoteFileId] = file.id
        return file
    }

    suspend fun getFile(fileId: Int): TdApi.File = send(TdApi.GetFile(fileId))

    suspend fun downloadFile(
        fileId: Int,
        priority: Int = 32,
        offset: Long = 0,
        limit: Long = 0,
        synchronous: Boolean = false,
    ): TdApi.File = send(TdApi.DownloadFile(fileId, priority, offset, limit, synchronous))

    suspend fun downloadThumbnail(remoteFileId: String): String? {
        val file = runCatching { getRemoteFile(remoteFileId, TdApi.FileTypeThumbnail()) }.getOrNull() ?: return null
        if (file.local.isDownloadingCompleted && File(file.local.path).exists()) {
            return file.local.path
        }
        val downloaded = runCatching {
            downloadFile(file.id, priority = 32, offset = 0, limit = 0, synchronous = true)
        }.getOrNull()
        return downloaded?.local?.path?.takeIf { it.isNotEmpty() && File(it).exists() }
    }

    fun downloadFileAsync(fileId: Int) {
        scope.launch {
            runCatching {
                downloadFile(fileId, priority = 16, offset = 0, limit = 0, synchronous = false)
            }
        }
    }

    private val artworkPrefs by lazy {
        appContext.getSharedPreferences("cadence_tg_artwork", Context.MODE_PRIVATE)
    }

    fun registerAudioArtwork(audioRemoteId: String, thumbRemoteId: String) {
        artworkPrefs.edit().putString("audio_$audioRemoteId", thumbRemoteId).apply()
    }

    fun getAudioArtwork(audioRemoteId: String): String? =
        artworkPrefs.getString("audio_$audioRemoteId", null)

    fun registerChatArtwork(chatKey: String, photoRemoteId: String) {
        artworkPrefs.edit().putString("chat_$chatKey", photoRemoteId).apply()
    }

    fun getChatArtwork(chatKey: String): String? =
        artworkPrefs.getString("chat_$chatKey", null)

    suspend fun getLocalFilePath(remoteFileId: String): String? {
        val cachedId = remoteFileMap[remoteFileId] ?: runCatching {
            getRemoteFile(remoteFileId, TdApi.FileTypeThumbnail()).id
        }.getOrNull() ?: return null
        val file = runCatching { getFile(cachedId) }.getOrNull() ?: return null
        return file.local.path.takeIf { file.local.isDownloadingCompleted && it.isNotEmpty() && File(it).exists() }
    }

    suspend fun cancelDownload(fileId: Int) {
        runCatching { send(TdApi.CancelDownloadFile(fileId, false)) }
    }

    suspend fun getDownloadedPrefix(fileId: Int, offset: Long): Long =
        runCatching {
            send(TdApi.GetFileDownloadedPrefixSize(fileId, offset)).size
        }.getOrDefault(0L)

    companion object {
        private const val TAG = "TelegramManager"

        @Volatile private var instance: TelegramManager? = null

        fun get(context: Context): TelegramManager =
            instance ?: synchronized(this) {
                instance ?: TelegramManager(context.applicationContext).also {
                    instance = it
                    it.start()
                }
            }
    }
}
