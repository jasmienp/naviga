package com.example.naviga

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class ResultsAdapter(private val onClick: (CampusLocation) -> Unit) :
    ListAdapter<CampusLocation, ResultsAdapter.VH>(object : DiffUtil.ItemCallback<CampusLocation>() {
        override fun areItemsTheSame(a: CampusLocation, b: CampusLocation) = a.id == b.id
        override fun areContentsTheSame(a: CampusLocation, b: CampusLocation) = a.id == b.id
    }) {

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_result, parent, false) as TextView
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val loc = getItem(position)
        holder.tv.text = "${loc.name}  ·  ${loc.category}"
        holder.tv.setOnClickListener { onClick(loc) }
    }
}