/* JNI bridge to the shared C receiver (rs_rx): YUV planes -> R,G,B profiles -> calibration,
 * unmixing, decoding, assembly. */
#include <jni.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <android/log.h>
#include "rs_rx.h"
#include "rs_frame.h"
#include "rs_multi.h"

static rs_rx_t *g_rx;
static rs_multi_t *g_multi;
static float g_r[4096], g_g[4096], g_b[4096];

static void ensure_init(void)
{
    if (g_rx) return;
    g_rx = (rs_rx_t *)malloc(rs_rx_sizeof());
    rs_rx_init(g_rx);
    g_multi = (rs_multi_t *)malloc(rs_multi_sizeof());
    rs_multi_init(g_multi);
}

JNIEXPORT void JNICALL
Java_com_federicopaglioni_rslog_RsCore_reset(JNIEnv *env, jclass cls)
{
    ensure_init();
    rs_rx_init(g_rx);
    rs_multi_init(g_multi);
}

/* Multi-source path on an RGBA_8888 frame. tracksOut receives up to len/8 entries of
 * (id, cx/w, cy/h, radius/w, mode, packets, messages, group); returns the number of tracks
 * (or -1 when no light was found, so the caller can use the single path). Packets decoded
 * are stored in stats[11]. Messages are queued with their track id (pollMessage). */
static rs_message_t g_mq[16]; static int g_mq_track[16]; static int g_mq_len;

