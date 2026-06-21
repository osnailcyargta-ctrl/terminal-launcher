package com.osnailcyargta.launcher

import android.graphics.Color
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val onLaunch: (AppInfo) -> Unit,
    private val onLongPress: (AppInfo, View) -> Unit
) : ListAdapter<AppInfo, AppAdapter.VH>(DIFF) {

    private var textColor: Int = Color.parseColor("#00ff88")

    fun setTextColor(color: Int) {
        textColor = color
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivIcon)
        val label: TextView = view.findViewById(R.id.tvLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = getItem(position)
        holder.icon.setImageDrawable(app.icon)
        holder.label.text = app.label
        holder.label.setTextColor(textColor)
        holder.itemView.setOnClickListener { onLaunch(app) }
        holder.itemView.setOnLongClickListener {
            onLongPress(app, holder.itemView)
            true
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(a: AppInfo, b: AppInfo) = a.packageName == b.packageName
            override fun areContentsTheSame(a: AppInfo, b: AppInfo) = a.label == b.label
        }
    }
}
