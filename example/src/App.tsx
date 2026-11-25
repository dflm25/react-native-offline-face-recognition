import { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Pressable,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {
  deleteFaceTemplate,
  getFaceCapabilities,
  hasFaceTemplate,
  registerFace,
  verifyFace,
  type FaceCapabilities,
  type FaceOptions,
} from 'react-native-offline-face-recognition';

type Operation = 'idle' | 'register' | 'verify' | 'delete';

const ERROR_LABELS: Record<string, string> = {
  invalid_options: 'Invalid options.',
  camera_unavailable: 'No front camera is available on this device.',
  camera_permission_denied: 'Camera access was denied. Enable it in Settings.',
  already_in_progress: 'A face capture is already open.',
  cancelled: 'Capture cancelled.',
  timeout: 'The capture timed out. Try again with better lighting.',
  no_face: 'No face was detected.',
  multiple_faces: 'More than one person was visible.',
  face_too_small: 'Move closer to the camera.',
  liveness_failed:
    'Liveness check failed. Use your real face, not a photo or video.',
  face_mismatch: 'This face does not match the registered template.',
  no_template: 'Register a face first.',
  storage_error: 'The local encrypted template could not be read or written.',
  not_supported: 'This platform is not supported.',
  unknown_error: 'An unexpected error occurred.',
};

export default function App() {
  const [enrolled, setEnrolled] = useState<boolean | null>(null);
  const [capabilities, setCapabilities] = useState<FaceCapabilities | null>(
    null
  );
  const [operation, setOperation] = useState<Operation>('idle');
  const [message, setMessage] = useState('Ready.');
  const [details, setDetails] = useState<string[]>([]);

  const refresh = useCallback(async () => {
    const [hasTemplate, caps] = await Promise.all([
      hasFaceTemplate().catch(() => false),
      getFaceCapabilities(),
    ]);
    setEnrolled(hasTemplate);
    setCapabilities(caps);
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const runCapture = useCallback(
    async (kind: 'register' | 'verify') => {
      const options: FaceOptions = {
        requireLiveness: true,
        timeout: 45000,
        threshold: 0.68,
      };
      setOperation(kind);
      setMessage(
        kind === 'register' ? 'Opening enrollment…' : 'Opening verification…'
      );
      setDetails([]);

      try {
        if (kind === 'register') {
          const result = await registerFace(options);
          if (!result.success) {
            setMessage(
              ERROR_LABELS[result.error ?? 'unknown_error'] ??
                result.error ??
                'Failed.'
            );
            return;
          }
          setEnrolled(true);
          setMessage('Face enrolled on this device.');
          setDetails([
            `Template: ${result.templateId ?? 'local'}`,
            'Encrypted in Keychain/Keystore',
            'No image or embedding uploaded',
          ]);
        } else {
          const result = await verifyFace(options);
          if (!result.success) {
            setMessage(
              ERROR_LABELS[result.error ?? 'unknown_error'] ??
                result.error ??
                'Failed.'
            );
            setDetails(
              [
                result.score !== undefined
                  ? `Similarity: ${(result.score * 100).toFixed(1)}%`
                  : '',
                result.livenessPassed !== undefined
                  ? `Liveness: ${result.livenessPassed ? 'passed' : 'not passed'}`
                  : '',
              ].filter(Boolean)
            );
            return;
          }
          setMessage('Face verified. You can clock in.');
          setDetails(
            [
              result.score !== undefined
                ? `Similarity: ${(result.score * 100).toFixed(1)}%`
                : '',
              result.livenessPassed
                ? 'Liveness: passed'
                : 'Liveness: not required',
            ].filter(Boolean)
          );
        }
      } finally {
        setOperation('idle');
        await refresh();
      }
    },
    [refresh]
  );

  const removeTemplate = useCallback(() => {
    Alert.alert(
      'Delete face template?',
      'This removes the encrypted template from this device.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Delete',
          style: 'destructive',
          onPress: async () => {
            setOperation('delete');
            setMessage('Deleting local template…');
            try {
              await deleteFaceTemplate();
              setEnrolled(false);
              setDetails([]);
              setMessage('Face template deleted.');
            } catch {
              setMessage('Could not delete the template.');
            } finally {
              setOperation('idle');
            }
          },
        },
      ]
    );
  }, []);

  const busy = operation !== 'idle';

  return (
    <SafeAreaView style={styles.safe}>
      <ScrollView contentContainerStyle={styles.container}>
        <View style={styles.header}>
          <View style={styles.logo}>
            <Text style={styles.logoText}>OF</Text>
          </View>
          <View style={styles.headerText}>
            <Text style={styles.title}>Offline Face</Text>
            <Text style={styles.subtitle}>
              Clock-in face verification, fully on-device
            </Text>
          </View>
        </View>

        <View style={styles.statusCard}>
          <View style={styles.statusRow}>
            <View
              style={[styles.dot, enrolled ? styles.dotOn : styles.dotOff]}
            />
            <Text style={styles.statusTitle}>
              {enrolled === null
                ? 'Checking…'
                : enrolled
                  ? 'Face enrolled'
                  : 'No face enrolled'}
            </Text>
          </View>
          <Text style={styles.statusBody}>{message}</Text>
          {details.length > 0 ? (
            <View style={styles.details}>
              {details.map((detail) => (
                <Text key={detail} style={styles.detail}>
                  • {detail}
                </Text>
              ))}
            </View>
          ) : null}
        </View>

        <View style={styles.actions}>
          <Pressable
            disabled={busy || capabilities?.available === false}
            onPress={() => {
              runCapture('register');
            }}
            style={({ pressed }) => [
              styles.primaryButton,
              (busy || capabilities?.available === false) && styles.disabled,
              pressed && styles.pressed,
            ]}
          >
            {operation === 'register' ? (
              <ActivityIndicator color="#07130D" />
            ) : (
              <Text style={styles.primaryText}>
                {enrolled ? 'Re-enroll face' : 'Register face'}
              </Text>
            )}
          </Pressable>

          <Pressable
            disabled={busy || !enrolled}
            onPress={() => {
              runCapture('verify');
            }}
            style={({ pressed }) => [
              styles.secondaryButton,
              (busy || !enrolled) && styles.disabled,
              pressed && styles.pressed,
            ]}
          >
            {operation === 'verify' ? (
              <ActivityIndicator color="#3DDC97" />
            ) : (
              <Text style={styles.secondaryText}>Verify face</Text>
            )}
          </Pressable>

          <Pressable
            disabled={busy || !enrolled}
            onPress={removeTemplate}
            style={({ pressed }) => [
              styles.ghostButton,
              (busy || !enrolled) && styles.disabled,
              pressed && styles.pressed,
            ]}
          >
            {operation === 'delete' ? (
              <ActivityIndicator color="#D98C8C" />
            ) : (
              <Text style={styles.ghostText}>Delete local template</Text>
            )}
          </Pressable>
        </View>

        <View style={styles.capabilities}>
          <Text style={styles.sectionTitle}>Device capabilities</Text>
          <Row
            label="Front camera / module"
            value={formatBoolean(capabilities?.available)}
          />
          <Row label="Platform" value={capabilities?.platform ?? '—'} />
          <Row
            label="Active liveness"
            value={formatBoolean(capabilities?.supportsLiveness)}
          />
          <Row label="Network required" value="No" />
        </View>

        <Text style={styles.note}>
          Face templates stay in this device’s secure storage. Images and
          embeddings are never sent to a server. Liveness is an active
          challenge, not a certified presentation-attack detection system.
        </Text>
      </ScrollView>
    </SafeAreaView>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel}>{label}</Text>
      <Text style={styles.rowValue}>{value}</Text>
    </View>
  );
}

