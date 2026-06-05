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
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int64_t offtin(uint8_t *buf)
{
    int64_t y;
    y  = buf[7] & 0x7F;
    y  = y * 256; y += buf[6];
    y  = y * 256; y += buf[5];
    y  = y * 256; y += buf[4];
    y  = y * 256; y += buf[3];
    y  = y * 256; y += buf[2];
    y  = y * 256; y += buf[1];
    y  = y * 256; y += buf[0];
    if (buf[7] & 0x80) y = -y;
    return y;
}

int bspatch_apply(const char *oldfile, const char *patchfile, const char *newfile)
{
    FILE *f, *cpf, *dpf, *epf;
    BZFILE *cpfbz2, *dpfbz2, *epfbz2;
    int cbz2err, dbz2err, ebz2err;
    int fd;
    ssize_t oldsize, newsize;
    ssize_t bzctrllen, bzdatalen;
    uint8_t header[32], buf[8];
    uint8_t *old_data, *new_data;
    int64_t oldpos, newpos;
    int64_t ctrl[3];
    int64_t lenread;
    int64_t i;

    /* Open patch file */
    if ((f = fopen(patchfile, "r")) == NULL) return 1;

    /* Read header */
    if (fread(header, 1, 32, f) < 32) { fclose(f); return 1; }

    /* Check magic "BSDIFF40" */
    if (memcmp(header, "BSDIFF40", 8) != 0) { fclose(f); return 1; }

    /* Read lengths from header */
    bzctrllen = offtin(header + 8);
    bzdatalen = offtin(header + 16);
    newsize   = offtin(header + 24);

    if (bzctrllen < 0 || bzdatalen < 0 || newsize < 0) { fclose(f); return 1; }

    /* Open three sub-streams at the right offsets */
    if ((cpf = fopen(patchfile, "r")) == NULL) { fclose(f); return 1; }
    if (fseeko(cpf, 32, SEEK_SET))             { fclose(f); fclose(cpf); return 1; }

    if ((cpfbz2 = BZ2_bzReadOpen(&cbz2err, cpf, 0, 0, NULL, 0)) == NULL)
        { fclose(f); fclose(cpf); return 1; }

    if ((dpf = fopen(patchfile, "r")) == NULL)
        { BZ2_bzReadClose(&cbz2err, cpfbz2); fclose(f); fclose(cpf); return 1; }
    if (fseeko(dpf, 32 + bzctrllen, SEEK_SET))
        { BZ2_bzReadClose(&cbz2err, cpfbz2); fclose(f); fclose(cpf); fclose(dpf); return 1; }

    if ((dpfbz2 = BZ2_bzReadOpen(&dbz2err, dpf, 0, 0, NULL, 0)) == NULL)
        { BZ2_bzReadClose(&cbz2err, cpfbz2); fclose(f); fclose(cpf); fclose(dpf); return 1; }

    if ((epf = fopen(patchfile, "r")) == NULL)
        { BZ2_bzReadClose(&cbz2err, cpfbz2); BZ2_bzReadClose(&dbz2err, dpfbz2);
          fclose(f); fclose(cpf); fclose(dpf); return 1; }
    if (fseeko(epf, 32 + bzctrllen + bzdatalen, SEEK_SET))
        { BZ2_bzReadClose(&cbz2err, cpfbz2); BZ2_bzReadClose(&dbz2err, dpfbz2);
          fclose(f); fclose(cpf); fclose(dpf); fclose(epf); return 1; }

    if ((epfbz2 = BZ2_bzReadOpen(&ebz2err, epf, 0, 0, NULL, 0)) == NULL)
        { BZ2_bzReadClose(&cbz2err, cpfbz2); BZ2_bzReadClose(&dbz2err, dpfbz2);
          fclose(f); fclose(cpf); fclose(dpf); fclose(epf); return 1; }

    fclose(f);

    /* Read old file */
    if ((f = fopen(oldfile, "r")) == NULL) {
        BZ2_bzReadClose(&cbz2err, cpfbz2); BZ2_bzReadClose(&dbz2err, dpfbz2);
        BZ2_bzReadClose(&ebz2err, epfbz2);
        fclose(cpf); fclose(dpf); fclose(epf); return 1;
    }
    if (fseeko(f, 0, SEEK_END)) { fclose(f); return 1; }
    if ((oldsize = ftello(f)) == -1) { fclose(f); return 1; }
    if (fseeko(f, 0, SEEK_SET)) { fclose(f); return 1; }

    if ((old_data = malloc(oldsize + 1)) == NULL) { fclose(f); return 1; }
    if (fread(old_data, 1, oldsize, f) != (size_t)oldsize) { free(old_data); fclose(f); return 1; }
    fclose(f);

    if ((new_data = malloc(newsize + 1)) == NULL) { free(old_data); return 1; }

    oldpos = 0; newpos = 0;
    while (newpos < newsize) {
        /* Read control triplet */
        for (i = 0; i <= 2; i++) {
            lenread = BZ2_bzRead(&cbz2err, cpfbz2, buf, 8);
            if ((lenread < 8) || ((cbz2err != BZ_OK) && (cbz2err != BZ_STREAM_END))) {
                free(old_data); free(new_data);
                BZ2_bzReadClose(&cbz2err, cpfbz2); BZ2_bzReadClose(&dbz2err, dpfbz2);
                BZ2_bzReadClose(&ebz2err, epfbz2);
                fclose(cpf); fclose(dpf); fclose(epf); return 1;
            }
            ctrl[i] = offtin(buf);
        }

        /* Add old data */
        if (newpos + ctrl[0] > newsize) {
            free(old_data); free(new_data);
            BZ2_bzReadClose(&cbz2err, cpfbz2); BZ2_bzReadClose(&dbz2err, dpfbz2);
            BZ2_bzReadClose(&ebz2err, epfbz2);
            fclose(cpf); fclose(dpf); fclose(epf); return 1;
        }
        lenread = BZ2_bzRead(&dbz2err, dpfbz2, new_data + newpos, ctrl[0]);
        if ((lenread < ctrl[0]) || ((dbz2err != BZ_OK) && (dbz2err != BZ_STREAM_END))) {
            free(old_data); free(new_data);
            BZ2_bzReadClose(&cbz2err, cpfbz2); BZ2_bzReadClose(&dbz2err, dpfbz2);
            BZ2_bzReadClose(&ebz2err, epfbz2);
            fclose(cpf); fclose(dpf); fclose(epf); return 1;
        }
        for (i = 0; i < ctrl[0]; i++) {
            if ((oldpos + i >= 0) && (oldpos + i < oldsize))
                new_data[newpos + i] += old_data[oldpos + i];
        }
        newpos += ctrl[0]; oldpos += ctrl[0];

        /* Copy extra data */
        if (newpos + ctrl[1] > newsize) {
            free(old_data); free(new_data);
            BZ2_bzReadClose(&cbz2err, cpfbz2); BZ2_bzReadClose(&dbz2err, dpfbz2);
            BZ2_bzReadClose(&ebz2err, epfbz2);
            fclose(cpf); fclose(dpf); fclose(epf); return 1;
        }
        lenread = BZ2_bzRead(&ebz2err, epfbz2, new_data + newpos, ctrl[1]);
        if ((lenread < ctrl[1]) || ((ebz2err != BZ_OK) && (ebz2err != BZ_STREAM_END))) {
            free(old_data); free(new_data);
            BZ2_bzReadClose(&cbz2err, cpfbz2); BZ2_bzReadClose(&dbz2err, dpfbz2);
            BZ2_bzReadClose(&ebz2err, epfbz2);
            fclose(cpf); fclose(dpf); fclose(epf); return 1;
        }
        newpos += ctrl[1]; oldpos += ctrl[2];
    }

    BZ2_bzReadClose(&cbz2err, cpfbz2);
    BZ2_bzReadClose(&dbz2err, dpfbz2);
    BZ2_bzReadClose(&ebz2err, epfbz2);
    fclose(cpf); fclose(dpf); fclose(epf);

    /* Write output */
    if ((f = fopen(newfile, "w")) == NULL) { free(old_data); free(new_data); return 1; }
    if (fwrite(new_data, 1, newsize, f) != (size_t)newsize) {
        free(old_data); free(new_data); fclose(f); return 1;
    }
    fclose(f);

    free(old_data);
    free(new_data);
    return 0;
}
