package com.federicopaglioni.blinko

import java.nio.ByteBuffer

/** Bridge to the shared C core (core/): profile extraction, decoder, assembler, multi-source tracker. */
object RsCore {
    init { System.loadLibrary("blinko") }

    external fun reset()
    /** Packed BGRA frame (row stride 4*w), the converter's / recorder's layout, through the single receiver.
     *  Returns packets decoded in this frame; fills stats[15], profile (downsampled luma), packets (start,end,slot,channel)*n. */
    external fun processFrameBgra(px: ByteBuffer, w: Int, h: Int, axis: Int, t: Float,
                                  stats: FloatArray?, profile: FloatArray?, packets: FloatArray?): Int
    /** Multi-source on a packed BGRA frame: fills tracks (id, x, y, radius, mode, packets, messages, group)*n, packets[0]; returns track count or -1. */
    external fun processFrameBgraMulti(px: ByteBuffer, w: Int, h: Int, t: Float, tracks: FloatArray?, packets: IntArray?): Int
    /** Stats and profile of a packed BGRA frame for the UI while the multi-source receiver decodes it: nothing is decoded here
     *  (the receiver's fields of the stats are those of the track with the most packets). */
    external fun profileBgra(px: ByteBuffer, w: Int, h: Int, axis: Int, stats: FloatArray?, profile: FloatArray?)
    /** YUV_420_888 planes -> packed BGRA (w/step columns, h rows) into `out` (direct). Returns the output width, 0 on error. */
    external fun convertYuvToBgra(y: ByteBuffer, yRs: Int, yPs: Int, u: ByteBuffer, uRs: Int, uPs: Int, v: ByteBuffer, vRs: Int, vPs: Int,
                                  w: Int, h: Int, step: Int, out: ByteBuffer): Int
    /** RGBA_8888 -> packed BGRA, columns subsampled by step. Returns the output width. */
    external fun convertRgbaToBgra(px: ByteBuffer, rowStride: Int, pixelStride: Int, w: Int, h: Int, step: Int, out: ByteBuffer): Int
    /** RAW_SENSOR (16-bit Bayer) -> packed BGRA, reduced: one pixel from a 2x2 block per `rowBin` sensor rows (h/rowBin rows),
     *  block columns subsampled by step ((w/2)/step columns), clipping at 255. Returns the output width. */
    external fun convertRawToBgra(px: ByteBuffer, rowStride: Int, w: Int, h: Int, cfa: Int, black: Int, white: Int, step: Int, rowBin: Int, out: ByteBuffer): Int
    /** RAW_SENSOR (16-bit Bayer) frame: per-row R/G/B profiles from the mosaic, single receiver (the lab's full-resolution path). Same outputs as processFrameBgra. */
    external fun processFrameRaw(px: ByteBuffer, rowStride: Int, w: Int, h: Int, cfa: Int, black: Int, white: Int, t: Float,
                                 stats: FloatArray?, profile: FloatArray?, packets: FloatArray?): Int
    /** Last r/g/b profiles given to the receiver (RAW path): out = r[n] g[n] b[n]; returns n. */
    external fun lastProfiles(out: FloatArray): Int
    /** Decoder: minimum local contrast (max-min over a chip window) to trust a row; default 6. */
    external fun setMinContrast(v: Float)
    /** Camera exposure in rows of the image the receiver decodes (exposure µs / row µs); 0 = unknown. */
    external fun setExposureRows(rows: Float)
    /** Time of one such row in seconds (stitching of repeated packets across frames); 0 = unknown. */
    external fun setRowTime(seconds: Float)

    /** A complete message; `source` is the logical source (group id) of the light, 0 from the single receiver. */
    class Message(val slot: Int, val level: Int, val text: String, val source: Int)
    /** meta = (slot, level, source), the text's bytes in `text`; returns their number, -1 when no message is waiting. */
    private external fun nextMessage(meta: IntArray, text: ByteArray): Int
    private val msgMeta = IntArray(3); private val msgText = ByteArray(64)
    /** The next complete message, or null. The text arrives as bytes (the protocol carries any byte) and is decoded here
     *  as UTF-8, like the iOS app does; a malformed sequence becomes U+FFFD. */
    @Synchronized fun pollMessage(): Message? {
        val n = nextMessage(msgMeta, msgText)
        if (n < 0) return null
        return Message(msgMeta[0], msgMeta[1], String(msgText, 0, n, Charsets.UTF_8), msgMeta[2])
    }

    val levelNames = arrayOf("DEBUG", "INFO", "WARN", "ERROR", "FATAL", "STATUS", "FAULT", "?")

    /** Stats layout written by processFrame* / profileBgra: indices into the FloatArray(15). */
    const val ST_SYNCS = 0; const val ST_CRC_FAIL = 1; const val ST_CONTRAST = 2; const val ST_RPC = 3
    const val ST_ROI0 = 4; const val ST_ROI1 = 5; const val ST_COUNT = 6; const val ST_MESSAGES = 7
    const val ST_MODE = 8; const val ST_PILOTS = 9; const val ST_COND = 10; const val ST_PACKETS = 11
    const val ST_PEAK = 12; const val ST_SAT = 13; const val ST_STITCHED = 14   // packets stitched from pieces across frames
}
