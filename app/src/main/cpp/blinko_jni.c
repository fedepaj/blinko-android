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
static int g_last_n = 0;     /* rows of the last RAW profiles (lastProfiles) */

/* The receiver's parallel hook: the channels of a frame on three threads. The phone's three
 * 3000-row RAW profiles took 15-25 ms one after the other, and under that load the governor kept
 * the big cores at a third of their clock; two short-lived pthreads per frame plus the caller
 * cut the wall time to about a third. The decoder's scratch is thread-local (RS_DEC_THREADS). */
#include <pthread.h>
#include <stdatomic.h>
#include <unistd.h>
/* Any number of jobs over a few short-lived workers (the caller is one of them): the jobs are
 * pulled from a shared counter, so a frame's 3 channels or a profile's ~10-24 sync candidates
 * both fit; nested calls (a candidate map inside a channel map) just make more workers. */
typedef struct { void (*job)(void *, int); void *ctx; int count; atomic_int next; } par_t;
static void *par_run(void *a)
{
    par_t *p = (par_t *)a;
    for (;;) { int i = atomic_fetch_add(&p->next, 1); if (i >= p->count) return NULL; p->job(p->ctx, i); }
}
static void parallel_for(void *user, int count, void (*job)(void *ctx, int i), void *ctx)
{
    (void)user;
    par_t p; p.job = job; p.ctx = ctx; p.count = count; atomic_init(&p.next, 0);
    static int ncpu = 0; if (!ncpu) { ncpu = (int)sysconf(_SC_NPROCESSORS_ONLN); if (ncpu < 2) ncpu = 2; if (ncpu > 8) ncpu = 8; }
    int workers = count < ncpu ? count : ncpu;
    pthread_t th[8]; int started = 0;
    for (int w = 1; w < workers; w++) if (pthread_create(&th[w], NULL, par_run, &p) == 0) started |= 1 << w;
    par_run(&p);
    for (int w = 1; w < workers; w++) if (started & (1 << w)) pthread_join(th[w], NULL);
}
static void set_hooks(void) { rs_rx_set_parallel(g_rx, parallel_for, NULL); rs_multi_set_parallel(g_multi, parallel_for, NULL); }

static void ensure_init(void)
{
    if (g_rx) return;
    g_rx = (rs_rx_t *)malloc(rs_rx_sizeof());
    rs_rx_init(g_rx);
    g_multi = (rs_multi_t *)malloc(rs_multi_sizeof());
    rs_multi_init(g_multi);
    set_hooks();
}

JNIEXPORT void JNICALL
Java_com_federicopaglioni_blinko_RsCore_reset(JNIEnv *env, jclass cls)
{
    ensure_init();
    rs_rx_init(g_rx);
    rs_multi_init(g_multi);
    set_hooks();
}

/* Multi-source path on an RGBA_8888 frame. tracksOut receives up to len/8 entries of
 * (id, cx/w, cy/h, radius/w, mode, packets, messages, group); returns the number of tracks
 * (or -1 when no light was found, so the caller can use the single path). Packets decoded
 * are stored in stats[11]. Messages are queued with their track id (pollMessage). */
static rs_message_t g_mq[16]; static int g_mq_track[16]; static int g_mq_len;

