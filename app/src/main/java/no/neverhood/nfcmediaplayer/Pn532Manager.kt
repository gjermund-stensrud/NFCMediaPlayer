package no.neverhood.nfcmediaplayer

import android.nfc.NdefMessage
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessed.WriteType
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import com.welie.blessed.ConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException

// HM-10 UUIDs
private val SERVICE_UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
private val CHAR_READ_UUID   = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
private val CHAR_WRITE_UUID   = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")

// Minicopy UUIDs
/*
private val SERVICE_UUID = UUID.fromString("0000FF00-0000-1000-8000-00805F9B34FB")
private val CHAR_READ_UUID   = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
private val CHAR_WRITE_UUID   = UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB")
*/

private val wakeupCommand = byteArrayOf(0x55, 0x55, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xff.toByte(), 0x03, 0xfd.toByte(), 0xd4.toByte(), 0x14, 0x01, 0x17, 0x00)

class Pn532Manager(val activity: MainActivity) {
    private var central = BluetoothCentralManager(activity.applicationContext)

    // Response assembly state
    private val responseBuffer = ByteArrayOutputStream()
    private var responseDeferred: CompletableDeferred<ByteArray>? = null

    private val swVersionCommand = buildPn532Frame(0x02) // byteArrayOf(0x00, 0x00, 0xff.toByte(), 0x02, 0xfe.toByte(), 0xd4.toByte(), 0x02, 0x2a, 0x00)
    private val inListPassiveTargetCommand = buildPn532Frame(0x4a, byteArrayOf(0x01, 0x00)) // byteArrayOf(0x00, 0x00, 0xff.toByte(), 0x04, 0xfc.toByte(), 0xd4.toByte(), 0x4a, 0x01, 0x00, 0xe1.toByte(), 0x00)

    private fun startScan() {
        activity.setStatus("Scanning...")
        // TODO Scan using services instead of names and support Minicopy
        central.scanForPeripheralsWithNames(arrayOf("NFC-MediaPlayer"),
            { peripheral, scanResult ->
                Timber.i("Found peripheral '${peripheral.name}' with RSSI ${scanResult.rssi}")
                central.stopScan()
                try {
                    central.autoConnectPeripheral(peripheral)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to connect to peripheral")
                }
            },
            { scanFailure -> Timber.e("scan failed with reason $scanFailure")})
    }

    fun initBluetooth() {
        Thread {
            val scope = CoroutineScope(Job() + Dispatchers.IO)

            // Scan for device
            startScan()

            central.observeConnectionState { peripheral, state ->
                Timber.i("Peripheral ${peripheral.name} has $state")
                activity.setStatus(state.toString())
                if(state == ConnectionState.CONNECTED) {
                    scope.launch {
                        try {
                            setupNotificationObserver(peripheral)
                            readTagLoop(peripheral)
                        } catch (e: Exception) {
                            Timber.e(e, "Unexpected error")
                        }
                    }
                }
                if(state == ConnectionState.DISCONNECTED) {
                    // Clean up and start scanning
                    responseDeferred?.cancel()
                    startScan()
                }
            }
        }.start()
    }

    fun readTagLoop(peripheral: BluetoothPeripheral) {
        // When device is connected. Send wakeup and config sequence
        val wakeupBytes = sendCommand(peripheral, wakeupCommand)
        if (wakeupBytes[0] != 0xd5.toByte() || wakeupBytes[1] != 0x15.toByte()) {
            // Wakeup failed
            return
        }

        // Get swVersion
        val swVersionBytes = sendCommand(peripheral, swVersionCommand)
        val swVersion = swVersionBytes.toHexString()
        Timber.d("SW Version: %s", swVersion)

        if (swVersion != "d50332010607") {
            // Sw version failed
            return
        }

        while (peripheral.getState() == ConnectionState.CONNECTED) {
            // Scan for tag
            Timber.d("Scanning for tag...")
            activity.setStatus("Waiting for tag...")
            val tagData = sendCommand(peripheral, inListPassiveTargetCommand, 0)

            // If error or timeout, restart scan
            if (isErrorOrTimeout(tagData)) {
                continue
            }

            // Tag detected. Parse serial number and read NDEF
            val tagId = tagData.copyOfRange(8, 8 + tagData[7].toInt()).toHexString()
            Timber.d("TagCount: %d. TagNumber: %d. TagType(ATQA): %02X %02X. SAK: %02X. TagID: %s", tagData[2], tagData[3], tagData[4], tagData[5], tagData[6], tagId)
            activity.setStatus("Tag detected")

            // Read NDEF pages until end
            var ndefPage = 1
            var hasMoreData = true
            var hasError = false
            var payloadBuffer = byteArrayOf()
            var byteLength = 0
            var readLength = 0
            while (hasMoreData && readLength <= byteLength) {
                //Timber.d("Reading NDEF page %d", ndefPage)
                val blockNum = 4 * ndefPage
                val command = buildPn532Frame(0x40, byteArrayOf(0x01, 0x30, blockNum.toByte()))
                val ndefData = sendCommand(peripheral, command)

                if (isErrorOrTimeout(ndefData)) {
                    hasError = true
                    break
                }

                var startIndex = 3
                if (ndefPage == 1) {
                    // Find NDEF start indicator (0xD1)
                    startIndex = ndefData.indexOf(0xD1.toByte())
                    // Number of bytes is in the byte preceding NDEF start
                    byteLength = ndefData[startIndex-1].toInt()
                }

                var len = ndefData.size
                val endIndex = ndefData.indexOf(0xFE.toByte())
                if (endIndex > 0) {
                    hasMoreData = false
                    len = endIndex
                }

                payloadBuffer += ndefData.copyOfRange(startIndex, len)
                readLength += len - startIndex

                ndefPage++
            }

            if (hasError) {
                continue
            }

            try {
                val message = NdefMessage(payloadBuffer)

                if(message.records.size == 0) {
                    Timber.d("No ndef records found on tag")
                    continue
                }

                // Get URI
                val uri = message.records[0].toUri()
                if (uri != null) {
                    // Send URI to MainActivity
                    activity.setStatus("URI extracted")
                    Timber.d("URI: %s", uri)
                    activity.extractAndPlayVideo(uri)
                }
            } catch (e: Exception) {
                Timber.e(e,"Invalid NDEF message")
            }
        }

        // TODO clean up connection and notification
        Timber.d("readTagLoop ended")
    }

