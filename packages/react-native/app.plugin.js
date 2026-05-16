// Entry point for Expo's config-plugin loader.
//
// The plugin source lives in TypeScript at `src/plugin/index.ts`; the
// compiled output lives at `plugin/build/index.js`. Run
// `yarn workspace @nitropush/react-native build:plugin` after editing the
// source so Expo's loader (which runs in Node and expects plain JS) sees
// the latest version.
module.exports = require("./plugin/build/index").default;
