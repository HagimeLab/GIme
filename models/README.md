# GIme 字幕模型包（v1.1.8）

APP 内「设置 → 字幕模型」的下载清单。当前只支持 **X-ASR** 一套。模型二进制**不进 git 对象**
（169MB 的 onnx 会把仓库撑大，且 GitHub 单文件 push 上限 100MB），实际挂在发行版附件上。

| id | 名称 | 特点 | 字节数 | 下载源 | sha256 |
|---|---|---|---|---|---|
| `xasr` | X-ASR 中英大模型（1920ms 快模型） | MatMul int8 + onnxsim，整轨比 480ms 全 int8 快约 3.5× | 168,586,449 | Gitee `v1.1.8-models` 9 卷（首源）→ 直链 → GitHub 分块 | `ffb6733dfbf8f0dee3cab678c1e42caf33853edc61f7c180b968a05df370f7d4` |

## 下载源与分卷

Gitee 发行版单附件上限 100MB、下载不支持 Range，故整包按 **20MB × 9 卷**拆开：

```
https://gitee.com/hagemilab/gi/releases/download/v1.1.8-models/gime-asr-xasr-1920ms-int8.zip.p1
…                                                                              .p8
…                                                                              .p9   (795KB)
```

- 每卷独立下载，先写 `.part` 再改名，装完即删；中断后只补没下完的卷。
- 9 卷拼接后校验整包 sha256，再解压。
- 地址由 `AsrModels.giteeUrl()` 拼接，tag 与文件名改了要同步改那里。

## 包内文件（解压到 `files/asr/xasr/`）

- `encoder.onnx`（154,636,721，MatMul int8 + onnxsim）
- `decoder.onnx`（11,309,084，fp32 —— int8 decoder 精度损失明显）
- `joiner.onnx`（2,581,422）
- `tokens.txt`（58,806）

包内**不含** 480ms 慢模型；Rust 加载失败时没有 sherpa 回退，只报明确错误。

## 出包与发布

```bat
python tools\pack-model.py  --dir D:\desktop\加速推理框架\models_1920_int8\sim --out dist\gime-asr-xasr-1920ms-int8.zip
python tools\split-model.py --src dist\gime-asr-xasr-1920ms-int8.zip --out models\gitee-parts
python tools\upload-gitee.py --dir models\gitee-parts --tag v1.1.8-models
```

上传脚本幂等，重跑只补缺卷。发布后先匿名下第 1 卷核对 sha256，再上真机点一次「下载模型」。

## GitHub 侧

169MB 单文件不能直接 push（单文件 100MB 硬限制）。要放 GitHub 只能走发行版资产（单资产 ≤2GB）
或 LFS；如果放发行版，建议沿用同一套 `…zip.p1…p9` 文件名，App 加一条「GitHub 分卷」降级分支即可。
