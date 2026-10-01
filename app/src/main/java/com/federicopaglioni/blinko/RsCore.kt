package com.federicopaglioni.blinko

import java.nio.ByteBuffer

/** Bridge to the shared C core (core/): profile extraction, decoder, assembler, multi-source tracker. */
object RsCore {
    init { System.loadLibrary("blinko") }

    external fun reset()
    /** Returns packets decoded in this frame; fills stats[14], profile (downsampled luma), packets (start,end,slot,channel)*n.
     *  u/v may be null (luma-only). */
    external fun processFrame(y: ByteBuffer, yRs: Int, yPs: Int, u: ByteBuffer?, uRs: Int, uPs: Int, v: ByteBuffer?, vRs: Int, vPs: Int,
                              w: Int, h: Int, axis: Int, t: Float,
                              stats: FloatArray?, profile: FloatArray?, packets: FloatArray?): Int
    /** RGBA_8888 frame: full-resolution colour. */
    external fun processFrameRgba(px: ByteBuffer, rowStride: Int, pixelStride: Int, w: Int, h: Int, axis: Int, t: Float,
                                  stats: FloatArray?, profile: FloatArray?, packets: FloatArray?): Int
    /** Packed BGRA frame (row stride 4*w), the converter's / recorder's layout. Same outputs as processFrameRgba. */
    external fun processFrameBgra(px: ByteBuffer, w: Int, h: Int, axis: Int, t: Float,
                                  stats: FloatArray?, profile: FloatArray?, packets: FloatArray?): Int
    /** Multi-source on RGBA: fills tracks (id, x, y, radius, mode, packets, messages, group)*n, packets[0]; returns track count or -1. */
    external fun processFrameRgbaMulti(px: ByteBuffer, rowStride: Int, pixelStride: Int, w: Int, h: Int, t: Float,
                                       tracks: FloatArray?, packets: IntArray?): Int
    /** Multi-source on a packed BGRA frame. */
    external fun processFrameBgraMulti(px: ByteBuffer, w: Int, h: Int, t: Float, tracks: FloatArray?, packets: IntArray?): Int
    /** YUV_420_888 planes -> packed BGRA (w/step columns, h rows) into `out` (direct). Returns the output width, 0 on error. */
    external fun convertYuvToBgra(y: ByteBuffer, yRs: Int, yPs: Int, u: ByteBuffer, uRs: Int, uPs: Int, v: ByteBuffer, vRs: Int, vPs: Int,
                                  w: Int, h: Int, step: Int, out: ByteBuffer): Int
    /** RGBA_8888 -> packed BGRA, columns subsampled by step. Returns the output width. */
    external fun convertRgbaToBgra(px: ByteBuffer, rowStride: Int, pixelStride: Int, w: Int, h: Int, step: Int, out: ByteBuffer): Int
    /** RAW_SENSOR (16-bit Bayer) frame: per-row R/G/B profiles from the mosaic, single receiver. Same outputs as processFrameBgra. */
    external fun processFrameRaw(px: ByteBuffer, rowStride: Int, w: Int, h: Int, cfa: Int, black: Int, white: Int, t: Float,
                                 stats: FloatArray?, profile: FloatArray?, packets: FloatArray?): Int
    /** Last r/g/b profiles given to the receiver (RAW path): out = r[n] g[n] b[n]; returns n. */
    external fun lastProfiles(out: FloatArray): Int
    /** Decoder: minimum local contrast (max-min over a chip window) to trust a row; default 6. */
    external fun setMinContrast(v: Float)
    /** Camera exposure in sensor rows (exposure µs / row µs); 0 = unknown. */
    external fun setExposureRows(rows: Float)
    /** "slot|level|text|track" or null. */
    external fun pollMessage(): String?
    external fun slotProgress(slot: Int): Float

    val levelNames = arrayOf("DEBUG", "INFO", "WARN", "ERROR", "FATAL", "STATUS", "FAULT", "?")

    /** Stats layout written by processFrame*: indices into the FloatArray(14). */
    const val ST_SYNCS = 0; const val ST_CRC_FAIL = 1; const val ST_CONTRAST = 2; const val ST_RPC = 3
    const val ST_ROI0 = 4; const val ST_ROI1 = 5; const val ST_COUNT = 6; const val ST_MESSAGES = 7
    const val ST_MODE = 8; const val ST_PILOTS = 9; const val ST_COND = 10; const val ST_PACKETS = 11
    const val ST_PEAK = 12; const val ST_SAT = 13
}
