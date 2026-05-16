/**
 * @file Post-codegen patcher for @nitropush/react-native.
 *
 * Runs after `nitrogen` from the `codegen` script. Applies three
 * fix-ups to the generated bridge so a fresh `pod install` + Xcode
 * build / Android Gradle build succeeds without manual edits:
 *
 *   1. Android — strip the `margelo/nitro/` path prefix from
 *      `NitroPushOnLoad.cpp`. Nitrogen emits `#include "margelo/nitro/…"`
 *      paths that only resolve when the package lives at that namespace;
 *      our custom Kotlin package (com.nitropush) needs them flat.
 *
 *   2. iOS Swift-Cxx bridge header — add a missing
 *      `#include <NitroModules/NitroDefines.hpp>` (NON_NULL / similar
 *      macros), and fully-qualify any `Result<T>` references that the
 *      strict-modular-headers pass in Xcode 26 otherwise resolves
 *      ambiguously.
 *
 *   3. iOS Swift-Cxx bridge cpp — neutralise the
 *      `create_Func_void_double` factory. Swift C++ interop currently
 *      exposes the closure as a bridged C++ record rather than the
 *      `NitroPush::Func_void_double` wrapper Nitrogen expects, so the
 *      generated body fails to compile. Replace with a no-op stub —
 *      the JS-side progress listener still works because the JS path
 *      uses the Swift closure directly, not the C++ bridge.
 *
 * The script is idempotent: every `replace()` is a no-op when its
 * pattern is missing, so it's safe to re-run after subsequent
 * codegen passes.
 */
const path = require('node:path')
const { writeFile, readFile, access } = require('node:fs/promises')

async function fileExists(filePath) {
  try {
    await access(filePath)
    return true
  } catch {
    return false
  }
}

const androidWorkaround = async () => {
  const androidOnLoadFile = path.join(
    process.cwd(),
    'nitrogen/generated/android',
    'NitroPushOnLoad.cpp'
  )
  if (!(await fileExists(androidOnLoadFile))) return

  const str = await readFile(androidOnLoadFile, { encoding: 'utf8' })
  await writeFile(androidOnLoadFile, str.replace(/margelo\/nitro\//g, ''))
}

const iosBridgeHeaderWorkaround = async () => {
  const bridgeHeaderFile = path.join(
    process.cwd(),
    'nitrogen/generated/ios',
    'NitroPush-Swift-Cxx-Bridge.hpp'
  )
  if (!(await fileExists(bridgeHeaderFile))) return

  const str = await readFile(bridgeHeaderFile, { encoding: 'utf8' })
  let patched = str

  if (!patched.includes('#include <NitroModules/NitroDefines.hpp>')) {
    patched = patched.replace(
      '#pragma once',
      '#pragma once\n#include <NitroModules/NitroDefines.hpp>'
    )
  }

  // `Result<T>` references in the generated header are unqualified; the
  // Xcode 26 modular-headers pass can't resolve them through the umbrella
  // unless they're fully-qualified to `margelo::nitro::Result<T>`. These
  // replaces are idempotent — silently no-op when the pattern is absent.
  patched = patched
    .replace(
      'using Result_double_ = Result<double>;',
      'using Result_double_ = margelo::nitro::Result<double>;'
    )
    .replace(
      'return Result<double>::withValue(std::move(value));',
      'return margelo::nitro::Result<double>::withValue(std::move(value));'
    )
    .replace(
      'return Result<double>::withError(error);',
      'return margelo::nitro::Result<double>::withError(error);'
    )
    .replace(
      'using Result_std__shared_ptr_Promise_Result___ = Result<std::shared_ptr<Promise<Result>>>;',
      'using Result_std__shared_ptr_Promise_Result___ = margelo::nitro::Result<std::shared_ptr<Promise<Result>>>;'
    )
    .replace(
      'return Result<std::shared_ptr<Promise<Result>>>::withValue(value);',
      'return margelo::nitro::Result<std::shared_ptr<Promise<Result>>>::withValue(value);'
    )
    .replace(
      'return Result<std::shared_ptr<Promise<Result>>>::withError(error);',
      'return margelo::nitro::Result<std::shared_ptr<Promise<Result>>>::withError(error);'
    )
    .replace(
      'using Result_Result_ = Result<Result>;',
      'using Result_Result_ = margelo::nitro::Result<margelo::nitro::nitropush::Result>;'
    )
    .replace(
      'return Result<Result>::withValue(value);',
      'return margelo::nitro::Result<margelo::nitro::nitropush::Result>::withValue(value);'
    )
    .replace(
      'return Result<Result>::withError(error);',
      'return margelo::nitro::Result<margelo::nitro::nitropush::Result>::withError(error);'
    )

  await writeFile(bridgeHeaderFile, patched)
}

const iosBridgeCppWorkaround = async () => {
  const bridgeCppFile = path.join(
    process.cwd(),
    'nitrogen/generated/ios',
    'NitroPush-Swift-Cxx-Bridge.cpp'
  )
  if (!(await fileExists(bridgeCppFile))) return

  const str = await readFile(bridgeCppFile, { encoding: 'utf8' })
  let patched = str

  patched = patched.replace(
    '  // pragma MARK: std::function<void(double /* progress */)>\n' +
      '  Func_void_double create_Func_void_double(void* NON_NULL swiftClosureWrapper) noexcept {\n' +
      '    auto swiftClosure = NitroPush::Func_void_double::fromUnsafe(swiftClosureWrapper);\n' +
      '    return [swiftClosure = std::move(swiftClosure)](double progress) mutable -> void {\n' +
      '      swiftClosure.call(progress);\n' +
      '    };\n' +
      '  }',
    '  // pragma MARK: std::function<void(double /* progress */)>\n' +
      '  Func_void_double create_Func_void_double(void* NON_NULL swiftClosureWrapper) noexcept {\n' +
      '    // Swift C++ interop currently exposes `Func_void_double` as a bridged C++ record\n' +
      '    // instead of the `NitroPush::Func_void_double` wrapper class, so this conversion\n' +
      '    // path is unavailable in generated headers.\n' +
      '    (void)swiftClosureWrapper;\n' +
      '    return [](double) -> void {};\n' +
      '  }'
  )

  await writeFile(bridgeCppFile, patched)
}

const run = async () => {
  await androidWorkaround()
  await iosBridgeHeaderWorkaround()
  await iosBridgeCppWorkaround()
}

run().catch((err) => {
  console.error('[@nitropush/react-native] post-script failed:', err)
  process.exitCode = 1
})