static jint multi_process(JNIEnv *env, jobject buf, jint rowStride, jint pixelStride, jint w, jint h,
        int r_off, int g_off, int b_off, jfloat t, jfloatArray tracksOut, jintArray packetsOut)
{
    ensure_init();
    const uint8_t *px = (const uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
    if (!px || w <= 0 || h <= 0) return -1;
    int n = rs_multi_process(g_multi, px, w, h, rowStride, pixelStride, r_off, g_off, b_off, t);
    int count = rs_multi_track_count(g_multi);
    if (count == 0) return -1;
    if (packetsOut) { jint v = n; (*env)->SetIntArrayRegion(env, packetsOut, 0, 1, &v); }
    if (tracksOut) {
        jsize m = (*env)->GetArrayLength(env, tracksOut) / 8;
        jfloat tmp[8 * RS_MAX_TRACKS]; int k = 0;
        for (int i = 0; i < count && i < m; i++) {
            int id, mode, pilots; float cx, cy, rad; uint32_t pk, ms;
            if (!rs_multi_track_info(g_multi, i, &id, &cx, &cy, &rad, &mode, &pk, &ms, &pilots)) break;
            tmp[k++] = (float)id; tmp[k++] = cx / (float)w; tmp[k++] = cy / (float)h; tmp[k++] = rad / (float)h;   /* radius vs rows: columns are subsampled */
            tmp[k++] = (float)mode; tmp[k++] = (float)pk; tmp[k++] = (float)ms; tmp[k++] = (float)rs_multi_track_group(g_multi, i);
        }
        if (k) (*env)->SetFloatArrayRegion(env, tracksOut, 0, k, tmp);
    }
    rs_message_t msg; int tid;
    while (g_mq_len < 16 && rs_multi_pop_message(g_multi, &msg, &tid)) { g_mq[g_mq_len] = msg; g_mq_track[g_mq_len] = tid; g_mq_len++; }
    return count;
}

JNIEXPORT jint JNICALL
Java_com_federicopaglioni_blinko_RsCore_processFrameRgbaMulti(JNIEnv *env, jclass cls, jobject buf, jint rowStride, jint pixelStride,
        jint w, jint h, jfloat t, jfloatArray tracksOut, jintArray packetsOut)
{
    return multi_process(env, buf, rowStride, pixelStride, w, h, 0, 1, 2, t, tracksOut, packetsOut);
}

/* Same on a packed BGRA frame (the recorder's / converter's layout, row stride = 4 * w). */
JNIEXPORT jint JNICALL
Java_com_federicopaglioni_blinko_RsCore_processFrameBgraMulti(JNIEnv *env, jclass cls, jobject buf, jint w, jint h, jfloat t,
        jfloatArray tracksOut, jintArray packetsOut)
{
    return multi_process(env, buf, w * 4, 4, w, h, 2, 1, 0, t, tracksOut, packetsOut);
}

/* stats[0..11]: syncs, crc_fail, contrast, rows_per_chip, roi_start, roi_end, profile_count,
 * messages_total, mode (0 luma / 1 rgb), pilots, cal_cond, packets_total. */
JNIEXPORT jint JNICALL
Java_com_federicopaglioni_blinko_RsCore_processFrame(JNIEnv *env, jclass cls,
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
        jfloat s[15] = { (float)st->syncs, (float)st->crc_fail, st->contrast, rs_rx_rows_per_chip(g_rx),
                         (float)fi.roi_start, (float)fi.roi_end, (float)fi.count, (float)rs_rx_messages(g_rx),
                         (float)rs_rx_mode(g_rx), (float)rs_rx_pilots(g_rx), rs_rx_cal_cond(g_rx), (float)rs_rx_packets(g_rx),
                         (float)fi.peak, fi.sat_frac, (float)rs_rx_stitched(g_rx) };
        (*env)->SetFloatArrayRegion(env, statsOut, 0, 15, s);
    }
    if (profileOut) {
        jsize m = (*env)->GetArrayLength(env, profileOut);
        if (m > 0) {
            jfloat tmp[4096]; if (m > 4096) m = 4096;
            if (m > fi.count) m = fi.count;          /* room for every sample: copy them 1:1, nothing stretched */
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

/* Interleaved colour frame: same outputs as processFrame. */
static jint rgb_process(JNIEnv *env, jobject buf, jint rowStride, jint pixelStride, jint w, jint h,
        int r_off, int g_off, int b_off, jint axis, jfloat t, jfloatArray statsOut, jfloatArray profileOut, jfloatArray packetsOut)
{
    ensure_init();
    const uint8_t *px = (const uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
    if (!px || w <= 0 || h <= 0) return 0;
    rs_frame_info_t fi;
    rs_frame_profile_rgb(px, w, h, rowStride, pixelStride, r_off, g_off, b_off, axis, g_r, g_g, g_b, &fi);
    int n = rs_rx_process(g_rx, g_r, g_g, g_b, fi.count, t);
    const rs_dec_stats_t *st = rs_rx_stats(g_rx);
    if (statsOut) {
        jfloat s[15] = { (float)st->syncs, (float)st->crc_fail, st->contrast, rs_rx_rows_per_chip(g_rx),
                         (float)fi.roi_start, (float)fi.roi_end, (float)fi.count, (float)rs_rx_messages(g_rx),
                         (float)rs_rx_mode(g_rx), (float)rs_rx_pilots(g_rx), rs_rx_cal_cond(g_rx), (float)rs_rx_packets(g_rx),
                         (float)fi.peak, fi.sat_frac, (float)rs_rx_stitched(g_rx) };
        (*env)->SetFloatArrayRegion(env, statsOut, 0, 15, s);
    }
    if (profileOut) {
        jsize m = (*env)->GetArrayLength(env, profileOut);
        if (m > 0) {
            jfloat tmp[4096]; if (m > 4096) m = 4096;
            if (m > fi.count) m = fi.count;          /* room for every sample: copy them 1:1, nothing stretched */
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

JNIEXPORT jint JNICALL
Java_com_federicopaglioni_blinko_RsCore_processFrameRgba(JNIEnv *env, jclass cls, jobject buf, jint rowStride, jint pixelStride,
        jint w, jint h, jint axis, jfloat t, jfloatArray statsOut, jfloatArray profileOut, jfloatArray packetsOut)
{
    return rgb_process(env, buf, rowStride, pixelStride, w, h, 0, 1, 2, axis, t, statsOut, profileOut, packetsOut);
}

JNIEXPORT jint JNICALL
Java_com_federicopaglioni_blinko_RsCore_processFrameBgra(JNIEnv *env, jclass cls, jobject buf, jint w, jint h, jint axis, jfloat t,
        jfloatArray statsOut, jfloatArray profileOut, jfloatArray packetsOut)
{
    return rgb_process(env, buf, w * 4, 4, w, h, 2, 1, 0, axis, t, statsOut, profileOut, packetsOut);
}

/* YUV_420_888 planes -> packed BGRA with every `step`-th column kept (rows, the time axis, stay
 * at full resolution): the layout the recorder stores and the receiver consumes. Full-range
 * BT.601 like rs_frame_profile_yuv420. Returns the output width. */
JNIEXPORT jint JNICALL
Java_com_federicopaglioni_blinko_RsCore_convertYuvToBgra(JNIEnv *env, jclass cls,
        jobject ybuf, jint yRs, jint yPs, jobject ubuf, jint uRs, jint uPs, jobject vbuf, jint vRs, jint vPs,
        jint w, jint h, jint step, jobject out)
{
    const uint8_t *y = (const uint8_t *)(*env)->GetDirectBufferAddress(env, ybuf);
    const uint8_t *u = (const uint8_t *)(*env)->GetDirectBufferAddress(env, ubuf);
    const uint8_t *v = (const uint8_t *)(*env)->GetDirectBufferAddress(env, vbuf);
    uint8_t *o = (uint8_t *)(*env)->GetDirectBufferAddress(env, out);
    if (!y || !u || !v || !o || w <= 0 || h <= 0 || step <= 0) return 0;
    int ow = w / step;
    if ((*env)->GetDirectBufferCapacity(env, out) < (jlong)ow * h * 4) return 0;
    for (int r = 0; r < h; r++) {
        const uint8_t *py = y + r * yRs, *pu = u + (r / 2) * uRs, *pv = v + (r / 2) * vRs;
        uint8_t *d = o + (size_t)r * ow * 4;
        for (int c = 0; c < ow; c++, d += 4) {
            int x = c * step;
            int Y = py[x * yPs], U = pu[(x / 2) * uPs] - 128, V = pv[(x / 2) * vPs] - 128;
            int R = Y + ((1436 * V) >> 10), G = Y - ((352 * U + 731 * V) >> 10), B = Y + ((1815 * U) >> 10);
            d[0] = (uint8_t)(B < 0 ? 0 : B > 255 ? 255 : B);
            d[1] = (uint8_t)(G < 0 ? 0 : G > 255 ? 255 : G);
            d[2] = (uint8_t)(R < 0 ? 0 : R > 255 ? 255 : R);
            d[3] = 255;
        }
    }
    return ow;
}

/* RGBA_8888 frame -> packed BGRA, columns subsampled by `step`. Returns the output width. */
JNIEXPORT jint JNICALL
Java_com_federicopaglioni_blinko_RsCore_convertRgbaToBgra(JNIEnv *env, jclass cls, jobject buf, jint rowStride, jint pixelStride,
        jint w, jint h, jint step, jobject out)
{
    const uint8_t *px = (const uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
    uint8_t *o = (uint8_t *)(*env)->GetDirectBufferAddress(env, out);
    if (!px || !o || w <= 0 || h <= 0 || step <= 0) return 0;
    int ow = w / step;
    if ((*env)->GetDirectBufferCapacity(env, out) < (jlong)ow * h * 4) return 0;
    for (int r = 0; r < h; r++) {
        const uint8_t *s = px + (size_t)r * rowStride;
        uint8_t *d = o + (size_t)r * ow * 4;
        for (int c = 0; c < ow; c++, d += 4) {
            const uint8_t *p = s + (size_t)c * step * pixelStride;
            d[0] = p[2]; d[1] = p[1]; d[2] = p[0]; d[3] = 255;
        }
    }
    return ow;
}

/* RAW_SENSOR frame -> packed BGRA at half resolution: one pixel per 2x2 Bayer block (R, (Gr+Gb)/2,
 * B scaled 0..255 by the black and white levels, clipping at 255 so the receiver sees it), with
 * the block columns subsampled by `step`. The result goes through the same segmentation /
 * multi-source / profile path as a YUV frame, which keeps every light apart; rows are 2 sensor
 * rows, so the row time to use is twice the sensor's. Returns the output width. */
JNIEXPORT jint JNICALL
Java_com_federicopaglioni_blinko_RsCore_convertRawToBgra(JNIEnv *env, jclass cls, jobject buf, jint rowStride, jint w, jint h,
        jint cfa, jint black, jint white, jint step, jobject outBuf)
{
    const uint8_t *px = (const uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
    uint8_t *o = (uint8_t *)(*env)->GetDirectBufferAddress(env, outBuf);
    if (!px || !o || w < 4 || h < 4 || step < 1) return 0;
    /* one output pixel per 4x4 sensor pixels: the block columns are subsampled by `step`, the
     * block rows by 2 (every other pair of sensor rows is read at all: the conversion is
     * memory-bound on a phone). Rows are then 4 sensor rows: 10.6 us on the S21 FE. */
    int bw = w / 2, bh = h / 4, ow = bw / step;
    jlong cap = (*env)->GetDirectBufferCapacity(env, outBuf);
    if (cap < (jlong)ow * bh * 4) return 0;
    if (white <= black) white = black + 1;
    int scale_q8 = (255 << 8) / (white - black);                        /* integer scaling */
    /* cfa: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR -> positions of R and B inside the 2x2 block */
    int r_row = (cfa == 2 || cfa == 3) ? 1 : 0, r_col = (cfa == 1 || cfa == 3) ? 1 : 0;
    for (int by = 0; by < bh; by++) {
        const uint16_t *row0 = (const uint16_t *)(px + (size_t)(4 * by) * rowStride), *row1 = (const uint16_t *)(px + (size_t)(4 * by + 1) * rowStride);
        uint8_t *d = o + (size_t)by * ow * 4;
        for (int bx = 0; bx < ow; bx++, d += 4) {
            int x = 2 * bx * step;
            int p00 = row0[x], p01 = row0[x + 1], p10 = row1[x], p11 = row1[x + 1];
            int r = r_row == 0 ? (r_col == 0 ? p00 : p01) : (r_col == 0 ? p10 : p11);
            int b = r_row == 0 ? (r_col == 0 ? p11 : p10) : (r_col == 0 ? p01 : p00);
            int g = (p00 + p01 + p10 + p11 - r - b) >> 1;             /* (Gr + Gb) / 2 */
            int vr = ((r - black) * scale_q8) >> 8, vg = ((g - black) * scale_q8) >> 8, vb = ((b - black) * scale_q8) >> 8;
            d[0] = (uint8_t)(vb < 0 ? 0 : vb > 255 ? 255 : vb); d[1] = (uint8_t)(vg < 0 ? 0 : vg > 255 ? 255 : vg); d[2] = (uint8_t)(vr < 0 ? 0 : vr > 255 ? 255 : vr); d[3] = 255;
        }
    }
    return ow;
}

/* RAW_SENSOR frame (16-bit little-endian Bayer, values black..white): per-row R, G, B profiles
 * computed from the mosaic itself, no ISP in between. Columns: 2x2 Bayer blocks whose maximum
 * (sampled every 16th row) is above 30 % of the frame's brightest block; blocks that clip
 * (>= 97 % of white) are dropped when at least 8 unclipped ones remain (the halo carries the
 * modulation a saturated core loses). Row r of the R profile exists only on GR rows and B only
 * on BG rows: the missing rows repeat their neighbour. Values are scaled to 0..255.
 * cfa: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR. Only the row axis is supported. */
static uint8_t g_rawsel[4096];
static float g_r2[2048], g_g2[2048], g_b2[2048];   /* pair-averaged RAW profiles */
static float g_exposure_rows = 0;                   /* as set by the app, in full-resolution rows */
static float g_row_seconds = 0;                     /* sensor row time, full resolution */
JNIEXPORT jint JNICALL
Java_com_federicopaglioni_blinko_RsCore_processFrameRaw(JNIEnv *env, jclass cls, jobject buf, jint rowStride, jint w, jint h,
        jint cfa, jint black, jint white, jfloat t, jfloatArray statsOut, jfloatArray profileOut, jfloatArray packetsOut)
{
    ensure_init();
    const uint8_t *px = (const uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
    if (!px || w <= 0 || h <= 0 || h > 4096) return 0;
    int bw = w / 2; if (bw > 4096) bw = 4096;
    if (white <= black) white = black + 1;
    float scale = 255.0f / (float)(white - black);
    /* pass 1: block maxima on sampled rows */
    static uint16_t bmax[4096];
    for (int b = 0; b < bw; b++) bmax[b] = 0;
    for (int r = 0; r < h; r += 16) {
        const uint16_t *row = (const uint16_t *)(px + (size_t)r * rowStride);
        for (int b = 0; b < bw; b++) { uint16_t v = row[2 * b]; if (row[2 * b + 1] > v) v = row[2 * b + 1]; if (v > bmax[b]) bmax[b] = v; }
    }
    int gmax = 0; for (int b = 0; b < bw; b++) if (bmax[b] > gmax) gmax = bmax[b];
    int thr = black + (int)(0.3f * (gmax - black)), clip = black + (int)(0.97f * (white - black));
    int nsel = 0, nunclipped = 0, first = -1, last = -1;
    for (int b = 0; b < bw; b++) { g_rawsel[b] = bmax[b] >= thr; if (g_rawsel[b]) { nsel++; if (bmax[b] < clip) nunclipped++; } }
    if (nunclipped >= 8 && nunclipped < nsel) { for (int b = 0; b < bw; b++) if (g_rawsel[b] && bmax[b] >= clip) g_rawsel[b] = 0; nsel = nunclipped; }
    if (nsel == 0) return 0;
    for (int b = 0; b < bw; b++) if (g_rawsel[b]) { if (first < 0) first = b; last = b; }
    /* pass 2: per-row sums of the selected blocks, by colour */
    int r_even = (cfa == 0) ? 1 : (cfa == 1) ? 0 : (cfa == 2) ? -1 : -1;   /* column offset of R on even rows, -1 = R is on odd rows */
    int peak = 0, satrows = 0;
    for (int r = 0; r < h; r++) {
        const uint16_t *row = (const uint16_t *)(px + (size_t)r * rowStride);
        int even = (r & 1) == 0;
        /* which colours this row holds: RGGB/GRBG: even rows R+G, odd rows G+B; GBRG/BGGR: even G+B, odd R+G */
        int has_r = (cfa <= 1) ? even : !even;
        int roff, goff;
        if (has_r) { roff = (cfa == 0 || cfa == 3) ? 0 : 1; goff = 1 - roff; }          /* RGGB: R G ; GRBG: G R ; GBRG odd: R G? (GBRG rows: G B / R G) BGGR odd: G R */
        else       { roff = (cfa == 0 || cfa == 3) ? 1 : 0; goff = 1 - roff; }          /* the non-G pixel is B */
        if (cfa == 2) { roff = has_r ? 0 : 1; goff = 1 - roff; }
        if (cfa == 3) { roff = has_r ? 1 : 0; goff = 1 - roff; }
        uint32_t sc = 0, sg = 0; int mx = 0;
        for (int b = first; b <= last; b++) {
            if (!g_rawsel[b]) continue;
            int c = row[2 * b + roff], g = row[2 * b + goff];
            sc += c; sg += g; if (c > mx) mx = c; if (g > mx) mx = g;
        }
        float vc = ((float)sc / nsel - black) * scale, vg = ((float)sg / nsel - black) * scale;
        if (vc < 0) vc = 0; if (vg < 0) vg = 0;
        /* Gr and Gb pixels differ in sensitivity (a period-2 zigzag under a narrow-band LED):
         * the G profile uses the Gr rows only, the Gb rows repeat their neighbour like R and B do */
        if (has_r) { g_r[r] = vc; g_g[r] = vg; g_b[r] = r > 0 ? g_b[r - 1] : vc; }
        else       { g_b[r] = vc; g_r[r] = r > 0 ? g_r[r - 1] : vc; g_g[r] = r > 0 ? g_g[r - 1] : vg; }
        int pm = (int)((mx - black) * scale); if (pm > peak) peak = pm;
        if (mx >= clip) satrows++;
    }
    if (h > 1) { if (cfa <= 1) g_b[0] = g_b[1]; else { g_r[0] = g_r[1]; g_g[0] = g_g[1]; } }
    g_last_n = h;
    /* A 3000-row RAW frame costs the receiver 3 x 3000-row detections (15-20 fps on the S21 FE);
     * the data needs no more than ~4 rows per chip, so outside the lab the profiles are averaged
     * in pairs (1500 rows at 5.3 us: still 3.8 rows per chip at a 20 us chip). The exposure in
     * rows halves with them; the packet rows are reported as fractions, so nothing else moves. */
    int ds = (h >= 2000 && packetsOut) ? 2 : 1, hd = h / ds;
    if (ds == 2) {
        for (int r = 0; r < hd; r++) { g_r2[r] = 0.5f * (g_r[2 * r] + g_r[2 * r + 1]); g_g2[r] = 0.5f * (g_g[2 * r] + g_g[2 * r + 1]); g_b2[r] = 0.5f * (g_b[2 * r] + g_b[2 * r + 1]); }
    }
    { rs_camera_t cam = { g_exposure_rows / (float)ds, g_row_seconds * (float)ds }; rs_rx_set_camera(g_rx, cam); }
    int n = rs_rx_process(g_rx, ds == 2 ? g_r2 : g_r, ds == 2 ? g_g2 : g_g, ds == 2 ? g_b2 : g_b, hd, t);
    const rs_dec_stats_t *st = rs_rx_stats(g_rx);
    if (statsOut) {
        jfloat s[15] = { (float)st->syncs, (float)st->crc_fail, st->contrast, rs_rx_rows_per_chip(g_rx) * (float)ds,
                         (float)(2 * first), (float)(2 * last + 2), (float)h, (float)rs_rx_messages(g_rx),
                         (float)rs_rx_mode(g_rx), (float)rs_rx_pilots(g_rx), rs_rx_cal_cond(g_rx), (float)rs_rx_packets(g_rx),
                         (float)peak, (float)satrows / (float)h, (float)rs_rx_stitched(g_rx) };
        (*env)->SetFloatArrayRegion(env, statsOut, 0, 15, s);
    }
    if (profileOut) {
        jsize m = (*env)->GetArrayLength(env, profileOut);
        if (m > 0) {
            jfloat tmp[4096]; if (m > 4096) m = 4096;
            if (m > h) m = h;
            for (int i = 0; i < m; i++) {
                int a = i * h / m, bb = (i + 1) * h / m; if (bb <= a) bb = a + 1;
                float mxv = -1;
                for (int j = a; j < bb && j < h; j++) { float l = (g_r[j] + g_g[j] + g_b[j]) / 3; if (l > mxv) mxv = l; }
                tmp[i] = mxv;
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
            tmp[k++] = p.row_start / (float)hd; tmp[k++] = p.row_end / (float)hd; tmp[k++] = (float)p.id; tmp[k++] = (float)ch;
        }
        if (k) (*env)->SetFloatArrayRegion(env, packetsOut, 0, k, tmp);
    }
    return n;
}

/* The last r, g, b profiles handed to the receiver (n rows each): out[0..n) = r, [n..2n) = g, [2n..3n) = b. Returns n. */
JNIEXPORT jint JNICALL
Java_com_federicopaglioni_blinko_RsCore_lastProfiles(JNIEnv *env, jclass cls, jfloatArray out)
{
    int n = g_last_n; if (n <= 0) return 0;
    jsize m = (*env)->GetArrayLength(env, out); if (m < 3 * n) return 0;
    (*env)->SetFloatArrayRegion(env, out, 0, n, g_r);
    (*env)->SetFloatArrayRegion(env, out, n, n, g_g);
    (*env)->SetFloatArrayRegion(env, out, 2 * n, n, g_b);
    return n;
}

JNIEXPORT void JNICALL
Java_com_federicopaglioni_blinko_RsCore_setMinContrast(JNIEnv *env, jclass cls, jfloat v)
{
    ensure_init();
    g_rx->cfg.min_contrast = v;
}

/* Camera exposure in sensor rows (exposure_us / row_us) for the exposure-aware detector; 0 = unknown. */
JNIEXPORT void JNICALL
Java_com_federicopaglioni_blinko_RsCore_setRowTime(JNIEnv *env, jclass cls, jfloat seconds)
{
    ensure_init();
    g_row_seconds = seconds;
    rs_camera_t cam = { g_exposure_rows, g_row_seconds }; rs_rx_set_camera(g_rx, cam); rs_multi_set_camera(g_multi, cam);
}

JNIEXPORT void JNICALL
Java_com_federicopaglioni_blinko_RsCore_setExposureRows(JNIEnv *env, jclass cls, jfloat rows)
{
    ensure_init();
    g_exposure_rows = rows;
    rs_camera_t cam = { g_exposure_rows, g_row_seconds }; rs_rx_set_camera(g_rx, cam); rs_multi_set_camera(g_multi, cam);
}

JNIEXPORT jstring JNICALL
Java_com_federicopaglioni_blinko_RsCore_pollMessage(JNIEnv *env, jclass cls)
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
Java_com_federicopaglioni_blinko_RsCore_slotProgress(JNIEnv *env, jclass cls, jint slot)
{
    ensure_init();
    return rs_asm_progress(&g_rx->assembler, (uint8_t)slot);
}
