package com.screenremote.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.screenremote.app.R
import com.screenremote.app.model.DiscoveredDevice

class DeviceListAdapter(
    private val onClick: (DiscoveredDevice) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.VH>() {

    private val items = mutableListOf<DiscoveredDevice>()

    fun submit(list: List<DiscoveredDevice>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val initial: TextView = itemView.findViewById(R.id.deviceInitial)
        private val name: TextView = itemView.findViewById(R.id.deviceName)
        private val subtitle: TextView = itemView.findViewById(R.id.deviceSubtitle)

        fun bind(device: DiscoveredDevice) {
            name.text = device.name
            subtitle.text = "${device.type.label} · ${device.ip}"
            initial.text = device.name.trim().firstOrNull()?.uppercase() ?: "?"
            itemView.setOnClickListener { onClick(device) }
        }
    }
}
