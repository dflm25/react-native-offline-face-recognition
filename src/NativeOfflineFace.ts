import { TurboModuleRegistry, type TurboModule } from 'react-native';

/**
 * Codegen-facing TurboModule contract.
 *
 * Keep this file free of implementation details: React Native Codegen reads it
 * to produce the iOS and Android native specs. The public, fully typed API lives
 * in `index.ts`.
 */
export interface Spec extends TurboModule {
  registerFace(options: Object): Promise<Object>;
  verifyFace(options: Object): Promise<Object>;
  hasFaceTemplate(): Promise<boolean>;
  deleteFaceTemplate(): Promise<void>;
  getFaceCapabilities(): Promise<Object>;
}

export default TurboModuleRegistry.get<Spec>('OfflineFace');
