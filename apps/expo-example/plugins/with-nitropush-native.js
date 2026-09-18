const { loadProjectEnv } = require("@expo/env");
const withNitroPush = require("@nitropush/react-native/app.plugin.js");

/**
 * Keeps build credentials out of Expo's public config. The public plugin list
 * contains only this wrapper path; the deployment key is passed directly to
 * the NitroPush native mods while prebuild is evaluating them.
 */
module.exports = (config) => {
  loadProjectEnv(__dirname + "/..", { silent: true });

  const deploymentKey = process.env.NITROPUSH_DEPLOYMENT_KEY;

  if (!deploymentKey || !deploymentKey.trim()) {
    throw new Error(
      "NITROPUSH_DEPLOYMENT_KEY is required for the Expo native build",
    );
  }

  return withNitroPush(config, {
    ios: true,
    android: true,
    deploymentKey: deploymentKey.trim(),
    enableDeltaUpdates:
      process.env.NITROPUSH_ENABLE_DELTA_UPDATES === "true",
  });
};
