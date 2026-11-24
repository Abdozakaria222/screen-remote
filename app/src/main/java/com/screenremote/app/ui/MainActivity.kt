package com.screenremote.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.screenremote.app.R
import com.screenremote.app.discovery.DeviceDiscovery
import com.screenremote.app.model.DiscoveredDevice

class MainActivity : AppCompatActivity() {

    private lateinit var discovery: DeviceDiscovery
    private lateinit var adapter: DeviceListAdapter
    private lateinit var progress: ProgressBar
    private lateinit var empty: TextView
    private lateinit var scanButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        progress = findViewById(R.id.progress)
        empty = findViewById(R.id.empty)
        scanButton = findViewById(R.id.scanButton)

        adapter = DeviceListAdapter { device -> openRemote(device) }
        val list = findViewById<RecyclerView>(R.id.deviceList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        discovery = DeviceDiscovery(this)
        scanButton.setOnClickListener { startScan() }
        startScan()
    }

    private fun startScan() {
        adapter.submit(emptyList())
        empty.visibility = View.GONE
        progress.visibility = View.VISIBLE
        scanButton.isEnabled = false

        discovery.stop()
        discovery.start { devices -> adapter.submit(devices) }

        // نوقف مؤشر التقدم بعد انتهاء المسح تقريباً
        progress.postDelayed({
            progress.visibility = View.GONE
            scanButton.isEnabled = true
            empty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
        }, 27000)
    }

    private fun openRemote(device: DiscoveredDevice) {
        val intent = Intent(this, RemoteActivity::class.java).apply {
            putExtra(RemoteActivity.EXTRA_NAME, device.name)
            putExtra(RemoteActivity.EXTRA_IP, device.ip)
            putExtra(RemoteActivity.EXTRA_PORT, device.port)
            putExtra(RemoteActivity.EXTRA_TYPE, device.type.name)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        discovery.stop()
    }
}
