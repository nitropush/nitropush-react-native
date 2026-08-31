const baseConfig = require("./app.json").expo;

require("@expo/env").loadProjectEnv(__dirname, { silent: true });

module.exports = () => {
  const enableDeltaUpdates =
    process.env.NITROPUSH_ENABLE_DELTA_UPDATES === "true";

  return {
    ...baseConfig,
    extra: {
      ...(baseConfig.extra ?? {}),
      nitropushDeltaUpdates: enableDeltaUpdates,
    },
  };
};
