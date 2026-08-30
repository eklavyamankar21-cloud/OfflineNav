package com.example.offlinenav

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.util.Locale

class NavigationService : Service(), TextToSpeech.OnInitListener, LocationListener {

    private val CHANNEL_ID = "NavServiceChannel"
    private var textToSpeech: TextToSpeech? = null
    private var locationManager: LocationManager? = null

    // Class-level variables to track active navigation state across GPS updates
    private var currentTurnIndex = 0
    private var hasGivenAdvanceWarning = false

    // This companion object lets MainActivity hand over the route data instantly
    companion object {
        var activeRouteCoords: List<Pair<Double, Double>> = emptyList()
        var activeInstructions: com.graphhopper.util.InstructionList? = null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        textToSpeech = TextToSpeech(this, this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        // --- NEW: Intercept dynamic turn updates from MainActivity ---
        if (intent?.action == "UPDATE_TURN") {
            val maneuver = intent.getStringExtra("maneuver") ?: "Follow the route"
            val distance = intent.getStringExtra("distance") ?: ""

            val title = if (distance.isNotEmpty()) distance else "Navigation Active"

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(maneuver)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setOnlyAlertOnce(true) // Prevents the phone from buzzing every second it updates
                .build()

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(1, notification) // Refreshes the existing foreground notification

            return START_STICKY
        }
        // -------------------------------------------------------------

        if (intent?.action == "STOP_SERVICE") {
            locationManager?.removeUpdates(this)
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()

            // Reset state for the next route
            currentTurnIndex = 0
            hasGivenAdvanceWarning = false
            return START_NOT_STICKY
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OfflineNav Active")
            .setContentText("Calculating route...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        startForeground(1, notification)

        // Request GPS updates every 1 second or 2 meters
        locationManager?.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000L,
            2f,
            this
        )

        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        if (activeRouteCoords.isEmpty() || activeInstructions == null) return

        val instructions = activeInstructions!!

        if (currentTurnIndex < instructions.size) {
            val currentTurn = instructions[currentTurnIndex]
            val turnLat = currentTurn.points.getLat(0)
            val turnLon = currentTurn.points.getLon(0)

            val results = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, turnLat, turnLon, results)
            val distanceToTurn = results[0]

            // Trigger TTS when approaching a turn in the background (between 100m and 250m)
            if (distanceToTurn in 100.0f..250.0f && !hasGivenAdvanceWarning) {
                val maneuverText = generateManeuverText(currentTurn)
                textToSpeech?.speak("In ${distanceToTurn.toInt()} meters, $maneuverText", TextToSpeech.QUEUE_ADD, null, null)
                hasGivenAdvanceWarning = true
            }

            // Advance to the next instruction when you pass the turn (within 35m)
            if (distanceToTurn < 35.0f) {
                currentTurnIndex++
                hasGivenAdvanceWarning = false // Reset the warning flag for the next turn
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.US
            textToSpeech?.speak("Background tracking engaged.", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Navigation", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun generateManeuverText(turn: com.graphhopper.util.Instruction): String {
        if (turn.sign == com.graphhopper.util.Instruction.FINISH) return "Arrive at destination"

        val destinationText = "onto ${if (turn.name.isNullOrEmpty()) "the road" else turn.name}"

        return when (turn.sign) {
            com.graphhopper.util.Instruction.TURN_RIGHT -> "Turn right $destinationText"
            com.graphhopper.util.Instruction.TURN_LEFT -> "Turn left $destinationText"
            com.graphhopper.util.Instruction.TURN_SLIGHT_RIGHT -> "Keep right $destinationText"
            com.graphhopper.util.Instruction.TURN_SLIGHT_LEFT -> "Keep left $destinationText"
            com.graphhopper.util.Instruction.TURN_SHARP_RIGHT -> "Sharp right $destinationText"
            com.graphhopper.util.Instruction.TURN_SHARP_LEFT -> "Sharp left $destinationText"
            com.graphhopper.util.Instruction.CONTINUE_ON_STREET -> "Continue $destinationText"
            else -> "Head towards $destinationText"
        }
    }
}