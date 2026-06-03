require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "NitroPush"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => min_ios_version_supported, :visionos => 1.0 }
  s.source       = { :git => "https://github.com/nitropush/nitropush.git", :tag => "#{s.version}" }

  s.source_files = [
    # Implementation (Swift + Objective-C++)
    "ios/**/*.{swift,m,mm}",
    # Vendored bspatch C implementation (experimental delta updates)
    "ios/**/*.{c,h}",
    # Implementation (C++ objects)
    "cpp/**/*.{hpp,cpp}",
  ]

  # libbz2 is required by bspatch.c (ships on every Apple platform).
  # Set this BEFORE add_nitrogen_files so the merge in autolinking.rb
  # preserves it. $(inherited) chains with any flags the build system adds.
  s.pod_target_xcconfig = { "OTHER_LDFLAGS" => "$(inherited) -lbz2" }

  load 'nitrogen/generated/ios/NitroPush+autolinking.rb'
  add_nitrogen_files(s)

  s.dependency 'React-jsi'
  s.dependency 'React-callinvoker'
  install_modules_dependencies(s)
end