function formatBoolean(value: boolean | undefined): string {
  if (value === undefined) {
    return '—';
  }
  return value ? 'Available' : 'Unavailable';
}

const styles = StyleSheet.create({
  safe: {
    flex: 1,
    backgroundColor: '#07100C',
  },
  container: {
    padding: 20,
    paddingBottom: 40,
    gap: 18,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 14,
    paddingTop: 8,
  },
  logo: {
    width: 48,
    height: 48,
    borderRadius: 16,
    backgroundColor: '#3DDC97',
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoText: {
    color: '#07130D',
    fontWeight: '800',
    fontSize: 18,
  },
  headerText: {
    flex: 1,
  },
  title: {
    color: '#F5FFF9',
    fontSize: 26,
    fontWeight: '700',
    letterSpacing: -0.4,
  },
  subtitle: {
    color: '#8FA99C',
    fontSize: 13,
    marginTop: 3,
  },
  statusCard: {
    backgroundColor: '#0E1A15',
    borderRadius: 22,
    padding: 18,
    borderWidth: 1,
    borderColor: '#1B2E26',
    gap: 8,
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  dot: {
    width: 9,
    height: 9,
    borderRadius: 5,
  },
  dotOn: {
    backgroundColor: '#3DDC97',
  },
  dotOff: {
    backgroundColor: '#5C6F66',
  },
  statusTitle: {
    color: '#F5FFF9',
    fontWeight: '600',
    fontSize: 16,
  },
  statusBody: {
    color: '#A9C1B5',
    fontSize: 14,
    lineHeight: 20,
  },
  details: {
    gap: 3,
    paddingTop: 4,
  },
  detail: {
    color: '#7F9A8D',
    fontSize: 13,
    lineHeight: 19,
  },
  actions: {
    gap: 10,
  },
  primaryButton: {
    backgroundColor: '#3DDC97',
    borderRadius: 16,
    minHeight: 54,
    alignItems: 'center',
    justifyContent: 'center',
  },
  primaryText: {
    color: '#07130D',
    fontSize: 16,
    fontWeight: '700',
  },
  secondaryButton: {
    borderRadius: 16,
    minHeight: 54,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: '#3DDC97',
    backgroundColor: '#0C1813',
  },
  secondaryText: {
    color: '#3DDC97',
    fontSize: 16,
    fontWeight: '700',
  },
  ghostButton: {
    minHeight: 46,
    alignItems: 'center',
    justifyContent: 'center',
  },
  ghostText: {
    color: '#D98C8C',
    fontSize: 14,
    fontWeight: '600',
  },
  pressed: {
    opacity: 0.75,
  },
  disabled: {
    opacity: 0.35,
  },
  capabilities: {
    backgroundColor: '#0B1511',
    borderRadius: 18,
    padding: 16,
    gap: 2,
  },
  sectionTitle: {
    color: '#F5FFF9',
    fontSize: 14,
    fontWeight: '700',
    marginBottom: 8,
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 8,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#1B2E26',
  },
  rowLabel: {
    color: '#8FA99C',
    fontSize: 13,
  },
  rowValue: {
    color: '#D6E8DF',
    fontSize: 13,
    fontWeight: '600',
  },
  note: {
    color: '#61796D',
    fontSize: 12,
    lineHeight: 18,
    paddingHorizontal: 4,
  },
});