JNIEXPORT jint JNICALL
Java_com_federicopaglioni_rslog_RsCore_processFrameRgbaMulti(JNIEnv *env, jclass cls, jobject buf, jint rowStride, jint pixelStride,
        jint w, jint h, jfloat t, jfloatArray tracksOut, jintArray packetsOut)
{
    ensure_init();
    const uint8_t *px = (const uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
    if (!px || w <= 0 || h <= 0) return -1;
    int n = rs_multi_process(g_multi, px, w, h, rowStride, pixelStride, 0, 1, 2, t);
    int count = rs_multi_track_count(g_multi);
    if (count == 0) return -1;
    if (packetsOut) { jint v = n; (*env)->SetIntArrayRegion(env, packetsOut, 0, 1, &v); }
    if (tracksOut) {
        jsize m = (*env)->GetArrayLength(env, tracksOut) / 8;
        jfloat tmp[8 * RS_MAX_TRACKS]; int k = 0;
        for (int i = 0; i < count && i < m; i++) {
            int id, mode, pilots; float cx, cy, rad; uint32_t pk, ms;
            if (!rs_multi_track_info(g_multi, i, &id, &cx, &cy, &rad, &mode, &pk, &ms, &pilots)) break;
            tmp[k++] = (float)id; tmp[k++] = cx / (float)w; tmp[k++] = cy / (float)h; tmp[k++] = rad / (float)w;
            tmp[k++] = (float)mode; tmp[k++] = (float)pk; tmp[k++] = (float)ms; tmp[k++] = (float)rs_multi_track_group(g_multi, i);
        }
        if (k) (*env)->SetFloatArrayRegion(env, tracksOut, 0, k, tmp);
    }
    rs_message_t msg; int tid;
    while (g_mq_len < 16 && rs_multi_pop_message(g_multi, &msg, &tid)) { g_mq[g_mq_len] = msg; g_mq_track[g_mq_len] = tid; g_mq_len++; }
    return count;
}

/* stats[0..11]: syncs, crc_fail, contrast, rows_per_chip, roi_start, roi_end, profile_count,
 * messages_total, mode (0 luma / 1 rgb), pilots, cal_cond, packets_total. */
JNIEXPORT jint JNICALL
Java_com_federicopaglioni_rslog_RsCore_processFrame(JNIEnv *env, jclass cls,
        jobject ybuf, jint yRs, jint yPs, jobject ubuf, jint uRs, jint uPs, jobject vbuf, jint vRs, jint vPs,
        jint w, jint h, jint axis, jfloat t,
        jfloatArray statsOut, jfloatArray profileOut, jfloatArray packetsOut)
{
    ensure_init();
    const uint8_t *y = (const uint8_t *)(*env)->GetDirectBufferAddress(env, ybuf);
    const uint8_t *u = ubuf ? (const uint8_t *)(*env)->GetDirectBufferAddress(env, ubuf) : NULL;
    const uint8_t *v = vbuf ? (const uint8_t *)(*env)->GetDirectBufferAddress(env, vbuf) : NULL;
    if (!y || w <= 0 || h <= 0) return 0;
    rs_frame_info_t fi;
    int n;
    if (u && v) {
        rs_frame_profile_yuv420(y, yRs, yPs, u, uRs, uPs, v, vRs, vPs, w, h, axis, g_r, g_g, g_b, &fi);
        n = rs_rx_process(g_rx, g_r, g_g, g_b, fi.count, t);
    } else {
        rs_frame_profile(y, w, h, yRs, yPs, axis, g_r, &fi);
        n = rs_rx_process(g_rx, g_r, NULL, NULL, fi.count, t);
    }
    const rs_dec_stats_t *st = rs_rx_stats(g_rx);
    if (statsOut) {
        jfloat s[14] = { (float)st->syncs, (float)st->crc_fail, st->contrast, rs_rx_rows_per_chip(g_rx),
                         (float)fi.roi_start, (float)fi.roi_end, (float)fi.count, (float)rs_rx_messages(g_rx),
                         (float)rs_rx_mode(g_rx), (float)rs_rx_pilots(g_rx), rs_rx_cal_cond(g_rx), (float)rs_rx_packets(g_rx),
                         (float)fi.peak, fi.sat_frac };
        (*env)->SetFloatArrayRegion(env, statsOut, 0, 14, s);
    }
    if (profileOut) {
        jsize m = (*env)->GetArrayLength(env, profileOut);
        if (m > 0) {
            jfloat tmp[1024]; if (m > 1024) m = 1024;
            for (int i = 0; i < m; i++) {
                int a = i * fi.count / m, b = (i + 1) * fi.count / m; if (b <= a) b = a + 1;
                float mx = -1;
                for (int j = a; j < b && j < fi.count; j++) { float l = (g_r[j] + g_g[j] + g_b[j]) / 3; if (l > mx) mx = l; }
                tmp[i] = mx;
            }
            (*env)->SetFloatArrayRegion(env, profileOut, 0, m, tmp);
        }
    }
    if (packetsOut) {
        jsize m = (*env)->GetArrayLength(env, packetsOut) / 4;
        jfloat tmp[96 * 4]; int k = 0;
        for (int i = 0; i < n && i < m && i < 96; i++) {
            rs_packet_t p; uint8_t ch;
            if (!rs_rx_packet_at(g_rx, i, &p, &ch)) break;
            tmp[k++] = p.row_start / (float)fi.count; tmp[k++] = p.row_end / (float)fi.count; tmp[k++] = (float)p.id; tmp[k++] = (float)ch;
        }
        if (k) (*env)->SetFloatArrayRegion(env, packetsOut, 0, k, tmp);
    }
    return n;
}

/* RGBA_8888 frame (full-resolution colour): same outputs as processFrame. */
JNIEXPORT jint JNICALL
Java_com_federicopaglioni_rslog_RsCore_processFrameRgba(JNIEnv *env, jclass cls, jobject buf, jint rowStride, jint pixelStride,
        jint w, jint h, jint axis, jfloat t, jfloatArray statsOut, jfloatArray profileOut, jfloatArray packetsOut)
{
    ensure_init();
    const uint8_t *px = (const uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
    if (!px || w <= 0 || h <= 0) return 0;
    rs_frame_info_t fi;
    rs_frame_profile_rgb(px, w, h, rowStride, pixelStride, 0, 1, 2, axis, g_r, g_g, g_b, &fi);
    int n = rs_rx_process(g_rx, g_r, g_g, g_b, fi.count, t);
    const rs_dec_stats_t *st = rs_rx_stats(g_rx);
    if (statsOut) {
        jfloat s[14] = { (float)st->syncs, (float)st->crc_fail, st->contrast, rs_rx_rows_per_chip(g_rx),
                         (float)fi.roi_start, (float)fi.roi_end, (float)fi.count, (float)rs_rx_messages(g_rx),
                         (float)rs_rx_mode(g_rx), (float)rs_rx_pilots(g_rx), rs_rx_cal_cond(g_rx), (float)rs_rx_packets(g_rx),
                         (float)fi.peak, fi.sat_frac };
        (*env)->SetFloatArrayRegion(env, statsOut, 0, 14, s);
    }
    if (profileOut) {
        jsize m = (*env)->GetArrayLength(env, profileOut);
        if (m > 0) {
            jfloat tmp[1024]; if (m > 1024) m = 1024;
            for (int i = 0; i < m; i++) {
                int a = i * fi.count / m, b = (i + 1) * fi.count / m; if (b <= a) b = a + 1;
                float mx = -1;
                for (int j = a; j < b && j < fi.count; j++) { float l = (g_r[j] + g_g[j] + g_b[j]) / 3; if (l > mx) mx = l; }
                tmp[i] = mx;
            }
            (*env)->SetFloatArrayRegion(env, profileOut, 0, m, tmp);
        }
    }
    if (packetsOut) {
        jsize m = (*env)->GetArrayLength(env, packetsOut) / 4;
        jfloat tmp[96 * 4]; int k = 0;
        for (int i = 0; i < n && i < m && i < 96; i++) {
            rs_packet_t p; uint8_t ch;
            if (!rs_rx_packet_at(g_rx, i, &p, &ch)) break;
            tmp[k++] = p.row_start / (float)fi.count; tmp[k++] = p.row_end / (float)fi.count; tmp[k++] = (float)p.id; tmp[k++] = (float)ch;
        }
        if (k) (*env)->SetFloatArrayRegion(env, packetsOut, 0, k, tmp);
    }
    return n;
}

JNIEXPORT jstring JNICALL
Java_com_federicopaglioni_rslog_RsCore_pollMessage(JNIEnv *env, jclass cls)
{
    ensure_init();
    rs_message_t m; int tid = 0;
    if (g_mq_len > 0) {
        m = g_mq[0]; tid = g_mq_track[0];
        for (int i = 1; i < g_mq_len; i++) { g_mq[i - 1] = g_mq[i]; g_mq_track[i - 1] = g_mq_track[i]; }
        g_mq_len--;
    } else if (!rs_rx_pop_message(g_rx, &m)) {
        return NULL;
    }
    char buf[112];
    snprintf(buf, sizeof(buf), "%d|%d|%s|%d", m.id, m.level, m.text, tid);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jfloat JNICALL
Java_com_federicopaglioni_rslog_RsCore_slotProgress(JNIEnv *env, jclass cls, jint slot)
{
    ensure_init();
    return rs_asm_progress(&g_rx->assembler, (uint8_t)slot);
}
