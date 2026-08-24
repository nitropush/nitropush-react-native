#include <stdio.h>

#include "bspatch.h"

int main(int argc, char **argv)
{
    if (argc != 4) {
        fprintf(stderr, "usage: %s OLD PATCH NEW\n", argv[0]);
        return 64;
    }

    return bspatch_apply(argv[1], argv[2], argv[3]);
}
