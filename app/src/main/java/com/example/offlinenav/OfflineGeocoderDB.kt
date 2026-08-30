package com.example.offlinenav

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

class OfflineGeocoderDB(private val context: Context) :

    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "places.db"
        private const val DB_VERSION = 1
    }
    fun deleteBookmark(name: String) {
        writableDatabase.delete("user_bookmarks", "name = ?", arrayOf(name))
    }

    // Properly define dbFile so both the copier and the searcher can use it
    private val dbFile: File get() = context.getDatabasePath(DB_NAME)

    init {
        copyDatabaseIfNeeded()
    }
    fun createBookmarksTable() {
        writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS user_bookmarks (id INTEGER PRIMARY KEY, name TEXT, lat REAL, lon REAL)")
    }

    fun addBookmark(name: String, lat: Double, lon: Double) {
        val values = android.content.ContentValues().apply {
            put("name", name)
            put("lat", lat)
            put("lon", lon)
        }
        writableDatabase.insert("user_bookmarks", null, values)
    }

    fun getBookmarks(): List<Pair<String, Pair<Double, Double>>> {
        val list = mutableListOf<Pair<String, Pair<Double, Double>>>()
        val cursor = readableDatabase.rawQuery("SELECT name, lat, lon FROM user_bookmarks", null)
        while (cursor.moveToNext()) {
            list.add(cursor.getString(0) to Pair(cursor.getDouble(1), cursor.getDouble(2)))
        }
        cursor.close()
        return list
    }

    private fun copyDatabaseIfNeeded() {
        if (!dbFile.exists() || dbFile.length() == 0L) {
            try {
                dbFile.parentFile?.mkdirs()
                context.assets.open(DB_NAME).use { input: InputStream ->
                    FileOutputStream(dbFile).use { output: OutputStream ->
                        input.copyTo(output)
                    }
                }
                Log.d("OfflineGeocoderDB", "Successfully copied places.db from assets.")
            } catch (e: Exception) {
                Log.e("OfflineGeocoderDB", "Error copying places.db from assets: ${e.message}", e)
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase?) {
        // Not needed, our DB is pre-built
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        // Not needed for now
    }

    fun searchPlaces(
        query: String,
        userLat: Double? = null,
        userLon: Double? = null
    ): List<Pair<String, Pair<Double, Double>>> {
        val db = readableDatabase

        // 1. Fuzzy Matching: Surround the query with % to catch partial words
        val likeQuery = "%${query.trim()}%"

        // Pull up to 100 loose matches, ignoring case sensitivity
        val cursor = db.rawQuery(
            "SELECT name, lat, lon FROM places WHERE name LIKE ? COLLATE NOCASE LIMIT 100",
            arrayOf(likeQuery)
        )

        // Data class to hold temporary results
        data class PlaceResult(val name: String, val lat: Double, val lon: Double, var distance: Float = 0f)
        val tempResults = mutableListOf<PlaceResult>()

        if (cursor.moveToFirst()) {
            do {
                val name = cursor.getString(0)
                val lat = cursor.getDouble(1)
                val lon = cursor.getDouble(2)

                val place = PlaceResult(name, lat, lon)

                // 2. Spatial Sorting: If we have GPS, calculate exact distance in meters
                if (userLat != null && userLon != null) {
                    val distResults = FloatArray(1)
                    android.location.Location.distanceBetween(userLat, userLon, lat, lon, distResults)
                    place.distance = distResults[0]
                }

                tempResults.add(place)
            } while (cursor.moveToNext())
        }
        cursor.close()

        // Sort by distance (closest first) if GPS is active
        if (userLat != null && userLon != null) {
            tempResults.sortBy { it.distance }
        }

        // Return the top 5 most relevant results formatted for MainActivity
        return tempResults.take(5).map { it.name to Pair(it.lat, it.lon) }
    }

    fun getNearbyLandmark(turnLat: Double, turnLng: Double, radiusMeters: Double = 30.0): String? {
        if (!dbFile.exists() || dbFile.length() == 0L) return null

        // 1 degree of latitude is roughly 111,000 meters
        val degreeOffset = radiusMeters / 111000.0
        val minLat = turnLat - degreeOffset
        val maxLat = turnLat + degreeOffset
        val minLng = turnLng - degreeOffset
        val maxLng = turnLng + degreeOffset

        var db: SQLiteDatabase? = null
        var closestLandmark: String? = null
        var minDistance = Float.MAX_VALUE

        try {
            db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)

            // Fast bounding box query
            val cursor = db.rawQuery(
                "SELECT name, lat, lng FROM places WHERE lat BETWEEN ? AND ? AND lng BETWEEN ? AND ?",
                arrayOf(minLat.toString(), maxLat.toString(), minLng.toString(), maxLng.toString())
            )

            if (cursor.moveToFirst()) {
                val results = FloatArray(1)
                do {
                    val name = cursor.getString(0)
                    val placeLat = cursor.getDouble(1)
                    val placeLng = cursor.getDouble(2)

                    // Strict distance check to ensure it's within the true circle radius
                    android.location.Location.distanceBetween(
                        turnLat,
                        turnLng,
                        placeLat,
                        placeLng,
                        results
                    )
                    val distance = results[0]

                    if (distance <= radiusMeters && distance < minDistance) {
                        // Filter out empty names or generic letter codes
                        if (name.isNotBlank() && name.length > 2) {
                            minDistance = distance
                            closestLandmark = name
                        }
                    }
                } while (cursor.moveToNext())
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e("OfflineGeocoderDB", "Landmark search failed: ${e.message}", e)
        } finally {
            db?.close()
        }

        return closestLandmark
    }
}