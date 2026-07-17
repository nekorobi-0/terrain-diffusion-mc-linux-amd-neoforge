# Terrain Diffusion MC NeoForge ROCm版: インストール手順

この版は Linux の AMD GPU で Terrain Diffusion MC を動かすための
NeoForge 1.21.1 向けビルドです。推論は次の経路で実行されます。

```text
Minecraft -> ONNX Runtime -> MIGraphX Execution Provider -> ROCm -> AMD GPU
```

CPU版のONNX Runtimeではありません。MIGraphXを有効にしたONNX Runtime
ネイティブライブラリとROCmが必要です。

## 必要環境

- Linux
- AMD GPUと、GPUをサポートするROCm/MIGraphX
- Java 21
- Minecraft Java Edition 1.21.1
- NeoForge 1.21.1
- `terrain-diffusion-mc-*-linux-amd+1.21.1-all.jar`
- MIGraphX対応でビルドしたONNX Runtimeネイティブライブラリ

動作確認はRX 7900 XTX/GFX1100とROCm 7.2で行っています。必要なVRAMは
おおむね2GB以上、Minecraftへの割り当てメモリは12GBを目安にしてください。

## 1. ROCmがGPUを認識することを確認する

ターミナルで次を実行します。

```bash
rocminfo | rg 'Name:.*gfx'
```

AMDのディスクリートGPUが`gfx1100`などとして表示されれば、ROCmから認識されています。
表示されない場合は、先にOS/ROCm側のセットアップを完了してください。

## 2. NeoForgeとmod jarを配置する

1. Minecraft 1.21.1用のNeoForgeプロファイルを作成します。
2. `terrain-diffusion-mc-*-linux-amd+1.21.1-all.jar` を対象プロファイルの`mods/`へ置きます。
3. Fabric版jarやCPU版jarが同じ`mods/`にある場合は削除します。

Modrinth Appでは、通常の配置先は次の形式です。

```text
~/.local/share/ModrinthApp/profiles/<プロファイル名>/mods/
```

## 3. MIGraphX対応ONNX Runtimeを用意する

このjarだけではAMD GPU推論を実行できません。以下を含むディレクトリを用意します。

```text
libonnxruntime.so
libonnxruntime4j_jni.so
libonnxruntime_providers_migraphx.so
libonnxruntime_providers_shared.so
```

ライブラリは、このリポジトリで使ったMIGraphX対応のONNX Runtime Java/nativeビルドと
同じABIである必要があります。標準のMaven版ONNX Runtimeだけではこの構成になりません。

## 4. mod設定を作成して編集する

一度Minecraftを起動して終了すると、`config/terrain-diffusion-mc.properties`が作成されます。
次の設定を入れてください。`inference.native_path`は手順3で用意した絶対パスです。

```properties
inference.device=gpu
inference.provider=migraphx
inference.native_path=/absolute/path/to/onnxruntime-migraphx

# RX 7000シリーズでは有効化を推奨
inference.migraphx_fp16=true

# 生成中にセッションを再作成しない。約2.5GB以上の空きVRAMを推奨
inference.offload_models=false

# ONNX RuntimeのCPU補助スレッドによるCPU占有を防ぐ
inference.onnx_intra_op_threads=1
inference.onnx_inter_op_threads=1

# MIGraphXのコンパイル済みプログラムを永続化する場所
inference.migraphx_model_cache_path=/absolute/path/to/terrain-diffusion-models/migraphx-cache

# 品質を維持する
generation.performance_mode=false
```

`inference.migraphx_model_cache_path`には、Minecraftプロファイル内で書き込み可能な
空ディレクトリを指定してください。未指定時はモデルディレクトリ配下が使われます。

## 5. 初回起動とGPUキャッシュ作成

Minecraftを完全に終了してから起動します。初回のワールド生成時にはMIGraphXが各モデルを
GPU向けにコンパイルします。特にbaseモデルは数分かかることがあります。完了後は手順4の
キャッシュディレクトリに3個の`.mxr`ファイルが作成されます。

この初回コンパイルは一度だけです。キャッシュを削除した場合、ONNX RuntimeやROCmを更新した場合、
またはGPUを変更した場合は再コンパイルされます。

## 6. 動作確認

`logs/latest.log`で次のような行を確認します。

```text
Terrain diffusion inference: GPU (MIGraphX)
ONNX model 'base' loaded on GPU
```

`CPU`や`No configured GPU provider is available`と出た場合は、GPU推論になっていません。
手順3のライブラリと`inference.native_path`を確認してください。

固定形状のMIGraphXキャッシュ作成後、256ブロック生成の確認値は初回約2.6秒、
キャッシュが温まった後は約0.5秒です。これはフル品質
`generation.performance_mode=false`での測定値です。

## よくある問題

### GPU利用率が低く、CPUが100%になる

MIGraphXが有効になっておらずCPU Execution Providerで推論している状態です。
`logs/latest.log`で`GPU (MIGraphX)`を確認し、ネイティブライブラリのパスと
`libonnxruntime_providers_migraphx.so`の存在を確認してください。

### 起動時または最初の生成だけ非常に遅い

MIGraphXの初回コンパイルです。キャッシュディレクトリに3個の`.mxr`が生成されるまで待ちます。
毎回再コンパイルされる場合は、`inference.migraphx_model_cache_path`が書き込み可能であることを
確認してください。

### `Registry is already frozen`で落ちる

Fabric版jarとNeoForge版jarの混在、または古いjarが残っている可能性があります。
`mods/`にはこのNeoForge版jarだけを残してください。

### VRAM不足になる

`inference.offload_models=true`にするとモデルを段階ごとにVRAMから退避します。
速度は下がりますが必要VRAMを抑えられます。

## ビルド

MIGraphX対応ONNX Runtime Java artifactを`mavenLocal()`に用意した上で実行します。

```bash
JAVA_HOME=/path/to/java-21 ./gradlew build -PuseMigraphx=true --no-daemon
```

生成先:

```text
build/libs/terrain-diffusion-mc-*-linux-amd+1.21.1-all.jar
```
