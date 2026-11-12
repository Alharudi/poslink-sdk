package ly.dojoon.poslink.sample

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import ly.dojoon.poslink.android.bluetooth.BluetoothClientTransport
import ly.dojoon.poslink.android.bluetooth.BluetoothServerTransport
import ly.dojoon.poslink.android.bluetooth.PosLinkBluetooth
import ly.dojoon.poslink.android.storage.AndroidIdentity
import ly.dojoon.poslink.android.storage.KeystorePairingStore
import ly.dojoon.poslink.android.usb.UsbAccessoryTransport
import ly.dojoon.poslink.android.usb.UsbHostTransport
import ly.dojoon.poslink.cashier.PosLinkCashier
import ly.dojoon.poslink.cashier.SaleEvent
import ly.dojoon.poslink.cashier.SaleOutcome
import ly.dojoon.poslink.journal.FileJournal
import ly.dojoon.poslink.link.LinkSession
import ly.dojoon.poslink.link.PairingResult
import ly.dojoon.poslink.terminal.PosLinkTerminal
import ly.dojoon.poslink.terminal.SimulatedPayment
import ly.dojoon.poslink.terminal.TerminalPhase
import ly.dojoon.poslink.transport.TcpClientTransport
import ly.dojoon.poslink.transport.TcpServerTransport
import ly.dojoon.poslink.transport.Transport
import java.io.File
import java.math.BigDecimal
import kotlin.coroutines.resume

/**
 * POS Link sample: one screen, both roles. Shows the whole SDK API: pick a link, pair once, charge.
 * The terminal role uses the payment simulator; no real card is charged.
 */
class MainActivity : Activity() {

    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { getSharedPreferences("sample", Context.MODE_PRIVATE) }
    private var role: String
        get() = prefs.getString("role", "cashier")!!
        set(v) = prefs.edit().putString("role", v).apply()
    private var linkType: String
        get() = prefs.getString("link", "bluetooth")!!
        set(v) = prefs.edit().putString("link", v).apply()

    private var cashier: PosLinkCashier? = null
    private var terminal: PosLinkTerminal? = null
    private var transport: Transport? = null
    private var payment: SimulatedPayment? = null
    private var watch: Job? = null
    private var pairingDialog: AlertDialog? = null
    private val logLines = ArrayDeque<String>()

    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        startLink()
        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onDestroy() {
        ui.launch(Dispatchers.Default) { stopLink() }.invokeOnCompletion { ui.cancel() }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        restart() // the transport re-checks permissions when it starts
    }

    // ---- link ----

    private fun startLink() {
        val isCashier = role == "cashier"
        if (linkType == "bluetooth") askBluetoothPermissions(isCashier)
        val t: Transport? = when (linkType) {
            "usb" -> if (isCashier) UsbHostTransport(this) else UsbAccessoryTransport(this)
            "bluetooth" -> if (isCashier) prefs.getString("btAddress", null)?.let { BluetoothClientTransport(this, it) } else BluetoothServerTransport(this)
            else -> if (isCashier) TcpClientTransport(prefs.getString("tcpHost", "192.168.1.50")!!, 8765) else TcpServerTransport(8765)
        }
        transport = t
        rebuildControls()
        if (t == null) {
            statusView.text = "Choose the terminal: tap \"Choose paired terminal\"."
            return
        }
        val identity = AndroidIdentity.load(this)
        val pairings = KeystorePairingStore(this)
        val journal = FileJournal(File(filesDir, "journal_$role.json"))
        val info = AndroidIdentity.deviceInfo(appVersion = "sample 0.1.0")
        val link: LinkSession
        if (isCashier) {
            val c = PosLinkCashier(t, identity, pairings, journal, info = info, log = ::log)
            cashier = c
            link = c.link
            c.start()
        } else {
            val p = SimulatedPayment()
            payment = p
            val term = PosLinkTerminal(t, identity, pairings, journal, p, info = info, log = ::log)
            terminal = term
            link = term.link
            term.start()
            ui.launch { term.pairingRequest.collect { showPairingRequest(it) } }
            ui.launch { term.phase.collect { showPhase(it) } }
            ui.launch { term.pairingResults.collect { resultView.text = describe(it) } }
        }
        watch = ui.launch {
            combine(link.status, link.transportState, link.problem, link.peer) { s, ts, problem, peer ->
                buildString {
                    append("Link: ").append(s.name.lowercase().replace('_', ' '))
                    peer?.let { append(" · ").append(it.name) }
                    append("\n").append(t.name).append(": ").append(ts.detail ?: ts.status.name.lowercase())
                    problem?.let { append("\n⚠ ").append(it) }
                }
            }.collect { statusView.text = it }
        }
    }

