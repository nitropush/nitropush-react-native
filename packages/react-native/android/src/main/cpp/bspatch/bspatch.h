/*-
 * Copyright 2003-2005 Colin Percival — BSD-licensed (see bspatch.c)
 */
#ifndef BSPATCH_H
#define BSPATCH_H
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

int bspatch_apply(const char *oldfile, const char *patchfile, const char *newfile, uint64_t expected_size);

#ifdef __cplusplus
}
#endif

#endif /* BSPATCH_H */
