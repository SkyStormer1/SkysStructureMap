package com.skystormer.skysstructuremap

import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Writes the mod's files one after another on a thread of its own, away from the game: the
 * structures file alone is 19 MB on a server played for a while, and writing it on the game's
 * thread froze the game for 80 to 180 ms every time something nearby changed.
 *
 * Whatever is written is a copy made on the game's thread when asked ([later]), so nothing the
 * game goes on changing is read halfway. Written in the order asked, so an older copy never lands
 * over a newer one.
 */
object Saves {

    private val writer = Executors.newSingleThreadExecutor { Thread(it, "Sky's Structure Map save").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    /** The last write asked for, waited on by [flush]. */
    @Volatile private var last: Future<*>? = null

    /** Runs [write] on the writer, after everything asked before it. A failure is logged as [what]. */
    fun later(what: String, write: () -> Unit) {
        last = writer.submit {
            try {
                write()
            } catch (e: Exception) {
                Log.error("Could not save $what", e)
            }
        }
    }

    /** Waits until everything asked so far is written: on leaving a world, and before a file is copied. */
    fun flush() {
        try {
            last?.get()
        } catch (e: Exception) {
            Log.error("Waiting for files to be saved failed", e)
        }
    }

    /** Runs [read] on the writer, so it never reads a file halfway through being written. */
    fun <T> read(read: () -> T): Future<T> = writer.submit(read)
}
