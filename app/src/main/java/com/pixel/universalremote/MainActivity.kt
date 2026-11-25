package com.pixel.universalremote

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * Pixel Universal Remote v2 — pantalla principal.
 * Lista de TVs guardadas + control remoto. Todo en español.
 * La red siempre corre en hilos de fondo; la UI solo se toca en el hilo principal.
 */
class MainActivity : Activity() {

    private lateinit var store: TvStore
    private var devices = mutableListOf<TvDevice>()
    private lateinit var listAdapter: ArrayAdapter<String>

    private var currentDevice: TvDevice? = null
    private var session: TvSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = TvStore(this)
        devices = store.load()

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        val tvList = findViewById<ListView>(R.id.tvList)
        tvList.adapter = listAdapter
        tvList.setOnItemClickListener { _, _, position, _ ->
            openRemote(devices[position])
        }
        tvList.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(devices[position])
            true
        }

        findViewById<Button>(R.id.btnDiscover).setOnClickListener { discoverTvs() }
        findViewById<Button>(R.id.btnAdd).setOnClickListener { showAddDialog(null) }
        findViewById<Button>(R.id.btnBackToList).setOnClickListener { closeRemote() }

        bindRemoteButtons()
        refreshList()
    }

    override fun onDestroy() {
        session?.close()
        super.onDestroy()
    }

    // ---------- Lista de TVs ----------

    private fun refreshList() {
        val items = devices.map { it.toString() }
        listAdapter.clear()
        listAdapter.addAll(items)
        listAdapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.tvEmpty).visibility =
            if (devices.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmDelete(device: TvDevice) {
        AlertDialog.Builder(this)
            .setTitle("Quitar TV")
            .setMessage("¿Quitar ${device.name} (${device.ip}) de la lista?")
            .setPositiveButton("Quitar") { _, _ ->
                devices.remove(device)
                store.save(devices)
                refreshList()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showAddDialog(found: DiscoveredTv?) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val etName = EditText(this).apply {
            hint = "Nombre (ej. TV de la sala)"
            setText(found?.let { "TV ${it.brand.label()}" } ?: "")
        }
        val etIp = EditText(this).apply {
            hint = "IP local (ej. 192.168.1.40)"
            setText(found?.ip ?: "")
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                Brand.values().map { it.label() }
            )
            if (found != null) setSelection(found.brand.ordinal)
        }
        layout.addView(etName)
        layout.addView(etIp)
        layout.addView(spinner)
        AlertDialog.Builder(this)
            .setTitle(if (found == null) "Agregar TV manualmente" else "Agregar TV encontrada")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val name = etName.text.toString().trim().ifEmpty { "TV" }
                val ip = etIp.text.toString().trim()
                if (ip.isEmpty()) {
                    Toast.makeText(this, "Falta la IP de la TV", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val brand = Brand.values()[spinner.selectedItemPosition]
                val existing = devices.firstOrNull { it.ip == ip }
                if (existing != null) {
                    existing.name = name
                    existing.brand = brand
                } else {
                    devices.add(TvDevice(name = name, ip = ip, brand = brand))
                }
                store.save(devices)
                refreshList()
                Toast.makeText(this, "TV guardada", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun discoverTvs() {
        Toast.makeText(this, "Buscando TVs en Wi-Fi…", Toast.LENGTH_SHORT).show()
        Thread {
            val found = SsdpDiscovery.discover()
            runOnUiThread {
                if (found.isEmpty()) {
                    Toast.makeText(
                        this,
                        "No se encontró ninguna TV. Revisa que estén en la misma Wi-Fi o agrega la IP manual.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                val newOnes = found.filter { f -> devices.none { it.ip == f.ip } }
                if (newOnes.isEmpty()) {
                    Toast.makeText(this, "No hay TVs nuevas: ya están todas guardadas.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                if (newOnes.size == 1) {
                    showAddDialog(newOnes.first())
                } else {
                    val names = newOnes.map { "${it.brand.label()} — ${it.ip}" }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("TVs encontradas")
                        .setItems(names) { _, which ->
                            showAddDialog(newOnes[which])
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }
        }.start()
    }

    // ---------- Control remoto ----------

    private fun openRemote(device: TvDevice) {
        currentDevice = device
        findViewById<View>(R.id.viewList).visibility = View.GONE
        findViewById<View>(R.id.viewRemote).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvRemoteTitle).text =
            "${device.name} (${device.brand.label()})"
        setStatus("Conectando a ${device.ip}…")
        Thread {
            val s = createSession(device)
            val ok = try {
                s.open()
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                if (ok) {
                    session = s
                    val proto = when (device.protocol) {
                        "legacy" -> "Samsung clásico"
                        "tizen" -> "Samsung Tizen"
                        "ecp" -> "Roku"
                        "webos" -> "LG webOS"
                        else -> device.brand.label()
                    }
                    setStatus("Conectado · $proto")
                } else {
                    s.close()
                    setStatus(
                        "No se pudo conectar. Revisa la IP y que la TV esté en la misma Wi-Fi."
                    )
                    Toast.makeText(
                        this,
                        "No se pudo conectar a ${device.ip}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun createSession(device: TvDevice): TvSession {
        return when (device.brand) {
            Brand.SAMSUNG -> SamsungSession(
                device,
                onProtocol = { p ->
                    device.protocol = p
                    store.save(devices)
                },
                onToken = { t ->
                    device.token = t
                    store.save(devices)
                }
            )
            Brand.ROKU -> RokuSession(device.ip)
            Brand.LG -> LgWebOsSession(
                device.ip,
                device.token,
                onClientKey = { k ->
                    device.token = k
                    device.protocol = "webos"
                    store.save(devices)
                }
            )
        }
    }

    private fun closeRemote() {
        Thread { session?.close() }.start()
        session = null
        currentDevice = null
        findViewById<View>(R.id.viewRemote).visibility = View.GONE
        findViewById<View>(R.id.viewList).visibility = View.VISIBLE
    }

    private fun setStatus(text: String) {
        findViewById<TextView>(R.id.tvStatus).text = text
    }

    private fun sendKey(key: RemoteKey) {
        val s = session
        if (s == null) {
            Toast.makeText(this, "Aún no hay conexión con la TV", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val ok = try {
                s.sendKey(key)
            } catch (e: Exception) {
                false
            }
            if (!ok) {
                runOnUiThread {
                    Toast.makeText(this, "No se pudo enviar la tecla", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun bindRemoteButtons() {
        fun bind(id: Int, key: RemoteKey) {
            findViewById<Button>(id).setOnClickListener { sendKey(key) }
        }
        bind(R.id.btnPower, RemoteKey.POWER)
        bind(R.id.btnHome, RemoteKey.HOME)
        bind(R.id.btnSource, RemoteKey.SOURCE)
        bind(R.id.btnUp, RemoteKey.UP)
        bind(R.id.btnDown, RemoteKey.DOWN)
        bind(R.id.btnLeft, RemoteKey.LEFT)
        bind(R.id.btnRight, RemoteKey.RIGHT)
        bind(R.id.btnOk, RemoteKey.OK)
        bind(R.id.btnBack, RemoteKey.BACK)
        bind(R.id.btnVolUp, RemoteKey.VOL_UP)
        bind(R.id.btnVolDown, RemoteKey.VOL_DOWN)
        bind(R.id.btnMute, RemoteKey.MUTE)
        bind(R.id.btnChUp, RemoteKey.CH_UP)
        bind(R.id.btnChDown, RemoteKey.CH_DOWN)
        bind(R.id.btn0, RemoteKey.DIGIT_0)
        bind(R.id.btn1, RemoteKey.DIGIT_1)
        bind(R.id.btn2, RemoteKey.DIGIT_2)
        bind(R.id.btn3, RemoteKey.DIGIT_3)
        bind(R.id.btn4, RemoteKey.DIGIT_4)
        bind(R.id.btn5, RemoteKey.DIGIT_5)
        bind(R.id.btn6, RemoteKey.DIGIT_6)
        bind(R.id.btn7, RemoteKey.DIGIT_7)
        bind(R.id.btn8, RemoteKey.DIGIT_8)
        bind(R.id.btn9, RemoteKey.DIGIT_9)
    }
}
