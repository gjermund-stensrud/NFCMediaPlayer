package no.neverhood.nfcmediaplayer

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import no.neverhood.nfcmediaplayer.databinding.ActivityMainBinding
import timber.log.Timber


class MainActivity : AppCompatActivity() {
    private var player: YouTubePlayer? = null
    private var playerState: PlayerConstants.PlayerState = PlayerConstants.PlayerState.UNSTARTED
    private var currentVideoId = ""
    private var videoIdToWrite: String? = null
    private var writeDialog: AlertDialog? = null

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var binding: ActivityMainBinding

    private var pn532Manager: Pn532Manager? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            pn532Manager?.initBluetooth()
        } else {
            Snackbar.make(binding.root, "Bluetooth permissions are required", Snackbar.LENGTH_INDEFINITE)
                .setAction("Retry") { checkPermissionsAndInit() }
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Init Timber
        Timber.plant(Timber.DebugTree())   // This forwards logs to android.util.Log → Logcat

        // Init NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // Check permissions and then init Bluetooth
        pn532Manager = Pn532Manager(this)
        checkPermissionsAndInit()

        // Init YoutubePlayerView
        val youTubePlayerView: YouTubePlayerView = binding.youtubePlayerView
        lifecycle.addObserver(youTubePlayerView)

        youTubePlayerView.addYouTubePlayerListener(object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                player = youTubePlayer
                // Process the intent that started the activity
                handleIntent(intent)
            }

            // Capture state changes to know when video is playing
            override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                super.onStateChange(youTubePlayer, state)
                playerState = state
            }
        })
    }

    fun setStatus(text: String) {
        binding.textStatus.text = text
    }

    private fun showWriteDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_write_nfc, null)
        writeDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Skriv til NFC Tag")
            .setView(dialogView)
            .setNegativeButton("Avbryt") { _, _ ->
                videoIdToWrite = null
            }
            .setOnDismissListener {
                writeDialog = null
            }
            .setCancelable(false)
            .show()
    }

    private fun updateWriteDialogMessage(message: String) {
        writeDialog?.findViewById<android.widget.TextView>(R.id.text_dialog_message)?.text = message
    }

    // Bluetooth functions
    fun checkPermissionsAndInit() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            pn532Manager?.initBluetooth()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    // YouTube functions
    private fun extractYoutubeId(url: String): String? {
        val patterns = listOf(
            "v=([^&]+)",
            "youtu.be/([^?]+)",
            "embed/([^?]+)",
            "shorts/([^?]+)"
        )
        for (pattern in patterns) {
            val regex = Regex(pattern)
            val match = regex.find(url)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    fun extractAndPlayVideo(data: android.net.Uri) {
        // TODO: Move write operations here and support writing to external device
        val videoId = data.getQueryParameter("yt") ?: data.toString().substringAfter("yt=", "")
        if (videoId.isNotBlank()) {
            playVideo(videoId)
        }
    }

    private fun playVideo(videoId: String) {
        if (playerState == PlayerConstants.PlayerState.PLAYING && videoId == currentVideoId) {
            Timber.d("Already playing $videoId")
            return
        }
        Timber.d("Playing video: $videoId")
        currentVideoId = videoId
        player?.loadVideo(videoId, 0f)
    }

    // NFC functions
    override fun onResume() {
        super.onResume()
        enableNfcForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    private fun enableNfcForegroundDispatch() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        } else {
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        }
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Timber.d("onNewIntent: ${intent.action}")
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        // Handle Shared Text (YouTube link)
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null) {
                val videoId = extractYoutubeId(sharedText)
                if (videoId != null) {
                    videoIdToWrite = videoId
                    showWriteDialog()
                }
            }
            return
        }

        when (intent.action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_TAG_DISCOVERED -> {
                val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
                if (videoIdToWrite != null && tag != null) {
                    writeToTag(tag, videoIdToWrite!!)
                    return
                }

                // 1. Try to get NDEF data from URI (standard for our nfcmp:// scheme)
                val data = intent.data
                if (data != null && data.scheme == "nfcmp") {
                    extractAndPlayVideo(data)
                    return
                } else {
                    Timber.d("Incorrect NDEF data found in intent")
                    // It might be a generic tag or one we're supposed to read via NDEF messages
                }

                // 2. Try to get NDEF data from messages in the intent
                val rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
                if (rawMsgs != null) {
                    for (rawMsg in rawMsgs) {
                        val msg = rawMsg as NdefMessage
                        for (record in msg.records) {
                            val uri = record.toUri()
                            if (uri != null && uri.scheme == "nfcmp") {
                                extractAndPlayVideo(uri)
                                return
                            }
                        }
                    }
                }
            }
        }
    }

    private fun writeToTag(tag: Tag, videoId: String) {
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            val error = "NFC tag støtter ikke NDEF"
            if (writeDialog != null) {
                updateWriteDialogMessage("$error. Prøv en annen tag.")
            } else {
                Snackbar.make(binding.root, error, Snackbar.LENGTH_SHORT).show()
            }
            return
        }

        val uri = "nfcmp://play?yt=$videoId"
        val record = NdefRecord.createUri(uri)
        val message = NdefMessage(arrayOf(record))

        try {
            ndef.connect()
            if (!ndef.isWritable) {
                val error = "NFC tag er ikke skrivbar"
                if (writeDialog != null) {
                    updateWriteDialogMessage("$error. Prøv en annen tag.")
                } else {
                    Snackbar.make(binding.root, error, Snackbar.LENGTH_SHORT).show()
                }
                return
            }
            if (ndef.maxSize < message.toByteArray().size) {
                val error = "NFC tag har for lite plass"
                if (writeDialog != null) {
                    updateWriteDialogMessage("$error. Prøv en annen tag.")
                } else {
                    Snackbar.make(binding.root, error, Snackbar.LENGTH_SHORT).show()
                }
                return
            }
            ndef.writeNdefMessage(message)
            videoIdToWrite = null
            writeDialog?.dismiss()
            Snackbar.make(binding.root, "Tag date skrevet!", Snackbar.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Timber.e(e, "Error writing to tag")
            val error = "Feil ved skriving til tag"
            if (writeDialog != null) {
                updateWriteDialogMessage("$error. Prøv igjen.")
            } else {
                Snackbar.make(binding.root, error, Snackbar.LENGTH_SHORT).show()
            }
        } finally {
            try {
                ndef.close()
            } catch (e: Exception) {
                Timber.e(e, "Error closing ndef")
            }
        }
    }
}
