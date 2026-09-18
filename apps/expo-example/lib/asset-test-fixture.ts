// Keep only this stage's static requires in the Metro dependency graph.
// Future OTA-only images must not be imported by the baseline native binary.
export const assetTestFixture = {
  stage: "baseline",
  description: "Native baseline: both images are embedded in this binary.",
  images: [
    {
      id: "embedded",
      label: "Unchanged embedded asset",
      source: require("../assets/images/react-logo.png"),
    },
    {
      id: "changed",
      label: "Baseline image",
      source: require("../assets/images/partial-react-logo.png"),
    },
  ],
};