    fun isErrorOrTimeout(data: ByteArray): Boolean {
        if (data.isEmpty()) {
            // Timeout
            return true
        }
        if (data.size == 3 && data[0] == 0xD5.toByte() && data[1] == 0x41.toByte() && data[2] != 0x00.toByte()) {
            // PN532 returned error response
            Timber.d("PN532 returned error code: %02X", data[2])
            return true
        }

        return false
    }

    /**
     * Sends a command and waits synchronously for the **complete** PN532 response.
     *
     * Handles responses split across multiple BLE notifications.
     *
     * @param command Raw command bytes (usually with full PN532 frame: preamble + length + ... + checksum)
     * @param timeoutMs Timeout for the entire response (default 1s)
     * @return Complete response as received from PN532 (including ACK + information frame)
     */
    fun sendCommand(peripheral: BluetoothPeripheral, command: ByteArray, timeoutMs: Long = 1000L): ByteArray {

        if (peripheral.getState() != ConnectionState.CONNECTED) {
            throw IllegalStateException("PN532 is not connected")
        }

        return runBlocking(Dispatchers.IO) {
            resetResponseState()
            val deferred = CompletableDeferred<ByteArray>()
            responseDeferred = deferred

            // Write the command
            peripheral.writeCharacteristic(
                SERVICE_UUID,
                CHAR_WRITE_UUID,
                command,
                WriteType.WITH_RESPONSE
            )

            // Wait for complete response (assembled from one or more notifications)
            try {
                if (timeoutMs > 0) {
                    withTimeout(timeoutMs) {
                        deferred.await()
                    }
                } else {
                    deferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                Timber.e(e, "Timeout waiting for PN532 response")
                byteArrayOf()
            } catch (e: CancellationException) {
                Timber.e(e, "Wait for PN532 cancelled")
                byteArrayOf()
            }
        }
    }

    /**
     * Sets up the notification observer that accumulates chunks.
     */
    private suspend fun setupNotificationObserver(peripheral: BluetoothPeripheral) {
        val rxCharacteristic = peripheral.getCharacteristic(SERVICE_UUID, CHAR_READ_UUID)
            ?: throw IllegalStateException("RX characteristic not found on this device")

        peripheral.observe(rxCharacteristic) { chunk: ByteArray ->
            handleIncomingChunk(chunk)
        }
    }

    /**
     * Accumulates incoming data and checks if we have a complete PN532 frame.
     */
    private fun handleIncomingChunk(chunk: ByteArray) {
        responseBuffer.write(stripPn532Ack(chunk))

        val currentData = responseBuffer.toByteArray()

        // Try to parse a complete frame from the accumulated data
        val completeFrame = extractCompletePn532Frame(currentData)

        if (completeFrame != null) {
            // We have a full valid response → complete the deferred
            val payload = parsePn532Frame(completeFrame)
            responseDeferred?.complete(payload)
            resetResponseState()
        }
        // Otherwise keep accumulating for the next notification
    }

    /**
     * Looks for a complete PN532 response frame in the buffer.
     * Supports both ACK and normal/extended information frames.
     *
     * Returns the frame bytes if complete and valid, otherwise null.
     */
    private fun extractCompletePn532Frame(data: ByteArray): ByteArray? {
        if (data.size < 6) return null

        var idx = 0

        // Find start of a frame: 00 00 FF
        while (idx + 5 < data.size) {
            if (data[idx] == 0x00.toByte() &&
                data[idx + 1] == 0x00.toByte() &&
                data[idx + 2] == 0xFF.toByte()) {

                // Check if it's an ACK frame (very common after command)
                if (idx + 5 < data.size &&
                    data[idx + 3] == 0x00.toByte() &&
                    data[idx + 4] == 0xFF.toByte() &&
                    data[idx + 5] == 0x00.toByte()) {

                    // ACK is always 6 bytes: 00 00 FF 00 FF 00
                    return if (idx + 6 <= data.size) {
                        data.copyOfRange(idx, idx + 6)
                    } else null
                }

                // Normal or Extended information frame
                if (idx + 8 > data.size) return null // Not enough bytes yet

                val len = data[idx + 3].toInt() and 0xFF

                if (len == 0xFF) {
                    // Extended frame: next 2 bytes = length (big endian)
                    if (idx + 10 > data.size) return null
                    val extendedLen = ((data[idx + 6].toInt() and 0xFF) shl 8) or (data[idx + 7].toInt() and 0xFF)
                    val totalFrameSize = 10 + extendedLen + 2 // preamble(4) + LENM/LENL/LCS(3) + data + DCS + postamble(1) ≈ simplified

                    if (idx + totalFrameSize <= data.size) {
                        // Basic checksum validation can be added here if desired
                        return data.copyOfRange(idx, idx + totalFrameSize)
                    }
                } else {
                    // Normal frame: LEN + LCS + TFI + PD0..PDn + DCS + postamble
                    val frameSize = 6 + len + 1 // rough: preamble(3) + LEN(1) + LCS(1) + data(LEN+1 incl TFI) + DCS(1) + post(1) + margin
                    if (idx + frameSize <= data.size) {
                        return data.copyOfRange(idx, idx + frameSize)
                    }
                }
            }
            idx++
        }
        return null // No complete frame yet
    }

    private fun resetResponseState() {
        responseBuffer.reset()
        responseDeferred = null
    }

    private fun buildPn532Frame(command: Byte, params: ByteArray = byteArrayOf()): ByteArray {
        val data = byteArrayOf(command) + params
        val len = (data.size + 1).toByte() // +1 for TFI
        val lcs = (-len).toByte()
        val tfi = 0xD4.toByte()
        val sum = tfi.toInt() + data.sumOf { it.toInt() and 0xFF }
        val dcs = (-sum and 0xFF).toByte()
        return byteArrayOf(
            0x00, 0x00, 0xFF.toByte(), len, lcs, tfi
        ) + data + byteArrayOf(dcs, 0x00)
    }

    /**
     * Parses a raw byte array from the PN532 device.
     * PN532 Frame: Preamble(1), StartCode(2), Len(1), LenCS(1), Data(n), CS(1), Postamble(1)
     */
    fun parsePn532Frame(data: ByteArray): ByteArray {
        if (data.size < 7) throw IllegalArgumentException("Frame too short")

        // 1. Identify start of frame (0x00 0x00 0xFF) or 0x00 0xFF
        var startOffset = 0
        while (startOffset < data.size - 2 &&
            !(data[startOffset] == 0x00.toByte() &&
                    (data[startOffset + 1] == 0xFF.toByte() ||
                            (data[startOffset + 1] == 0x00.toByte() && data[startOffset + 2] == 0xFF.toByte())))
        ) {
            startOffset++
        }

        // Handle possible preamble/padding
        val headerOffset = if (data[startOffset + 1] == 0xFF.toByte()) startOffset else startOffset + 1

        // 2. Read length byte
        val len = data[headerOffset + 2].toInt() and 0xFF
        if (len == 0) return byteArrayOf() // Extended frame or ACK

        // 3. Verify Length Checksum (Len + LenCS = 0)
        val lenCs = data[headerOffset + 3].toInt() and 0xFF
        if (((len + lenCs) and 0xFF) != 0) {
            throw IllegalArgumentException("Length checksum failed")
        }

        // 4. Extract Data Payload (Length bytes starting after LenCS)
        val dataPayload = data.copyOfRange(headerOffset + 4, headerOffset + 4 + len)

        // 5. Verify Data Checksum
        var sum = 0
        for (i in 0 until len) {
            sum += dataPayload[i].toInt()
        }
        val expectedCs = (0x100 - (sum and 0xFF)) and 0xFF
        val actualCs = data[headerOffset + 4 + len].toInt() and 0xFF

        if (expectedCs != actualCs) {
            throw IllegalArgumentException("Data checksum failed: Expected $expectedCs, Got $actualCs")
        }

        return dataPayload
    }

    /**
     * Detects the PN532 ACK frame (00 00 FF 00 FF 00).
     * Returns the data following the ACK, or the original array if no ACK is found.
     */
    fun stripPn532Ack(input: ByteArray): ByteArray {
        val ackPattern = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(), 0x00)

        if (input.size < ackPattern.size) return input

        // Check if the beginning of the array matches the ACK pattern
        val hasAck = ackPattern.indices.all { i -> input[i] == ackPattern[i] }

        return if (hasAck) {
            input.copyOfRange(ackPattern.size, input.size)
        } else {
            input
        }
    }
}