package com.autologpro.app

import android.os.Handler
import android.os.Looper

/** A→B display-only event contract. Call only AFTER C repository commits the closing trip.
 *  Never use this to store a second trip, and never simulate hardware data as real.
 *  B remains in DEMO mode until C Room adapter and real Vehicle SDK are integrated.
 */
object VehicleUiEvents {
    sealed class Message {
        data class Driving(val isDriving: Boolean) : Message()
        data class TripClosed(val tripId: Long) : Message()
    }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var listener: ((Message) -> Unit)? = null

    fun attach(callback: (Message) -> Unit) { listener = callback }
    fun detach(callback: (Message) -> Unit) { if (listener === callback) listener = null }

    fun publishDriving(isDriving: Boolean) = dispatch(Message.Driving(isDriving))
    fun publishClosedTrip(tripId: Long) = dispatch(Message.TripClosed(tripId))

    private fun dispatch(message: Message) {
        main.post { listener?.invoke(message) }
    }
}
