package no.neverhood.nfcmediaplayer

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanResult
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.welie.blessed.BluetoothBytesParser
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessed.BluetoothPeripheralCallback
import com.welie.blessed.GattStatus
import com.welie.blessed.WriteType
import timber.log.Timber
import java.util.UUID
import androidx.core.net.toUri

// HM-10 UUIDs
private val SERVICE_UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
private val CHAR_UUID   = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
private val wakeupCommand = byteArrayOf(0x55, 0x55, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xff.toByte(), 0x03, 0xfd.toByte(), 0xd4.toByte(), 0x14, 0x01, 0x17, 0x00)

enum class Pn532State {
    POWER_ON,
    SEARCHING,
    CONNECTING,
    SERVICE_DISCOVERY,
    ENABLING_NOTIFICATIONS,
    WAKEUP,
    CONFIG,
    SCANNING,
    NDEF,
}

class Pn532Manager(val activity: MainActivity) {
    private var central: BluetoothCentralManager? = null
    private var connectedPeripheral: BluetoothPeripheral? = null
    private var state: Pn532State = Pn532State.POWER_ON
    private var receiveBuffer = byteArrayOf()
    private var payloadBuffer = ""
    private var ndefPage = 1

    private val swVersionCommand = buildPn532Frame(0x02) // byteArrayOf(0x00, 0x00, 0xff.toByte(), 0x02, 0xfe.toByte(), 0xd4.toByte(), 0x02, 0x2a, 0x00)
    private val inListPassiveTargetCommand = buildPn532Frame(0x4a, byteArrayOf(0x01, 0x00)) // byteArrayOf(0x00, 0x00, 0xff.toByte(), 0x04, 0xfc.toByte(), 0xd4.toByte(), 0x4a, 0x01, 0x00, 0xe1.toByte(), 0x00)

    fun initBluetooth() {
        if (central != null) return

        val centralManagerCallback = object : BluetoothCentralManagerCallback() {
            override fun onDiscovered(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
                Timber.i("Found peripheral '${peripheral.name}' with RSSI ${scanResult.rssi}")
                central?.stopScan()
                central?.connect(peripheral, bluetoothPeripheralCallback)
                state = Pn532State.CONNECTING
            }

            override fun onConnected(peripheral: BluetoothPeripheral) {
                super.onConnected(peripheral)
                Timber.i("Connected to peripheral '${peripheral.name}'")
                connectedPeripheral = peripheral
                state = Pn532State.SERVICE_DISCOVERY
            }

            val bluetoothPeripheralCallback = object : BluetoothPeripheralCallback() {
                override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
                    val success = peripheral.startNotify(SERVICE_UUID, CHAR_UUID)
                    Timber.i("Notifications started: $success")
                    state = Pn532State.ENABLING_NOTIFICATIONS
                }

                override fun onNotificationStateUpdate(
                    peripheral: BluetoothPeripheral,
                    characteristic: BluetoothGattCharacteristic,
                    status: GattStatus
                ) {
                    Timber.i("Notification state updated: $status")
                    connectedPeripheral?.writeCharacteristic(SERVICE_UUID, CHAR_UUID, wakeupCommand, WriteType.WITH_RESPONSE)
                    state = Pn532State.WAKEUP
                }

                override fun onCharacteristicWrite(
                    peripheral: BluetoothPeripheral,
                    value: ByteArray,
                    characteristic: BluetoothGattCharacteristic,
                    status: GattStatus
                ) {
                    val data = value.toHexString()
                    Log.d("Bluetooth", "Data written: $data")
                    Timber.i("Write completed with status: $status")

                }

                override fun onCharacteristicUpdate(
                    peripheral: BluetoothPeripheral,
                    value: ByteArray,
                    characteristic: BluetoothGattCharacteristic,
                    status: GattStatus
                ) {
                    if (status == GattStatus.SUCCESS) {
                        val received = value.toHexString().trim()
                        Timber.d("Received from HM-10: $received")

                        // Strip ACK (if any)
                        val dataFrame = stripPn532Ack(value)
                        if(dataFrame.isEmpty()) return // No daa, ACK only

                        try {
                            val payloadBytes = parsePn532Frame(dataFrame)
                            if (payloadBytes.isEmpty()) {
                                return
                            }

                            val data = payloadBytes.toHexString()
                            Timber.d("Parsed data: $data")

                            if (state == Pn532State.SCANNING) {
                                // Tag detected. Parse serial number and read NDEF
                                Timber.d("Tag detected: $data")
                                val command = buildPn532Frame(0x40, byteArrayOf(0x01, 0x30, 0x04))
                                connectedPeripheral?.writeCharacteristic(SERVICE_UUID, CHAR_UUID, command, WriteType.WITH_RESPONSE)
                                state = Pn532State.NDEF
                            }

                            else if (state == Pn532State.NDEF) {
                                if (ndefPage == 1) {
                                    val parser = BluetoothBytesParser(payloadBytes, 15)
                                    payloadBuffer = parser.getString()

                                    val byteNum = 4 * ++ndefPage
                                    val command = buildPn532Frame(0x40, byteArrayOf(0x01, 0x30, byteNum.toByte()))
                                    connectedPeripheral?.writeCharacteristic(SERVICE_UUID, CHAR_UUID, command, WriteType.WITH_RESPONSE)
                                }
                                else if (ndefPage == 2) {
                                    val parser = BluetoothBytesParser(payloadBytes, 3)
                                    payloadBuffer += parser.getString()

                                    val byteNum = 4 * ++ndefPage
                                    val command = buildPn532Frame(0x40, byteArrayOf(0x01, 0x30, byteNum.toByte()))
                                    connectedPeripheral?.writeCharacteristic(SERVICE_UUID, CHAR_UUID, command, WriteType.WITH_RESPONSE)
                                }
                                else if (ndefPage == 3) {
                                    val parser = BluetoothBytesParser(payloadBytes, 3)
                                    payloadBuffer += parser.getString()

                                    val uri = payloadBuffer.trim(0xFE.toChar()).toUri()
                                    activity.extractAndPlayVideo(uri)
                                }

                                Timber.d("Tag: $payloadBuffer")
                            }

                            else if (state == Pn532State.WAKEUP && data == "d515") {
                                connectedPeripheral?.writeCharacteristic(SERVICE_UUID, CHAR_UUID, swVersionCommand, WriteType.WITH_RESPONSE)
                                state = Pn532State.CONFIG
                            }
                            else if (state == Pn532State.CONFIG && data == "d50332010607") {
                                connectedPeripheral?.writeCharacteristic(SERVICE_UUID, CHAR_UUID, inListPassiveTargetCommand, WriteType.WITH_RESPONSE)
                                state = Pn532State.SCANNING
                            }
                            else {
                                Timber.d("Received unknown response: $received")
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Error parsing data")
                            return
                        }
                    }
                }
            }
        }

        central = BluetoothCentralManager(activity.applicationContext, centralManagerCallback, Handler(Looper.getMainLooper()))
        //central?.scanForPeripherals()
        central?.scanForPeripheralsWithNames(setOf("NFC-MediaPlayer"))
        state = Pn532State.SEARCHING
    }

