/* JNI bridge to the shared C receiver (core/): the camera's YUV, RGBA or RAW Bayer buffer becomes
 * a packed BGRA frame, the frame becomes per-row R, G, B profiles, and the receiver does the rest
 * (calibration, unmixing, decoding, assembly) for one light (rs_rx) or for every light in the
 * frame (rs_multi). */
#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>
#include <pthread.h>
#include <stdatomic.h>
#include <unistd.h>
#include "rs_rx.h"
#include "rs_frame.h"
#include "rs_multi.h"

/* One lock for everything shared below: the two receivers, the profile arrays, the message queue
 * and the settings kept for them. The camera thread decodes, the main thread resets and changes
 * settings, the replay thread does both, and the core supports one decode at a time per process
 * (static scratch in rs_frame / rs_multi / rs_rx, state in the receivers): without it a reset
 * zeroed a receiver under a decode in progress. Every entry point that touches them holds the
 * lock for the whole call; the JNI calls made while it is held only copy array regions, none
 * runs Java code. The converters work on their arguments alone and do not take it. */
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static rs_rx_t *g_rx;
static rs_multi_t *g_multi;
static float g_r[4096], g_g[4096], g_b[4096];
static int g_last_n = 0;     /* rows of the last RAW profiles (lastProfiles) */
/* Messages of the multi-source receiver with their logical source, until pollMessage takes them. */
static rs_message_t g_mq[16]; static int g_mq_track[16]; static int g_mq_len;
static int g_multi_owns;     /* 1: the last frame was decoded by the multi-source receiver (see pollMessage) */
/* What the app set. rs_rx_init / rs_multi_init zero it inside the receivers with everything
 * else, so it is kept here and put back after every init (apply_config): a reset used to leave
 * both receivers without exposure and row time, and the single one at the default contrast,
 * until the next change of a setting. */
static float g_exposure_rows = 0;    /* exposure in rows of the image the receiver decodes (the app's exposure_us / rowUs:
                                      * with RAW capture those are the rows of the reduced image, not sensor rows); 0 = unknown */
static float g_row_seconds = 0;      /* time of one such row, seconds; 0 = unknown */
static float g_min_contrast = -1;    /* < 0: never set, the core's default stays */

/* The receiver's parallel hook. The phone's three 3000-row RAW profiles took 15-25 ms one after
 * the other, and under that load the governor kept the big cores at a third of their clock;
 * decoding the three channels on three threads cut the wall time to about a third. The
 * decoder's scratch is thread-local (RS_DEC_THREADS).
 * Any number of jobs over short-lived workers made for the call (the caller is one of them, the
 * rest pthreads: one per job up to the online cores, 8 at most): the jobs are pulled from a
 * shared counter, so a frame's 3 channels or a profile's ~10-24 sync candidates both fit; nested
 * calls (a candidate map inside a channel map) just make more workers. */
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

/* Everything below this point that names g_rx / g_multi runs with g_lock held. */

/* After every init of the receivers: the parallel hook, the camera description, the contrast. */
static void apply_config(void)
{
    rs_rx_set_parallel(g_rx, parallel_for, NULL); rs_multi_set_parallel(g_multi, parallel_for, NULL);
    rs_camera_t cam = { g_exposure_rows, g_row_seconds }; rs_rx_set_camera(g_rx, cam); rs_multi_set_camera(g_multi, cam);
    if (g_min_contrast >= 0) { g_rx->cfg.min_contrast = g_min_contrast; rs_multi_set_min_contrast(g_multi, g_min_contrast); }
}

static void ensure_init(void)
{
    if (g_rx) return;
    g_rx = (rs_rx_t *)malloc(rs_rx_sizeof());
    rs_rx_init(g_rx);
    g_multi = (rs_multi_t *)malloc(rs_multi_sizeof());
    rs_multi_init(g_multi);
    apply_config();
}

JNIEXPORT void JNICALL
Java_com_federicopaglioni_blinko_RsCore_reset(JNIEnv *env, jclass cls)
{
    pthread_mutex_lock(&g_lock);
    ensure_init();
    rs_rx_init(g_rx);
    rs_multi_init(g_multi);
    apply_config();
    g_mq_len = 0; g_multi_owns = 0;
    pthread_mutex_unlock(&g_lock);
}

