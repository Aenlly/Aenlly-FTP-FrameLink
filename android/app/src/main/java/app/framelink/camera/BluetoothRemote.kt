package app.framelink.camera

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

data class RemoteDevice(val address: String, val name: String)

/** Sony蓝牙遥控器通道，只提供按钮动作与实际连接状态 */
@SuppressLint("MissingPermission")
class BluetoothRemote(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SecureSettings,
    private val notice: (String) -> Unit,
) {
    val lastAddress
        get() = settings.get("bluetooth.lastAddress")

    val devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val state = MutableStateFlow("蓝牙未连接")
    val ready = MutableStateFlow(false)
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var gatt: BluetoothGatt? = null
    private var pending: CompletableDeferred<Unit>? = null
    private val writes = Mutex()
    private val service = UUID.fromString("8000ff00-ff00-ffff-ffff-ffffffffffff")
    private val command = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    private val updates = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val scan =
        object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                if (
                    name.contains("ILCE", true) ||
                        name.contains("DSC", true) ||
                        name.contains("ZV-", true) ||
                        result.scanRecord?.serviceUuids?.any { it.uuid == service } == true
                )
                    devices.value =
                        (devices.value + RemoteDevice(result.device.address, name)).distinctBy {
                            it.address
                        }
            }

            override fun onScanFailed(code: Int) {
                state.value = "蓝牙扫描未完成（$code），请检查蓝牙权限和系统开关"
            }
        }
    private val bond =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device =
                    if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java,
                        )
                    else
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (
                    device?.address == gatt?.device?.address &&
                        intent.getIntExtra(
                            BluetoothDevice.EXTRA_BOND_STATE,
                            BluetoothDevice.BOND_NONE,
                        ) == BluetoothDevice.BOND_BONDED
                )
                    gatt?.discoverServices()
            }
        }

    init {
        ContextCompat.registerReceiver(
            context,
            bond,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    fun scan() {
        check(manager.adapter?.isEnabled == true) { "请先打开手机蓝牙" }
        devices.value = emptyList()
        state.value = "扫描中，请在相机开启蓝牙遥控配对"
        manager.adapter.bluetoothLeScanner.startScan(scan)
        scope.launch {
            delay(12000)
            manager.adapter.bluetoothLeScanner.stopScan(scan)
            if (!ready.value) state.value = "扫描结束，选择相机或重新扫描"
        }
    }

    fun connect(address: String) {
        close()
        state.value = "正在连接，请在相机确认配对"
        manager.adapter.bluetoothLeScanner?.stopScan(scan)
        gatt =
            manager.adapter
                .getRemoteDevice(address)
                .connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE)
    }

    private val callbacks =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (
                    newState == BluetoothProfile.STATE_CONNECTED &&
                        status == BluetoothGatt.GATT_SUCCESS
                ) {
                    if (gatt.device.bondState == BluetoothDevice.BOND_NONE) gatt.device.createBond()
                    else gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    ready.value = false
                    state.value = "蓝牙已断开，请重新连接"
                    pending?.completeExceptionally(IllegalStateException("蓝牙连接已中断"))
                    gatt.close()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val characteristic = gatt.getService(service)?.getCharacteristic(updates)
                val descriptor = characteristic?.getDescriptor(cccd)
                if (
                    status != BluetoothGatt.GATT_SUCCESS ||
                        characteristic == null ||
                        descriptor == null
                ) {
                    state.value = "当前模式没有蓝牙遥控服务，请开启机身蓝牙遥控"
                    return
                }
                gatt.setCharacteristicNotification(characteristic, true)
                if (Build.VERSION.SDK_INT >= 33)
                    gatt.writeDescriptor(
                        descriptor,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                    )
                else {
                    @Suppress("DEPRECATION") descriptor.value =
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION") gatt.writeDescriptor(descriptor)
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                ready.value = status == BluetoothGatt.GATT_SUCCESS
                if (ready.value) settings.put("bluetooth.lastAddress", gatt.device.address)
                state.value =
                    if (ready.value) "已连接 ${gatt.device.name ?: "Sony相机"}" else "蓝牙状态订阅失败，请重新配对"
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) pending?.complete(Unit)
                else pending?.completeExceptionally(IllegalStateException("蓝牙指令未确认（$status）"))
            }
        }

    private suspend fun write(code: Int) =
        writes.withLock {
            check(ready.value) { "蓝牙相机尚未就绪" }
            val active = gatt ?: error("蓝牙未连接")
            val characteristic =
                active.getService(service)?.getCharacteristic(command) ?: error("没有快门控制服务")
            val ack = CompletableDeferred<Unit>()
            pending = ack
            try {
                val data = byteArrayOf(1, code.toByte())
                val started =
                    if (Build.VERSION.SDK_INT >= 33)
                        active.writeCharacteristic(
                            characteristic,
                            data,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                        ) == BluetoothStatusCodes.SUCCESS
                    else {
                        @Suppress("DEPRECATION") characteristic.value = data
                        @Suppress("DEPRECATION") active.writeCharacteristic(characteristic)
                    }
                check(started) { "蓝牙繁忙，请重试" }
                withTimeout(5000) { ack.await() }
            } finally {
                pending = null
            }
        }

    fun focus() =
        scope.launch {
            try {
                write(0x07)
                delay(1200)
            } catch (error: Exception) {
                notice(error.message.orEmpty())
            } finally {
                runCatching { write(0x06) }
            }
        }

    fun shutter() =
        scope.launch {
            try {
                write(0x09)
                delay(120)
                notice("快门指令已发送，请确认机身拍摄；连拍行为遵循机身设置")
            } catch (error: Exception) {
                notice(error.message.orEmpty())
            } finally {
                runCatching { write(0x08) }
            }
        }

    fun close() {
        ready.value = false
        pending?.completeExceptionally(IllegalStateException("蓝牙连接已关闭"))
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        state.value = "蓝牙未连接"
    }
}