    fun scanLoop() {

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
        // Prepend previously incomplete frame (if any)
        val rawData = receiveBuffer + data
        if (receiveBuffer.isNotEmpty()) {
            receiveBuffer = byteArrayOf()
        }

        if (rawData.size < 7) throw IllegalArgumentException("Frame too short")

        // 1. Identify start of frame (0x00 0x00 0xFF) or 0x00 0xFF
        var startOffset = 0
        while (startOffset < rawData.size - 2 &&
            !(rawData[startOffset] == 0x00.toByte() &&
                    (rawData[startOffset + 1] == 0xFF.toByte() ||
                            (rawData[startOffset + 1] == 0x00.toByte() && rawData[startOffset + 2] == 0xFF.toByte())))
        ) {
            startOffset++
        }

        // Handle possible preamble/padding
        val headerOffset = if (rawData[startOffset + 1] == 0xFF.toByte()) startOffset else startOffset + 1

        // 2. Read length byte
        val len = rawData[headerOffset + 2].toInt() and 0xFF
        if (len == 0) return byteArrayOf() // Extended frame or ACK
        if (len > rawData.size - headerOffset - 6) {
            // Frame is not complete. Store for use when more data arrives
            receiveBuffer += rawData
            return byteArrayOf()
        }

        // 3. Verify Length Checksum (Len + LenCS = 0)
        val lenCs = rawData[headerOffset + 3].toInt() and 0xFF
        if (((len + lenCs) and 0xFF) != 0) {
            throw IllegalArgumentException("Length checksum failed")
        }

        // 4. Extract Data Payload (Length bytes starting after LenCS)
        val dataPayload = rawData.copyOfRange(headerOffset + 4, headerOffset + 4 + len)

        // 5. Verify Data Checksum
        var sum = 0
        for (i in 0 until len) {
            sum += dataPayload[i].toInt()
        }
        val expectedCs = (0x100 - (sum and 0xFF)) and 0xFF
        val actualCs = rawData[headerOffset + 4 + len].toInt() and 0xFF

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