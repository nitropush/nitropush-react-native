#import "NitroPushBspatch.h"
#include "bspatch/bspatch.h"

@implementation NitroPushBspatch

+ (int)applyWithBasePath:(NSString *)basePath
              patchPath:(NSString *)patchPath
                outPath:(NSString *)outPath {
    return bspatch_apply(basePath.UTF8String, patchPath.UTF8String, outPath.UTF8String);
}

@end
