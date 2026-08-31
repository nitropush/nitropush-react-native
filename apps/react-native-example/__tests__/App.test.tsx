/**
 * @format
 */

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import App from '../App';

jest.mock('@nitropush/react-native', () => ({
  configure: () => ({
    checkForUpdate: jest.fn().mockResolvedValue(null),
    getCurrentPackage: jest.fn().mockResolvedValue(null),
    getPendingPackage: jest.fn().mockResolvedValue(null),
    getUpdateMetadataSync: jest.fn().mockReturnValue(null),
    restartApp: jest.fn().mockResolvedValue(undefined),
    notifyAppReady: jest.fn().mockResolvedValue(undefined),
  }),
}));

test('renders correctly', async () => {
  await ReactTestRenderer.act(() => {
    ReactTestRenderer.create(<App />);
  });
});
