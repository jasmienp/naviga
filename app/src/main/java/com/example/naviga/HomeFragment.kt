package com.example.naviga

import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.button.MaterialButton

class HomeFragment : Fragment(R.layout.fragment_home) {

    private lateinit var all: List<CampusLocation>
    private lateinit var map: PhotoView
    private lateinit var overlay: BuildingOverlayView
    private lateinit var resultsCard: View
    private lateinit var destCard: View
    private lateinit var adapter: ResultsAdapter
    private lateinit var buttons: List<MaterialButton>

    private var query = ""
    private var category = "All"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        all = LocationRepository.load(requireContext())

        map = view.findViewById(R.id.mapView)
        overlay = view.findViewById(R.id.buildingOverlay)
        resultsCard = view.findViewById(R.id.resultsCard)
        destCard = view.findViewById(R.id.destinationCard)

        // real pixel size of map.jpg (cutout positions are in these units)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeResource(resources, R.drawable.map, opts)
        overlay.mapWidth = opts.outWidth.toFloat()
        overlay.mapHeight = opts.outHeight.toFloat()
        overlay.buildings = all

        map.minimumScale = 1f
        map.mediumScale = 2.5f
        map.maximumScale = 5f

        // keep the overlay aligned with the image while zooming/panning
        map.setOnMatrixChangeListener { rect -> overlay.imageRect = RectF(rect) }
        map.post { map.displayRect?.let { overlay.imageRect = RectF(it) } }

        // tap a building = select it; tap anything else = clear
        map.setOnPhotoTapListener { _, x, y ->
            hideResults()
            overlay.buildingAt(x, y)?.let { select(it, zoom = false) } ?: clearSelection()
        }
        map.setOnOutsidePhotoTapListener { clearSelection() }

        view.findViewById<View>(R.id.btnClose).setOnClickListener { clearSelection() }

        // search results list
        adapter = ResultsAdapter { loc -> hideResults(); select(loc, zoom = true) }
        view.findViewById<RecyclerView>(R.id.resultsList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@HomeFragment.adapter
        }

        view.findViewById<SearchView>(R.id.searchView)
            .setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextChange(newText: String?): Boolean {
                    query = newText.orEmpty(); applyFilters(); return true
                }
                override fun onQueryTextSubmit(q: String?): Boolean {
                    adapter.currentList.firstOrNull()?.let { select(it, zoom = true) }
                    hideResults(); return true
                }
            })

        // category filters
        buttons = listOf(R.id.btnAll, R.id.btnAcademic, R.id.btnStudy, R.id.btnFood)
            .map { view.findViewById<MaterialButton>(it) }
        buttons.forEach { b ->
            b.setOnClickListener {
                category = b.text.toString()
                styleButtons(b)
                clearSelection()
                applyFilters()
            }
        }

        applyFilters()
    }

    // buildings of the chosen category stay bright; "All" shows the plain map
    private fun categoryLit() =
        if (category == "All") emptyList() else all.filter { it.category == category }

    private fun applyFilters() {
        val filtered = all.filterBy(query, category)
        adapter.submitList(filtered)
        resultsCard.visibility = if (query.isBlank() || filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun select(loc: CampusLocation, zoom: Boolean) {
        overlay.selected = loc
        overlay.lit = listOf(loc)
        showCard(loc)
        if (zoom) focusOn(loc)
    }

    private fun clearSelection() {
        overlay.selected = null
        overlay.lit = categoryLit()
        destCard.visibility = View.GONE
    }

    private fun showCard(loc: CampusLocation) {
        destCard.findViewById<TextView>(R.id.tvDestName).text = loc.name
        destCard.findViewById<TextView>(R.id.tvDestInfo).text = "${loc.category} · ${loc.description}"

        val iv = destCard.findViewById<ImageView>(R.id.ivDestPhoto)
        val resId = if (loc.photo.isEmpty()) 0
        else resources.getIdentifier(loc.photo, "drawable", requireContext().packageName)
        if (resId != 0) { iv.setImageResource(resId); iv.visibility = View.VISIBLE }
        else iv.visibility = View.GONE            // no real-life photo yet

        if (destCard.visibility != View.VISIBLE) {
            destCard.alpha = 0f
            destCard.translationY = 40 * resources.displayMetrics.density
            destCard.visibility = View.VISIBLE
            destCard.animate().alpha(1f).translationY(0f).setDuration(180).start()
        }
    }

    // zoom in and center the building a bit above the middle so the bottom card doesn't cover it
    private fun focusOn(loc: CampusLocation) {
        map.setScale(1f, false)
        val r = map.displayRect ?: return
        val s = 2.5f
        val px = r.left + (loc.left + loc.sprite.width / 2f) / overlay.mapWidth * r.width()
        val py = r.top + (loc.top + loc.sprite.height / 2f) / overlay.mapHeight * r.height()
        val fx = (map.width / 2f - s * px) / (1 - s)
        val fy = (map.height * 0.4f - s * py) / (1 - s)
        map.setScale(s, fx, fy, true)
    }

    private fun hideResults() {
        resultsCard.visibility = View.GONE
        requireView().findViewById<SearchView>(R.id.searchView).clearFocus()
    }

    private fun styleButtons(active: MaterialButton) {
        fun c(id: Int) = ContextCompat.getColor(requireContext(), id)
        buttons.forEach { b ->
            val on = b == active
            b.backgroundTintList = ColorStateList.valueOf(c(if (on) R.color.main_blue else R.color.white))
            b.setTextColor(c(if (on) R.color.white else R.color.text_light))
            b.iconTint = ColorStateList.valueOf(c(if (on) R.color.white else R.color.text_light))
            b.strokeWidth = if (on) 0 else (1.12f * resources.displayMetrics.density).toInt()
        }
    }
}