/* What the process entry points hand back to Kotlin.
 * stats[0..14] (RsCore.ST_*): syncs, crc_fail, contrast, rows_per_chip, roi_start, roi_end,
 * profile_count, messages_total, mode (0 mono / 1 RGB / 2 direct), pilots, cal_cond,
 * packets_total, peak, sat_frac, stitched. The frame's figures are the arguments; the
 * receiver's are read from rx and are 0 when it is NULL.
 * profile: the luma (r+g+b)/3 of the `count` rows in g_r/g_g/g_b, the maximum of each bin when
 * the array is shorter than the profile.
 * packets: (row_start, row_end, slot, channel) of the first n packets of rx, the rows as
 * fractions of the profile it decoded, which has count/ds rows (ds = 2 when the RAW path
 * averaged the rows in pairs; rows_per_chip is reported in rows of the full profile). */
static void put_outputs(JNIEnv *env, const rs_rx_t *rx, int n, int count, int roi0, int roi1, int peak, float sat, int ds,
        jfloatArray statsOut, jfloatArray profileOut, jfloatArray packetsOut)
{
    if (statsOut) {
        jfloat s[15] = { 0 };
        if (rx) {
            const rs_dec_stats_t *st = rs_rx_stats(rx);
            s[0] = (float)st->syncs; s[1] = (float)st->crc_fail; s[2] = st->contrast; s[3] = rs_rx_rows_per_chip(rx) * (float)ds;
            s[7] = (float)rs_rx_messages(rx); s[8] = (float)rs_rx_mode(rx); s[9] = (float)rs_rx_pilots(rx); s[10] = rs_rx_cal_cond(rx);
            s[11] = (float)rs_rx_packets(rx); s[14] = (float)rs_rx_stitched(rx);
        }
        s[4] = (float)roi0; s[5] = (float)roi1; s[6] = (float)count; s[12] = (float)peak; s[13] = sat;
        (*env)->SetFloatArrayRegion(env, statsOut, 0, 15, s);
    }
    if (profileOut) {
        jsize m = (*env)->GetArrayLength(env, profileOut);
        if (m > 0) {
            jfloat tmp[4096]; if (m > 4096) m = 4096;
            if (m > count) m = count;                /* room for every sample: copy them 1:1, nothing stretched */
            for (int i = 0; i < m; i++) {
                int a = i * count / m, b = (i + 1) * count / m; if (b <= a) b = a + 1;
                float mx = -1;
                for (int j = a; j < b && j < count; j++) { float l = (g_r[j] + g_g[j] + g_b[j]) / 3; if (l > mx) mx = l; }
                tmp[i] = mx;
            }
            (*env)->SetFloatArrayRegion(env, profileOut, 0, m, tmp);
        }
    }
    if (packetsOut && rx) {
        jsize m = (*env)->GetArrayLength(env, packetsOut) / 4;
        jfloat tmp[96 * 4]; int k = 0; float rows = (float)(count / ds);
        for (int i = 0; i < n && i < m && i < 96; i++) {
            rs_packet_t p; uint8_t ch;
            if (!rs_rx_packet_at(rx, i, &p, &ch)) break;
            tmp[k++] = p.row_start / rows; tmp[k++] = p.row_end / rows; tmp[k++] = (float)p.id; tmp[k++] = (float)ch;
        }
        if (k) (*env)->SetFloatArrayRegion(env, packetsOut, 0, k, tmp);
    }
}

/* Multi-source path on a packed BGRA frame (the converter's / recorder's layout, row stride =
 * 4 * w). tracksOut receives up to len/8 entries of (id, cx/w, cy/h, radius/h, mode, packets,
 * messages, group), packetsOut[0] the packets decoded in this frame. Returns the number of
 * tracks, or -1 when no light is tracked, so the caller can use the single path. Messages are
 * queued with their logical source (pollMessage). */
