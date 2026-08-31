import { configure } from "@nitropush/react-native";

// A single hosted client for every route. Hosted URLs and the deployment key
// stay in native configuration; no credential is copied into the JS bundle.
export const nitropushClient = configure();
