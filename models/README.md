# GIme 字幕模型（v1.1.8 测试标记）

这里是 APP 内「设置 → 字幕模型」下载通道对应的模型清单。模型二进制**不进 git 对象**
（60MB 的 onnx 会把仓库撑大），实际文件挂在仓库的
[v1.1.8 Release 资产](https://github.com/HagimeLab/GIme/releases/tag/v1.1.8) 上，
APP 通过 GitHub API 的 release asset 端点下载（带 `Accept: application/octet-stream`，
302 到 CDN，支持 206 Range 断点续传）。

| id | 名称 | 特点 | 字节数 | 下载端点（仓库位置） | sha256 |
|---|---|---|---|---|---|
| `bilingual` | 中英双语 transducer | 60MB，句首更完整 | 60143397 | https://api.github.com/repos/HagimeLab/GIme/releases/assets/722682620 | `8151a74a3489324c04761b2c769a7f9b9ec681298b77d985b6fdb4b90db17624` |
| `ctc` | 中文 CTC 小模型 | 26MB，速度快、体积小 | 26355930 | https://api.github.com/repos/HagimeLab/GIme/releases/assets/722682414 | `699531bd5d9ad38cae3165adc45a3775ebcc8f9e6c9761e156cd3e9f82ea7e98` |

## 下载与解压

- 请求头：`Accept: application/octet-stream`（否则 API 返回的是 JSON 元数据，不是二进制）。
- 支持 `Range: bytes=<start>-`，APP 内做了断点续传。
- zip 内是平铺的模型文件，解压到 APP 私有目录 `files/asr/<id>/`：
  - `ctc`：`model.int8.onnx` + `tokens.txt`（走 `setZipformer2Ctc`）
  - `bilingual`：`encoder-epoch-99-avg-1.int8.onnx` + `decoder-epoch-99-avg-1.onnx` + `joiner-epoch-99-avg-1.int8.onnx` + `tokens.txt`（走 `setTransducer`）

## 关于本标记

- **仅用于测试**：不含安装包（APK），也没有改动 `release/latest.json`（仍为 1.1.7）。
- 本 Release 是 **prerelease**，不会被 `/releases/latest` 返回，因此不会触发 APP 内版本更新通知。
- 模型随用随下，APK 不再内置 60MB 权重。