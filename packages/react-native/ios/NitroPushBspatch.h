#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Thin Objective-C wrapper around the vendored bspatch C function.
/// Exposed to Swift via the pod's umbrella header.
@interface NitroPushBspatch : NSObject

/**
 * Apply a bsdiff4 patch.
 *
 * @param basePath   Path to the base (old) file.
 * @param patchPath  Path to the bsdiff4 patch file.
 * @param outPath    Path where the patched output should be written.
 * @return 0 on success, non-zero on failure.
 */
+ (int)applyWithBasePath:(NSString *)basePath
              patchPath:(NSString *)patchPath
                outPath:(NSString *)outPath;

@end

NS_ASSUME_NONNULL_END