JNIEXPORT jint JNICALL
Java_com_federicopaglioni_blinko_RsCore_processFrameBgraMulti(JNIEnv *env, jclass cls, jobject buf, jint w, jint h, jfloat t,
        jfloatArray tracksOut, jintArray packetsOut)
{
    const uint8_t *px = (const uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
    if (!px || w <= 0 || h <= 0) return -1;
    pthread_mutex_lock(&g_lock);
    ensure_init();
    int n = rs_multi_process(g_multi, px, w, h, w * 4, 4, 2, 1, 0, t);
    int count = rs_multi_track_count(g_multi);
    g_multi_owns = count > 0;
    if (count > 0) {
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
    }
    pthread_mutex_unlock(&g_lock);
    return count > 0 ? count : -1;
}

/* Single-receiver path on a packed BGRA frame: the R, G, B profiles of the frame's bright region
 * through g_rx. Returns the packets decoded in this frame; outputs as put_outputs. */
JNIEXPORT jint JNICALL
Java_com_federicopaglioni_blinko_RsCore_processFrameBgra(JNIEnv *env, jclass cls, jobject buf, jint w, jint h, jint axis, jfloat t,
        jfloatArray statsOut, jfloatArray profileOut, jfloatArray packetsOut)
{
    const uint8_t *px = (const uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
    if (!px || w <= 0 || h <= 0) return 0;
    pthread_mutex_lock(&g_lock);
    ensure_init();
    rs_frame_info_t fi;
    rs_frame_profile_rgb(px, w, h, w * 4, 4, 2, 1, 0, axis, g_r, g_g, g_b, &fi);
    int n = rs_rx_process(g_rx, g_r, g_g, g_b, fi.count, t);
    g_multi_owns = 0;
    put_outputs(env, g_rx, n, fi.count, fi.roi_start, fi.roi_end, fi.peak, fi.sat_frac, 1, statsOut, profileOut, packetsOut);
    pthread_mutex_unlock(&g_lock);
    return n;
}

/* The chart and the stats while the multi-source receiver owns the frame: the same profile of
 * the bright region as processFrameBgra, and nothing decoded. The app used to run
 * processFrameBgra here for these numbers: that is a second receiver with its own assembler on
 * the same light, and each of its messages came out of pollMessage once more, with source 0.
 * The receiver's fields of the stats are those of the track with the most packets so far. */
JNIEXPORT void JNICALL
Java_com_federicopaglioni_blinko_RsCore_profileBgra(JNIEnv *env, jclass cls, jobject buf, jint w, jint h, jint axis,
        jfloatArray statsOut, jfloatArray profileOut)
{
    const uint8_t *px = (const uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
    if (!px || w <= 0 || h <= 0) return;
    pthread_mutex_lock(&g_lock);
    ensure_init();
    rs_frame_info_t fi;
    rs_frame_profile_rgb(px, w, h, w * 4, 4, 2, 1, 0, axis, g_r, g_g, g_b, &fi);
    const rs_rx_t *lead = NULL;
    for (int i = 0, count = rs_multi_track_count(g_multi); i < count; i++) {
        const rs_rx_t *rx = rs_multi_track_rx(g_multi, i);
        if (rx && (!lead || rs_rx_packets(rx) > rs_rx_packets(lead))) lead = rx;
    }
    put_outputs(env, lead, 0, fi.count, fi.roi_start, fi.roi_end, fi.peak, fi.sat_frac, 1, statsOut, profileOut, NULL);
    pthread_mutex_unlock(&g_lock);
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

/* RAW_SENSOR frame (16-bit Bayer) -> packed BGRA, reduced: one pixel from a 2x2 Bayer block (R,
 * (Gr+Gb)/2, B scaled 0..255 by the black and white levels, clipping at 255 so the receiver
 * sees it), taking the block at the top of every `rowBin` sensor rows (the rows in between are
 * not read at all: the conversion is memory-bound on a phone) and every `step`-th block column.
 * The app passes rowBin = 4 (Pipeline.RAW_ROW_BIN): nominally one pixel per 4x4 sensor pixels,
 * with the columns thinned further by `step` like every other format. A row of the result is
 * rowBin sensor rows (4 x 2.65 = 10.6 us on the S21 FE), and that is the row time the receiver
 * is given. The result goes through the same segmentation / multi-source / profile path as a
 * YUV frame, which keeps every light apart. Returns the output width. */
JNIEXPORT jint JNICALL
Java_com_federicopaglioni_blinko_RsCore_convertRawToBgra(JNIEnv *env, jclass cls, jobject buf, jint rowStride, jint w, jint h,
        jint cfa, jint black, jint white, jint step, jint rowBin, jobject outBuf)
{
    const uint8_t *px = (const uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
    uint8_t *o = (uint8_t *)(*env)->GetDirectBufferAddress(env, outBuf);
    if (!px || !o || w < 4 || h < 4 || step < 1) return 0;
    if (rowBin < 2 || (rowBin & 1)) return 0;                           /* whole Bayer blocks: the block starts on an even row */
    int bw = w / 2, bh = h / rowBin, ow = bw / step;
    jlong cap = (*env)->GetDirectBufferCapacity(env, outBuf);
    if (cap < (jlong)ow * bh * 4) return 0;
    if (white <= black) white = black + 1;
    /* Integer scaling with 20 fractional bits, rounded, in 64 bits. With 8 bits the factor
     * 255 / (white - black) truncated to 4/256 on a 14-bit sensor, where full scale then came
     * out as 239, below the level the core treats as clipped (250), and to 0 on a 16-bit one. */
    int64_t scale_q20 = ((int64_t)255 << 20) / (white - black);
#define RAW_TO_8(v) ((int)(((int64_t)((v) - black) * scale_q20 + (1 << 19)) >> 20))
    /* cfa: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR -> positions of R and B inside the 2x2 block */
    int r_row = (cfa == 2 || cfa == 3) ? 1 : 0, r_col = (cfa == 1 || cfa == 3) ? 1 : 0;
    for (int by = 0; by < bh; by++) {
        const uint16_t *row0 = (const uint16_t *)(px + (size_t)(rowBin * by) * rowStride), *row1 = (const uint16_t *)(px + (size_t)(rowBin * by + 1) * rowStride);
        uint8_t *d = o + (size_t)by * ow * 4;
        for (int bx = 0; bx < ow; bx++, d += 4) {
            int x = 2 * bx * step;
            int p00 = row0[x], p01 = row0[x + 1], p10 = row1[x], p11 = row1[x + 1];
            int r = r_row == 0 ? (r_col == 0 ? p00 : p01) : (r_col == 0 ? p10 : p11);
            int b = r_row == 0 ? (r_col == 0 ? p11 : p10) : (r_col == 0 ? p01 : p00);
            int g = (p00 + p01 + p10 + p11 - r - b) >> 1;             /* (Gr + Gb) / 2 */
            int vr = RAW_TO_8(r), vg = RAW_TO_8(g), vb = RAW_TO_8(b);
            d[0] = (uint8_t)(vb < 0 ? 0 : vb > 255 ? 255 : vb); d[1] = (uint8_t)(vg < 0 ? 0 : vg > 255 ? 255 : vg); d[2] = (uint8_t)(vr < 0 ? 0 : vr > 255 ? 255 : vr); d[3] = 255;
        }
    }
#undef RAW_TO_8
    return ow;
}

/* RAW_SENSOR frame (16-bit little-endian Bayer, values black..white): per-row R, G, B profiles
 * computed from the mosaic itself, no ISP in between. Columns: 2x2 Bayer blocks whose maximum
 * (sampled every 16th row) is above 30 % of the frame's brightest block; blocks that clip
 * (>= 97 % of white) are dropped when at least 8 unclipped ones remain (the halo carries the
 * modulation a saturated core loses). Row r of the R profile exists only on GR rows and B only
 * on BG rows: the missing rows repeat their neighbour. Values are scaled to 0..255.
 * cfa: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR. Only the row axis is supported.
 * The app uses this path for the strobe calibration, which reads the profile and nothing else:
 * the rows here are sensor rows, while the camera description (g_exposure_rows, g_row_seconds)
 * is the one of the reduced image that RAW frames are decoded from (convertRawToBgra). */
static uint8_t g_rawsel[4096];
static float g_r2[2048], g_g2[2048], g_b2[2048];   /* pair-averaged RAW profiles */
static jint raw_process(JNIEnv *env, const uint8_t *px, jint rowStride, jint w, jint h,
        jint cfa, jint black, jint white, jfloat t, jfloatArray statsOut, jfloatArray profileOut, jfloatArray packetsOut)
{
    ensure_init();
    g_multi_owns = 0;
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
    put_outputs(env, g_rx, n, h, 2 * first, 2 * last + 2, peak, (float)satrows / (float)h, ds, statsOut, profileOut, packetsOut);
    return n;
}

JNIEXPORT jint JNICALL
Java_com_federicopaglioni_blinko_RsCore_processFrameRaw(JNIEnv *env, jclass cls, jobject buf, jint rowStride, jint w, jint h,
        jint cfa, jint black, jint white, jfloat t, jfloatArray statsOut, jfloatArray profileOut, jfloatArray packetsOut)
{
    const uint8_t *px = (const uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
    if (!px || w <= 0 || h <= 0 || h > 4096) return 0;
    pthread_mutex_lock(&g_lock);
    jint n = raw_process(env, px, rowStride, w, h, cfa, black, white, t, statsOut, profileOut, packetsOut);
    pthread_mutex_unlock(&g_lock);
    return n;
}

/* The last r, g, b profiles of the RAW path (n rows each): out[0..n) = r, [n..2n) = g, [2n..3n) = b. Returns n. */
JNIEXPORT jint JNICALL
Java_com_federicopaglioni_blinko_RsCore_lastProfiles(JNIEnv *env, jclass cls, jfloatArray out)
{
    jsize m = (*env)->GetArrayLength(env, out);
    pthread_mutex_lock(&g_lock);
    int n = g_last_n;
    if (n <= 0 || m < 3 * n) n = 0;
    else {
        (*env)->SetFloatArrayRegion(env, out, 0, n, g_r);
        (*env)->SetFloatArrayRegion(env, out, n, n, g_g);
        (*env)->SetFloatArrayRegion(env, out, 2 * n, n, g_b);
    }
    pthread_mutex_unlock(&g_lock);
    return n;
}

/* Decoder, single receiver and every track: minimum local contrast (max - min over a chip window) to trust a row. */
JNIEXPORT void JNICALL
Java_com_federicopaglioni_blinko_RsCore_setMinContrast(JNIEnv *env, jclass cls, jfloat v)
{
    pthread_mutex_lock(&g_lock);
    ensure_init();
    g_min_contrast = v;
    apply_config();
    pthread_mutex_unlock(&g_lock);
}

/* The camera description of both receivers (rs_camera_t), in rows of the image they decode:
 * setRowTime is the time of one row in seconds (the stitcher's phase prediction across frames),
 * setExposureRows the exposure in rows (exposure_us / row_us, for the exposure-aware detector);
 * 0 = unknown. */
JNIEXPORT void JNICALL
Java_com_federicopaglioni_blinko_RsCore_setRowTime(JNIEnv *env, jclass cls, jfloat seconds)
{
    pthread_mutex_lock(&g_lock);
    ensure_init();
    g_row_seconds = seconds;
    apply_config();
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL
Java_com_federicopaglioni_blinko_RsCore_setExposureRows(JNIEnv *env, jclass cls, jfloat rows)
{
    pthread_mutex_lock(&g_lock);
    ensure_init();
    g_exposure_rows = rows;
    apply_config();
    pthread_mutex_unlock(&g_lock);
}

/* The next complete message: metaOut = (slot, level, logical source; 0 from the single
 * receiver), the bytes of its text in textOut, their number returned; -1 when there is none.
 * Fields and bytes, not one formatted string: this used to return "slot|level|text|source" for
 * Kotlin to split, but '|' is in the protocol's text alphabet, and a text byte >= 0x80 is not
 * the modified UTF-8 NewStringUTF requires (CheckJNI aborts on it).
 * The single receiver's queue is read only when it decoded the last frame: while the
 * multi-source receiver owns the frames, its messages are the only ones delivered. */
JNIEXPORT jint JNICALL
Java_com_federicopaglioni_blinko_RsCore_nextMessage(JNIEnv *env, jclass cls, jintArray metaOut, jbyteArray textOut)
{
    rs_message_t m; int tid = 0, have = 0;
    pthread_mutex_lock(&g_lock);
    ensure_init();
    if (g_mq_len > 0) {
        m = g_mq[0]; tid = g_mq_track[0];
        for (int i = 1; i < g_mq_len; i++) { g_mq[i - 1] = g_mq[i]; g_mq_track[i - 1] = g_mq_track[i]; }
        g_mq_len--; have = 1;
    } else if (!g_multi_owns && rs_rx_pop_message(g_rx, &m)) have = 1;
    pthread_mutex_unlock(&g_lock);
    if (!have) return -1;
    jint meta[3] = { m.id, m.level, tid };
    (*env)->SetIntArrayRegion(env, metaOut, 0, 3, meta);
    jsize n = 0, cap = (*env)->GetArrayLength(env, textOut);
    while (n < (jsize)sizeof(m.text) && n < cap && m.text[n]) n++;
    (*env)->SetByteArrayRegion(env, textOut, 0, n, (const jbyte *)m.text);
    return n;
}
