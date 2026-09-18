# Delta golden fixtures

`old.bundle`, `new.bundle`, and `wrong-base.bundle` are deterministic binary
fixtures with an embedded NUL byte. `old-to-new.bsdiff` is a BSDIFF40 patch
generated from them with the real `bsdiff` command:

```sh
bsdiff old.bundle new.bundle old-to-new.bsdiff
bspatch old.bundle reconstructed.bundle old-to-new.bsdiff
cmp new.bundle reconstructed.bundle
```

`SHA256SUMS` pins every input and the generated patch so fixture drift fails
before either native implementation is exercised. The test runners compile the
iOS and Android vendored `bspatch.c` files separately, then cover successful
application, a corrupt patch header, and a wrong base bundle. A wrong base can
still return success from `bspatch`; the SDK's output SHA-256 check is what
rejects it, and the golden test mirrors that boundary.
