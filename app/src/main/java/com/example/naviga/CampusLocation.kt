package com.example.naviga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONArray

data class CampusLocation(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val sprite: Bitmap,     // the building cutout
    val left: Float,        // cutout position in map.jpg pixels
    val top: Float,
    val photo: String       // real-life photo drawable name (may not exist yet)
)

object LocationRepository {
    fun load(context: Context): List<CampusLocation> {
        val json = context.assets.open("locations.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        val res = context.resources
        val opts = BitmapFactory.Options().apply { inScaled = false }   // keep native pixel size
        val list = mutableListOf<CampusLocation>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val spriteName = o.getString("sprite")
            val id = res.getIdentifier(spriteName, "drawable", context.packageName)
            if (id == 0) { Log.w("Naviga", "Missing sprite: $spriteName"); continue }
            list += CampusLocation(
                o.getString("id"), o.getString("name"), o.getString("category"),
                o.getString("description"),
                BitmapFactory.decodeResource(res, id, opts),
                o.getDouble("left").toFloat(), o.getDouble("top").toFloat(),
                o.optString("photo")
            )
        }
        return list
    }
}

fun List<CampusLocation>.filterBy(query: String, category: String) = filter {
    (category == "All" || it.category == category) &&
            (query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true))
}