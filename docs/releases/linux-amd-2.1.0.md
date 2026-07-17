# Linux AMD Release Notes

Release target: `terrain-diffusion-mc-2.1.0-linux-amd+1.21.1.jar`

## Purpose

This release packages the Linux AMD fork changes needed to run Terrain Diffusion on AMD GPUs under Linux through ROCm/MIGraphX.

## Included Fork Changes

- configurable GPU provider selection:
  - `auto`
  - `migraphx`
  - `rocm`
  - `cuda`
  - `directml`
- explicit `inference.native_path` support for custom ONNX Runtime native libraries
- dedicated Gradle build variant:
  - `-PuseMigraphx=true`
- patched ONNX Runtime Java binding support for `addMIGraphX()`

## Verified Setup

- OS: Kubuntu `26.04 LTS`
- Kernel: Linux `7.0.0-27-generic`
- CPU: AMD Ryzen 7 9700X
- GPU: AMD Radeon RX 7900 XTX (`gfx1100`)
- System memory: 64 GB
- ROCm: `7.2.0`
- Java: OpenJDK `21.0.11`
- Minecraft: `1.21.1`
- NeoForge: `21.1.238`
- ONNX Runtime: custom `1.23.2` build with MIGraphX Execution Provider

## Required Config

```properties
inference.device=gpu
inference.provider=migraphx
inference.native_path=/absolute/path/to/onnxruntime-migraphx-native-directory
```

## Native Runtime Notes

This release expects external ONNX Runtime native libraries built with MIGraphX enabled. The jar alone is not enough.

The native directory used during testing contained:

- `libonnxruntime.so`
- `libonnxruntime4j_jni.so`
- `libonnxruntime_providers_migraphx.so`
- `libonnxruntime_providers_shared.so`

## Operational Notes

- `World Scale 2` is the recommended baseline.
- Higher world scale values smooth terrain more aggressively and can produce very large oceans.
- This release is intended for AMD Linux use, not as an official upstream-supported distribution.

## Credit

Original mod and integration work:

- [xandergos/terrain-diffusion-mc](https://github.com/xandergos/terrain-diffusion-mc)
- [xandergos/terrain-diffusion](https://github.com/xandergos/terrain-diffusion)
