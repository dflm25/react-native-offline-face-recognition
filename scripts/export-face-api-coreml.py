"""Export the pinned face-api.js recognition network for the iOS experiment."""
import argparse
import hashlib
import json
from pathlib import Path

import coremltools as ct
from coremltools.models.neural_network.quantization_utils import quantize_weights
import numpy as np
import tensorflow as tf

MANIFEST = "face_recognition_model-weights_manifest.json"
CHECKSUMS = {
    MANIFEST: "6619f4126f845c1f7857f39cbd79565f375734f46e0dd25d9602f8dc21cda9f5",
    "face_recognition_model-shard1": "412566a2b8d814d84c60b8055ec5d3b3b2328ef7cd7853384e03ec3db7b053d8",
    "face_recognition_model-shard2": "69350fdecd845c532e44dd8f7d0521c773505ef46b87cc34f46640a0cc334ecc",
}


def load_weights(directory):
    files = {name: (directory / name).read_bytes() for name in CHECKSUMS}
    for name, digest in CHECKSUMS.items():
        if hashlib.sha256(files[name]).hexdigest() != digest:
            raise ValueError(f"Unexpected hash for {name}")

    weights = {}
    for group in json.loads(files[MANIFEST]):
        source = b"".join(files[name] for name in group["paths"])
        offset = 0
        for spec in group["weights"]:
            quantization = spec.get("quantization")
            dtype = np.dtype(quantization["dtype"] if quantization else "<f4")
            count = int(np.prod(spec["shape"]))
            values = np.frombuffer(source, dtype=dtype, count=count, offset=offset)
            offset += count * dtype.itemsize
            values = values.astype(np.float32).reshape(spec["shape"])
            if quantization:
                values = values * quantization["scale"] + quantization["min"]
            weights[spec["name"]] = values
        if offset != len(source):
            raise ValueError("The manifest did not consume the entire weight group")
    return weights


def build_network(weights):
    def conv(x, name, stride=1, relu=True):
        scale = weights[f"{name}/scale/weights"]
        filters = weights[f"{name}/conv/filters"] * scale
        bias = weights[f"{name}/conv/bias"] * scale + weights[f"{name}/scale/biases"]
        output = tf.nn.conv2d(x, filters, strides=stride, padding="VALID" if stride == 2 else "SAME")
        output = tf.nn.bias_add(output, bias)
        return tf.nn.relu(output) if relu else output

    def residual(x, name, down=False):
        output = conv(x, f"{name}/conv1", stride=2 if down else 1)
        output = conv(output, f"{name}/conv2", relu=False)
        if down:
            x = tf.nn.avg_pool2d(x, 2, 2, "VALID")
            # The original pads the bottom/right of the residual branch and
            # appends zero channels to the pooled branch when their shapes differ.
            dh = x.shape[1] - output.shape[1]
            dw = x.shape[2] - output.shape[2]
            dc = output.shape[3] - x.shape[3]
            if dh or dw:
                output = tf.pad(output, [[0, 0], [0, dh], [0, dw], [0, 0]])
            if dc:
                x = tf.pad(x, [[0, 0], [0, 0], [0, 0], [0, dc]])
        return tf.nn.relu(x + output)

    @tf.function(input_signature=[tf.TensorSpec([1, 150, 150, 3], tf.float32, name="face")])
    def network(face):
        x = face - tf.constant([122.782, 117.001, 104.298], dtype=tf.float32) / 256
        x = conv(x, "conv32_down", stride=2)
        x = tf.nn.max_pool2d(x, 3, 2, "VALID")
        for channels, count in ((32, 3), (64, 3), (128, 2), (256, 2)):
            if channels != 32:
                x = residual(x, f"conv{channels}_down", down=True)
            for block in range(1, count + 1):
                x = residual(x, f"conv{channels}_{block}")
        x = residual(x, "conv256_down_out", down=True)
        return tf.matmul(tf.reduce_mean(x, axis=[1, 2]), weights["fc"])

    return network


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("weights", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    network = build_network(load_weights(args.weights))
    model = ct.convert(
        [network.get_concrete_function()],
        source="tensorflow",
        convert_to="neuralnetwork",
        inputs=[ct.ImageType(name="face", shape=(1, 150, 150, 3), scale=1 / 256, channel_first=False)],
    )
    output = model.get_spec().description.output[0].name
    spec = model.get_spec()
    ct.utils.rename_feature(spec, output, "embedding")
    model = quantize_weights(ct.models.MLModel(spec), nbits=16)
    model.short_description = "Experimental native port of face-api.js FaceRecognitionNet"
    model.author = "Davis King (weights), Vincent Mühler (face-api.js)"
    args.destination.parent.mkdir(parents=True, exist_ok=True)
    model.save(str(args.destination))


if __name__ == "__main__":
    main()
