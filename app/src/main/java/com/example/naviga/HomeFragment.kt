package com.example.naviga

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.github.chrisbanes.photoview.PhotoView

class HomeFragment : Fragment(R.layout.fragment_home) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mapView = view.findViewById<PhotoView>(R.id.mapView)
        mapView.minimumScale = 1f
        mapView.mediumScale = 2.5f
        mapView.maximumScale = 5f   // lets users zoom in far enough to read building labels
    }
}