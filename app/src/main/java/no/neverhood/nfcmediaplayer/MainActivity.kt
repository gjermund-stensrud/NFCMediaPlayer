package no.neverhood.nfcmediaplayer

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ActivityInfo
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.snackbar.Snackbar
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import no.neverhood.nfcmediaplayer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private var player: YouTubePlayer? = null
    private var currentVideoId = ""
    private var videoIdToWrite: String? = null

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var binding: ActivityMainBinding

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

        // Init NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // Init YoutubePlayerView
        val youTubePlayerView: YouTubePlayerView = binding.youtubePlayerView
        lifecycle.addObserver(youTubePlayerView)

        youTubePlayerView.addYouTubePlayerListener(object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                player = youTubePlayer
                // Process the intent that started the activity
                handleIntent(intent)
            }
        })
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

    private fun extractAndPlayVideo(data: android.net.Uri) {
        val videoId = data.getQueryParameter("yt") ?: data.toString().substringAfter("yt=", "")
        if (videoId.isNotBlank()) {
            playVideo(videoId)
        }
    }

    private fun playVideo(videoId: String) {
        if (videoId == currentVideoId) {
            Log.d("NFC", "Already playing $videoId")
            return
        }
        Log.d("NFC", "Playing video: $videoId")
        currentVideoId = videoId
        player?.loadVideo(videoId, 0f)
        Snackbar.make(binding.root, "Playing: $videoId", Snackbar.LENGTH_SHORT).show()
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
        Log.d("NFC", "onNewIntent: ${intent.action}")
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
                    Snackbar.make(binding.root, "Present an NFC tag to store the video", Snackbar.LENGTH_INDEFINITE)
                        .setAction("Cancel") { videoIdToWrite = null }
                        .show()
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
                    videoIdToWrite = null
                    return
                }

                // 1. Try to get NDEF data from URI (standard for our nfcmp:// scheme)
                val data = intent.data
                if (data != null && data.scheme == "nfcmp") {
                    extractAndPlayVideo(data)
                    return
                } else {
                    Log.d("NFC", "Incorrect NDEF data found in intent")
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
            Snackbar.make(binding.root, "NFC tag does not support NDEF", Snackbar.LENGTH_SHORT).show()
            return
        }

        val uri = "nfcmp://play?yt=$videoId"
        val record = NdefRecord.createUri(uri)
        val message = NdefMessage(arrayOf(record))

        try {
            ndef.connect()
            if (!ndef.isWritable) {
                Snackbar.make(binding.root, "NFC tag is read-only", Snackbar.LENGTH_SHORT).show()
                return
            }
            if (ndef.maxSize < message.toByteArray().size) {
                Snackbar.make(binding.root, "NFC tag space is too small", Snackbar.LENGTH_SHORT).show()
                return
            }
            ndef.writeNdefMessage(message)
            Snackbar.make(binding.root, "Video stored successfully!", Snackbar.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("NFC", "Error writing to tag", e)
            Snackbar.make(binding.root, "Failed to write to tag", Snackbar.LENGTH_SHORT).show()
        } finally {
            ndef.close()
        }
    }
}
