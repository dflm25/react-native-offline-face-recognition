# FaceRecognitionNet experiment on iOS

The iOS example opts in with `OfflineFaceExperimentFaceApi` in its Info.plist. Register a new
face in **OfflineFace ML**, then use **Verify face**. The experimental installation uses a separate
bundle identifier, `com.dlucumi.offlinefaceexperiment`.
The capture screen shows when Core ML is ready and how many samples FaceRecognitionNet has processed.
If an earlier template is selected, the verification screen identifies it as texture matching.

Core ML runs the converted FaceRecognitionNet on a color crop of 150 × 150 pixels and produces
128 floats. The camera, liveness challenge, and public API are the same as in the texture flow.
New experimental templates use format 4 and the mean Euclidean distance across the enrollment
and verification samples. The pilot uses the face-api.js reference cutoff of 0.60. The example
displays `max(0, 1 - distance)` as a score, with a scale distinct from LBP/HOG. The cutoff still
needs calibration on real devices. Android verification continues to use LBP/HOG.

The converted model occupies about 11 MiB in the iOS build. It uses the face recognition weight
shards from [face-api.js at the pinned revision](https://github.com/justadudewhohacks/face-api.js/tree/a86f011d72124e5fb93e59d5c4ab98f699dd5c9c).
The exporter checks the SHA-256 of all three input files. The upstream implementation is MIT,
with its notice included alongside the model. The upstream README credits Davis King for the
weights. His [original dlib model](https://github.com/davisking/dlib-models#dlib_face_recognition_resnet_model_v1datbz2)
is published under CC0 and the README describes its training sources.

On macOS, check out the pinned face-api.js revision and use the `weights` folder as input:

```sh
uv run --python 3.11 --with tensorflow==2.19.0 --with coremltools==9.0 \
  python scripts/export-face-api-coreml.py /path/to/face-api.js/weights ios/Models/FaceApiRecognition.mlmodel
```

The pilot was checked by comparing outputs from Core ML and face-api.js 0.22.2 on three identical
synthetic RGB inputs. The largest Euclidean difference between embeddings was below 0.001.
This checks the model conversion. Testing with the front camera under varied lighting is still
needed to compare verification performance.
