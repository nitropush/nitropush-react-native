import { Image } from "expo-image";
import { useState } from "react";
import { View } from "react-native";

import { ThemedText } from "@/components/themed-text";
import { assetTestFixture } from "@/lib/asset-test-fixture";

export function AssetTestPanel() {
  const [results, setResults] = useState<Record<string, string>>({});

  return (
    <View style={{ gap: 8 }} testID="asset-test-panel">
      <ThemedText type="subtitle" selectable>
        Asset test: {assetTestFixture.stage}
      </ThemedText>
      <ThemedText selectable>{assetTestFixture.description}</ThemedText>
      <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 12 }}>
        {assetTestFixture.images.map((fixture) => (
          <View key={fixture.id} style={{ width: 140, gap: 4 }}>
            <Image
              source={fixture.source}
              contentFit="contain"
              accessibilityLabel={fixture.label}
              style={{
                width: 140,
                height: 90,
                backgroundColor: "#e6f4fe",
                borderRadius: 8,
              }}
              onLoad={() =>
                setResults((current) => ({
                  ...current,
                  [fixture.id]: "loaded",
                }))
              }
              onError={() =>
                setResults((current) => ({
                  ...current,
                  [fixture.id]: "image error",
                }))
              }
            />
            <ThemedText style={{ fontSize: 12 }} selectable>
              {fixture.label}
            </ThemedText>
            <ThemedText style={{ fontSize: 12 }} selectable>
              {results[fixture.id] ?? "loading"}
            </ThemedText>
          </View>
        ))}
      </View>
      <ThemedText style={{ fontSize: 12, opacity: 0.7 }} selectable>
        These labels identify fixtures, not billing results. Verify network
        delivery and charges on the server.
      </ThemedText>
    </View>
  );
}
