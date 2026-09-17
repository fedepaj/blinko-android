package com.federicopaglioni.blinko

import java.nio.ByteBuffer

/** Bridge to the shared C core (core/): profile extraction, decoder, assembler. */
object RsCore {
    init { System.loadLibrary("blinko") }

    external fun reset()
    /** Returns packets decoded in this frame; fills stats[12], profile (downsampled luma), packets (start,end,slot,channel)*n.
     *  u/v may be null (luma-only). */
    external fun processFrame(y: ByteBuffer, yRs: Int, yPs: Int, u: ByteBuffer?, uRs: Int, uPs: Int, v: ByteBuffer?, vRs: Int, vPs: Int,
                              w: Int, h: Int, axis: Int, t: Float,
                              stats: FloatArray?, profile: FloatArray?, packets: FloatArray?): Int
    /** RGBA_8888 frame: full-resolution colour. */
    external fun processFrameRgba(px: ByteBuffer, rowStride: Int, pixelStride: Int, w: Int, h: Int, axis: Int, t: Float,
                                  stats: FloatArray?, profile: FloatArray?, packets: FloatArray?): Int
    /** Multi-source on RGBA: fills tracks (id, x, y, radius, mode, packets, messages, group)*n, packets[0]; returns track count or -1. */
    external fun processFrameRgbaMulti(px: ByteBuffer, rowStride: Int, pixelStride: Int, w: Int, h: Int, t: Float,
                                       tracks: FloatArray?, packets: IntArray?): Int
    /** "slot|level|text|track" or null. */
    external fun pollMessage(): String?
    external fun slotProgress(slot: Int): Float

    val levelNames = arrayOf("DEBUG", "INFO", "WARN", "ERROR", "FATAL", "STATUS", "FAULT", "?")
}
