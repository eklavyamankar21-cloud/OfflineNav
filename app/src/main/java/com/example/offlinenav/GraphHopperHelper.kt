package com.example.offlinenav

import android.content.Context
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.config.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class RouteResult(
    val pathCoords: List<Pair<Double, Double>>,
    val distanceKm: Float,
    val instructions: com.graphhopper.util.InstructionList,
    val elevations: List<Double>
)

class GraphHopperHelper(private val context: Context) {
    var hopper: GraphHopper? = null
    var isInitialized = false
        private set

    suspend fun initEngine(): Boolean = withContext(Dispatchers.IO) {
        try {
            val graphFolder = File(context.filesDir, "graphhopper_data")
            if (!graphFolder.exists()) graphFolder.mkdirs()

            val pbfFile = File(graphFolder, "telangana.osm.pbf")

            if (!pbfFile.exists()) {
                context.assets.open("telangana.osm.pbf").use { input ->
                    FileOutputStream(pbfFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            hopper = GraphHopper().apply {
                osmFile = pbfFile.absolutePath
                graphHopperLocation = graphFolder.absolutePath
                profiles = listOf(
                    Profile("car").setVehicle("car").setWeighting("fastest"),
                    Profile("bike").setVehicle("bike").setWeighting("fastest"),
                    Profile("foot").setVehicle("foot").setWeighting("fastest"),
                )
            }

            hopper?.importOrLoad()
            isInitialized = true
            true
        } catch (e: Exception) {
            android.util.Log.e("GraphHopperError", "Engine crashed violently: ${e.message}", e)
            e.printStackTrace()
            false
        }
    }

    suspend fun calculateRoute(
        startLat: Double, startLng: Double,
        destLat: Double, destLng: Double,
        profile: String
    ): RouteResult? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (hopper == null) return@withContext null

        try {
            val req = com.graphhopper.GHRequest(startLat, startLng, destLat, destLng)
                .setProfile(profile)
                .setLocale(java.util.Locale.US)

            val rsp = hopper!!.route(req)

            if (rsp.hasErrors()) {
                println("Routing errors: ${rsp.errors}")
                return@withContext null
            }

            val path = rsp.best
            val points = path.points
            val distance = (path.distance / 1000.0).toFloat()

            val coords = mutableListOf<Pair<Double, Double>>()
            val elevations = mutableListOf<Double>()

            for (i in 0 until points.size()) {
                coords.add(Pair(points.getLat(i), points.getLon(i)))

                val altitude = if (points.is3D) points.getEle(i) else 0.0
                elevations.add(altitude)
            }

            return@withContext RouteResult(coords, distance, path.instructions, elevations)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }
}