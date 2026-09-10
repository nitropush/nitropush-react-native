#include <stdio.h>
#include <stdlib.h>

#include "bspatch.h"

int main(int argc, char **argv)
{
    if (argc != 5) {
        fprintf(stderr, "usage: %s OLD PATCH NEW EXPECTED_SIZE\n", argv[0]);
        return 64;
    }

    return bspatch_apply(argv[1], argv[2], argv[3], strtoull(argv[4], NULL, 10));
}
