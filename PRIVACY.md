# Privacy — react-native-offline-face-recognition

## Data flow

1. The user taps register or verify in the host app.
2. The native module opens the front camera. Frames exist only in process memory.
3. Face detection and descriptor extraction run locally (Apple Vision / bundled ML Kit).
4. On enrollment, only six float histogram descriptors plus a random local template ID are stored:
   - iOS: a Keychain generic-password item with
     `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` and `kSecAttrSynchronizable = false`.
   - Android: an AES-256-GCM ciphertext in `noBackupFilesDir`; the key never leaves Android
     Keystore.
5. On verification, six fresh descriptors are compared locally. When `returnPhoto: true` is explicitly
   requested for registration, a JPEG face crop is returned as base64 to the host app. It is not saved
   by the library; the host controls its further use, storage, and deletion.
6. Frames and intermediate buffers are released. The library does not send images, descriptors, or
   scores to the library author or any third party; host apps may handle returned data separately.

The package contains no networking code. The Android library manifest does not include
`INTERNET`. The iOS privacy manifest (`ios/PrivacyInfo.xcprivacy`) declares no tracking, no
collected data types, and no required-reason API access.

## Data controller responsibilities

The app publisher is the data controller for the face template. This library only implements local
storage and comparison. The publisher must provide:

- clear notice of what is collected and why;
- a lawful basis and, where required, explicit consent;
- a retention period;
- a deletion path (wire `deleteFaceTemplate()` into account deletion/offboarding);
- access controls for the device and the clock-in backend;
- a privacy impact assessment where local biometric processing is regulated.

## Deletion

`deleteFaceTemplate()` removes the Keychain item on iOS and deletes the encrypted file plus the
Android Keystore key on Android. The operation is idempotent. It does not delete clock-in events
or photos that the host app may have saved separately.

## Children

Do not use this package to enroll children without the consent and legal basis required in your
jurisdiction.