    private suspend fun stopLink() {
        watch?.cancel()
        cashier?.stop()
        terminal?.stop()
        cashier = null
        terminal = null
        transport = null
    }

    private fun restart() {
        ui.launch {
            withContext(Dispatchers.Default) { stopLink() }
            startLink()
        }
    }

    private fun askBluetoothPermissions(isCashier: Boolean) {
        val needed = if (isCashier) BluetoothClientTransport.requiredPermissions() else BluetoothServerTransport.requiredPermissions()
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun handleUsbIntent(intent: Intent?) {
        intent ?: return
        (transport as? UsbAccessoryTransport)?.handleIntent(intent)
        (transport as? UsbHostTransport)?.handleIntent(intent)
    }

    // ---- cashier ----

    private fun charge(amountText: String) {
        val c = cashier ?: return
        val minor = runCatching { BigDecimal(amountText.trim()).movePointRight(3).longValueExact() }.getOrNull()
        if (minor == null || minor <= 0) {
            resultView.text = "Enter an amount like 12.500"
            return
        }
        resultView.text = "Sending ${amountText.trim()} LYD…"
        ui.launch {
            val outcome = c.sale(minor, "LYD", reference = "SAMPLE") { event ->
                ui.launch {
                    resultView.text = when (event) {
                        is SaleEvent.Sent -> "Sent. Waiting for the terminal…"
                        SaleEvent.ReadyToPay -> "Terminal ready: tap, insert or swipe card"
                        is SaleEvent.Progress -> "Terminal: ${event.stage.wire}"
                        SaleEvent.ConnectionLost -> "Connection lost. The result will be fetched when it is back…"
                        SaleEvent.Reconnected -> "Reconnected, checking the sale…"
                    }
                }
            }
            resultView.text = when (outcome) {
                is SaleOutcome.Completed -> with(outcome.result) {
                    "${outcome.result.outcome.wire.uppercase()}  ${BigDecimal(amount).movePointLeft(3)} $currency" +
                        listOfNotNull(authCode?.let { "auth $it" }, maskedPan, message).joinToString("\n", "\n")
                }
                is SaleOutcome.Refused -> "Refused (no money taken): ${outcome.message}"
                is SaleOutcome.Unknown -> "UNKNOWN, check the terminal: ${outcome.message}"
            }
        }
    }

    private fun pairWithTerminal() {
        val c = cashier ?: return
        resultView.text = "Open \"Pair a cashier\" on the terminal, then type its code here."
        ui.launch {
            val result = c.pair { code -> withContext(Dispatchers.Main) { askForCode(code) } }
            resultView.text = describe(result)
        }
    }

    /** Asks the person to type the code the terminal shows; true if it matches. */
    private suspend fun askForCode(expected: String): Boolean = suspendCancellableCoroutine { cont ->
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "6-digit code"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Type the code shown on the terminal")
            .setView(input)
            .setPositiveButton("Pair") { _, _ -> if (cont.isActive) cont.resume(input.text.toString().trim() == expected) }
            .setNegativeButton("Cancel") { _, _ -> if (cont.isActive) cont.resume(false) }
            .setOnCancelListener { if (cont.isActive) cont.resume(false) }
            .show()
        cont.invokeOnCancellation { dialog.dismiss() }
    }

    private fun chooseBluetoothTerminal() {
        val devices = PosLinkBluetooth.bondedDevices(this)
        if (devices == null) {
            askBluetoothPermissions(true)
            return
        }
        if (devices.isEmpty()) {
            resultView.text = "No paired Bluetooth devices. Pair the terminal in Android Bluetooth settings first."
            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
            return
        }
        val labels = devices.map { "${it.name ?: it.address}${if (it.hasPosLinkService) "  (POS Link)" else ""}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Paired devices").setItems(labels) { _, i ->
            prefs.edit().putString("btAddress", devices[i].address).apply()
            restart()
        }.show()
    }

    // ---- terminal ----

    private fun showPairingRequest(request: ly.dojoon.poslink.link.PairingRequest?) {
        pairingDialog?.dismiss()
        pairingDialog = null
        request ?: return
        pairingDialog = AlertDialog.Builder(this)
            .setTitle("Pair with \"${request.cashierName}\"?")
            .setMessage("Code: ${request.code.chunked(3).joinToString(" ")}\n\nConfirm only if the cashier shows or typed this code.")
            .setPositiveButton("Confirm") { _, _ -> request.confirm() }
            .setNegativeButton("Reject") { _, _ -> request.reject() }
            .setCancelable(false)
            .show()
    }

    private fun showPhase(phase: TerminalPhase) {
        resultView.text = when (phase) {
            TerminalPhase.Idle -> "Ready. Waiting for the cashier."
            is TerminalPhase.WaitingCard -> "${BigDecimal(phase.request.amount).movePointLeft(3)} ${phase.request.currency}\nTap, insert or swipe card (simulator)"
            is TerminalPhase.Processing -> "${BigDecimal(phase.request.amount).movePointLeft(3)} ${phase.request.currency}\n${phase.stage.wire}…"
            is TerminalPhase.Result -> "${phase.result.outcome.wire.uppercase()} ${BigDecimal(phase.result.amount).movePointLeft(3)} ${phase.result.currency}"
        }
    }

    private fun describe(result: PairingResult) = when (result) {
        is PairingResult.Paired -> "Paired with ${result.peerName}"
        is PairingResult.Failed -> "Pairing failed: ${result.message}"
    }

    // ---- screen ----

    private fun buildScreen() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        statusView = TextView(this).apply { textSize = 15f }
        resultView = TextView(this).apply { textSize = 20f; setTypeface(typeface, Typeface.BOLD); setPadding(0, pad, 0, pad) }
        logView = TextView(this).apply { textSize = 11f; typeface = Typeface.MONOSPACE }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun rebuildControls() {
        root.removeAllViews()
        val isCashier = role == "cashier"
        root.addView(TextView(this).apply { textSize = 22f; text = if (isCashier) "Cashier" else "Payment terminal" })
        root.addView(row(
            button(if (isCashier) "Be the terminal" else "Be the cashier") { role = if (isCashier) "terminal" else "cashier"; restart() },
        ))
        root.addView(row(
            button(mark("usb", "USB")) { linkType = "usb"; restart() },
            button(mark("bluetooth", "Bluetooth")) { linkType = "bluetooth"; restart() },
            button(mark("tcp", "Wi-Fi")) { linkType = "tcp"; restart() },
        ))
        root.addView(statusView)
        if (isCashier) {
            if (linkType == "bluetooth") root.addView(row(button("Choose paired terminal") { chooseBluetoothTerminal() }))
            if (linkType == "tcp") root.addView(row(button("Terminal IP: ${prefs.getString("tcpHost", "192.168.1.50")}") { askTcpHost() }))
            val amount = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                hint = "Amount (LYD), e.g. 12.500"
            }
            root.addView(amount)
            root.addView(row(button("Charge") { charge(amount.text.toString()) }, button("Pair with terminal") { pairWithTerminal() }))
        } else {
            root.addView(row(button("Pair a cashier") { terminal?.openPairing(); resultView.text = "Pairing open for 2 minutes. Start pairing on the cashier." }))
            root.addView(row(
                button("Card: approve") { payment?.presentCard(true) },
                button("Card: decline") { payment?.presentCard(false) },
                button("Cancel") { terminal?.cancelOnTerminal() },
            ))
        }
        root.addView(resultView)
        root.addView(TextView(this).apply { text = "Log"; setTypeface(typeface, Typeface.BOLD) })
        root.addView(logView)
    }

    private fun askTcpHost() {
        val input = EditText(this).apply { setText(prefs.getString("tcpHost", "192.168.1.50")) }
        AlertDialog.Builder(this).setTitle("Terminal IP address").setView(input)
            .setPositiveButton("OK") { _, _ -> prefs.edit().putString("tcpHost", input.text.toString().trim()).apply(); restart() }
            .show()
    }

    private fun mark(type: String, label: String) = if (linkType == type) "● $label" else label

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }

    private fun log(message: String) {
        android.util.Log.d("PosLinkSample", message)
        ui.launch {
            logLines.addFirst(message)
            while (logLines.size > 150) logLines.removeLast()
            logView.text = logLines.joinToString("\n")
        }
    }
}
