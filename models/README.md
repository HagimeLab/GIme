# GIme 字幕模型（v1.1.8 测试标记）

这里是 APP 内「我的 → 字幕模型」下载通道对应的模型清单。模型二进制**不进 git 对象**，
实际文件挂在仓库的 [v1.1.8 Release 资产](https://github.com/HagimeLab/GIme/releases/tag/v1.1.8) 上，
APP 通过 GitHub API 的 release asset 端点下载（带 `Accept: application/octet-stream`，
302 到 CDN，支持 206 Range 断点续传）。Gitee 只作 APK 国内中转，模型一律走 GitHub 分块续传
（`AsrModels.USE_GITEE_MODELS=false`，实测 Gitee 附件 >15MB 会随机断流且不支持 Range）。

| id | 名称 | 特点 | 字节数 | 下载端点（仓库位置） | sha256 |
|---|---|---|---|---|---|
| `ctc` | 中文 CTC 小模型 | 26MB，速度快、体积小 | 26355930 | https://api.github.com/repos/HagimeLab/GIme/releases/assets/722682414 | `699531bd5d9ad38cae3165adc45a3775ebcc8f9e6c9761e156cd3e9f82ea7e98` |
| `bilingual` | 中英双语 transducer | 60MB，句首更完整 | 60143397 | https://api.github.com/repos/HagimeLab/GIme/releases/assets/722682620 | `8151a74a3489324c04761b2c769a7f9b9ec681298b77d985b6fdb4b90db17624` |
| `xasr` | X-ASR 中英大模型（480ms） | 160MB，流式带标点 | 167043993 | https://api.github.com/repos/HagimeLab/GIme/releases/assets/723187192 | `4d7e22bc534175fe8f2723901e11ed7a9dd6af2afe9c1cb6b8389be538220935` |
| `sensevoice` | SenseVoice 多语大模型 | 230MB，中英日韩粤 + 标点，整句最准、非流式 | 239762935 | https://api.github.com/repos/HagimeLab/GIme/releases/assets/723574193 | `d6922fbb8b11250bfb02de9258972c8f74fedc3fc5dd9d38326899e60e6996b6` |

## 下载与解压

- 请求头：`Accept: application/octet-stream`（否则 API 返回 JSON 元数据，不是二进制）。
- 支持 `Range: bytes=<start>-`，APP 内 4MB 分块 + 断点续传，合并后校验长度与 sha256。
- zip 内是平铺的模型文件，解压到 APP 私有目录 `files/asr/<id>/`：
  - `ctc`：`model.int8.onnx` + `tokens.txt`（走 `setZipformer2Ctc`）
  - `bilingual` / `xasr`：encoder/decoder/joiner 三件套 + `tokens.txt`（走 `setTransducer`）
  - `sensevoice`：`model.int8.onnx` + `tokens.txt` + `silero_vad.int8.onnx`
    （走 `setSenseVoice` + Silero VAD 切句，整句上屏）

## 关于本标记

- **仅用于测试**：不含安装包（APK），`release/latest.json` 仍为 1.1.7。
- 本 Release 是 **prerelease**，不会被 `/releases/latest` 返回，不会触发 APP 内版本更新通知。
- 模型随用随下，APK 不内置任何权重；切换模型会释放旧识别器并重新加载。