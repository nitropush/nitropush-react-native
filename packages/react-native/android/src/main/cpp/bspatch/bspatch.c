/*-
 * Copyright 2003-2005 Colin Percival
 * All rights reserved
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted providing that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING
 * IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

#include "bspatch.h"
#include <bzlib.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Match the SDK's per-bundle limit, independently of untrusted patch headers. */
#define MAX_BYTES (64U * 1024U * 1024U)
#define MAX_CONTROL_TRIPLETS 1000000U

static int64_t offtin(const uint8_t *buf) {
    uint64_t value = buf[7] & 0x7f;
    for (int i = 6; i >= 0; --i) value = value * 256 + buf[i];
    return (buf[7] & 0x80) ? -(int64_t)value : (int64_t)value;
}

typedef struct { bz_stream bz; int initialized; int ended; } bounded_stream;

static int stream_open(bounded_stream *s, uint8_t *bytes, size_t count) {
    if (count > UINT_MAX || BZ2_bzDecompressInit(&s->bz, 0, 0) != BZ_OK) return 1;
    s->initialized = 1;
    s->bz.next_in = (char *)bytes;
    s->bz.avail_in = (unsigned int)count;
    return 0;
}

/* Each stream sees only its declared compressed segment. */
static int stream_read(bounded_stream *s, uint8_t *out, size_t count) {
    if (!count) return 0;
    if (s->ended || count > INT_MAX) return 1;
    s->bz.next_out = (char *)out;
    s->bz.avail_out = (unsigned int)count;
    while (s->bz.avail_out) {
        unsigned int input = s->bz.avail_in, output = s->bz.avail_out;
        int rc = BZ2_bzDecompress(&s->bz);
        if (rc == BZ_STREAM_END) {
            s->ended = 1;
            return s->bz.avail_out != 0;
        }
        if (rc != BZ_OK || (input == s->bz.avail_in && output == s->bz.avail_out)) return 1;
    }
    return 0;
}

static int stream_finish(bounded_stream *s) {
    if (!s->ended) {
        uint8_t extra;
        /* Finish the checksum/trailer, but reject any unconsumed output. */
        s->bz.next_out = (char *)&extra;
        s->bz.avail_out = 1;
        int rc = BZ2_bzDecompress(&s->bz);
        if (rc != BZ_STREAM_END || s->bz.avail_out != 1) return 1;
        s->ended = 1;
    }
    return s->bz.avail_in != 0;
}

static int read_file(const char *path, uint8_t **data, size_t *size) {
    FILE *f = fopen(path, "rb");
    if (!f) return 1;
    int rc = 1;
    if (fseeko(f, 0, SEEK_END)) goto done;
    off_t length = ftello(f);
    if (length < 0 || (uint64_t)length > MAX_BYTES || fseeko(f, 0, SEEK_SET)) goto done;
    *size = (size_t)length;
    *data = malloc(*size ? *size : 1);
    if (!*data || fread(*data, 1, *size, f) != *size) goto done;
    rc = 0;
done:
    fclose(f);
    return rc;
}

static int add_checked(int64_t a, int64_t b, int64_t *result) {
    if ((b > 0 && a > INT64_MAX - b) || (b < 0 && a < INT64_MIN - b)) return 1;
    *result = a + b;
    return 0;
}

int bspatch_apply(const char *oldfile, const char *patchfile, const char *newfile,
                  uint64_t expected_size) {
    uint8_t *patch = NULL, *old = NULL, *output = NULL;
    size_t patch_size = 0, old_size = 0;
    bounded_stream streams[3] = {0};
    int rc = 1;
    FILE *f = NULL;
    if (expected_size > MAX_BYTES) goto done;
    if (read_file(patchfile, &patch, &patch_size) || patch_size < 32 ||
        memcmp(patch, "BSDIFF40", 8)) goto done;
    int64_t control_size = offtin(patch + 8), diff_size = offtin(patch + 16);
    int64_t new_size = offtin(patch + 24);
    if (control_size < 0 || diff_size < 0 || new_size < 0 ||
        (uint64_t)new_size != expected_size ||
        (uint64_t)control_size > patch_size - 32 ||
        (uint64_t)diff_size > patch_size - 32 - (size_t)control_size) goto done;
    size_t diff_offset = 32 + (size_t)control_size;
    size_t extra_offset = diff_offset + (size_t)diff_size;
    if (stream_open(&streams[0], patch + 32, (size_t)control_size) ||
        stream_open(&streams[1], patch + diff_offset, (size_t)diff_size) ||
        stream_open(&streams[2], patch + extra_offset, patch_size - extra_offset) ||
        read_file(oldfile, &old, &old_size)) goto done;
    output = malloc(expected_size ? (size_t)expected_size : 1);
    if (!output) goto done;
    int64_t old_pos = 0;
    size_t new_pos = 0;
    unsigned int operations = 0;
    while (new_pos < expected_size) {
        uint8_t bytes[24];
        if (++operations > MAX_CONTROL_TRIPLETS || stream_read(&streams[0], bytes, 24)) goto done;
        int64_t diff = offtin(bytes), extra = offtin(bytes + 8), seek = offtin(bytes + 16);
        if (diff < 0 || extra < 0 || diff > INT_MAX || extra > INT_MAX ||
            (uint64_t)diff > expected_size - new_pos ||
            (uint64_t)extra > expected_size - new_pos - (size_t)diff) goto done;
        int64_t after_diff, after_seek;
        if (add_checked(old_pos, diff, &after_diff) || add_checked(after_diff, seek, &after_seek)) goto done;
        if (stream_read(&streams[1], output + new_pos, (size_t)diff)) goto done;
        for (int64_t i = 0; i < diff; ++i) {
            int64_t index = old_pos + i; /* after_diff was checked before this loop */
            if (index >= 0 && (uint64_t)index < old_size) output[new_pos + (size_t)i] += old[(size_t)index];
        }
        new_pos += (size_t)diff;
        if (stream_read(&streams[2], output + new_pos, (size_t)extra)) goto done;
        new_pos += (size_t)extra;
        old_pos = after_seek; /* negative and seek-only controls remain valid */
    }
    for (int i = 0; i < 3; ++i) if (stream_finish(&streams[i])) goto done;
    f = fopen(newfile, "wb");
    if (!f) goto done;
    if (fwrite(output, 1, (size_t)expected_size, f) != expected_size) goto done;
    rc = 0;
done:
    if (f && fclose(f)) rc = 1;
    for (int i = 0; i < 3; ++i) if (streams[i].initialized) BZ2_bzDecompressEnd(&streams[i].bz);
    free(patch); free(old); free(output);
    return rc;
}
