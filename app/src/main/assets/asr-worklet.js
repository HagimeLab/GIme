/* 实时字幕音频采集：跑在音频线程（AudioWorklet），不受主线程卡顿影响，
   不会像 ScriptProcessor 那样被跳过回调而导致音频丢帧、字幕越来越滞后。
   输出与 ScriptProcessor 路径完全一致的 16k/16bit 小端 PCM。 */
class AsrCaptureProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    var r = options && options.processorOptions ? options.processorOptions.ratio : 3;
    this.ratio = (r && r > 0) ? r : 3;
    /* 攒块再发：process() 每 128 帧（约 2.6ms）就被调一次，若每次都 postMessage
       到主线程再走 Android.asrPcm，一秒 375 次 JNI 调用会把识别拖垮。
       攒到约 4096 个 16k 样本（≈0.26 秒）再一次性转移，调用频率降到约 4 次/秒。 */
    this.chunks = [];
    this.pending = 0;
    this.TARGET = 2048;   /* 攒满 2048 样本（@16k ≈ 0.13s）就送一次：比 4096 少 ~0.13s 采集延迟 */
    /* 降采样余数缓存：process() 每次给 128 帧，128/ratio 常除不尽，余数必须留到下一次拼接。
       否则每帧丢 (128 % ratio) 个样本（48k→16k 时每次丢 2/128 = 1.56%），
       六十秒累积就丢近 1 秒音频 —— 这才是「字幕越播越慢」的真因。 */
    this.carry = null;
    this.carryLen = 0;
    /* 诊断计数：每秒上报一次输入/输出样本数与 process 调用次数，用于量化丢帧发生在哪一环 */
    this.statIn = 0; this.statOut = 0; this.statFrames = 0; this.statT = -1;
  }
  process(inputs) {
    var input = inputs[0];
    if (!input || input.length === 0) return true;
    var ch = input[0];
    if (!ch || !ch.length) return true;
    var ratio = this.ratio;
    this.statIn += ch.length; this.statFrames++;
    /* 把上一帧剩下的余数拼到本帧前面，保证重采样一个样本都不丢 */
    var src;
    if (this.carryLen > 0 && this.carry) {
      src = new Float32Array(this.carryLen + ch.length);
      src.set(this.carry, 0);
      src.set(ch, this.carryLen);
    } else {
      src = ch;
    }
    var n = Math.floor(src.length / ratio);
    var used = n * ratio;
    this.carryLen = src.length - used;
    this.carry = this.carryLen > 0 ? src.slice(used) : null;
    if (n <= 0) return true;
    var out = new Int16Array(n);
    var peak = 0;
    for (var i = 0; i < n; i++) {
      var a = (i * ratio) | 0;
      var b = Math.min(src.length, ((i + 1) * ratio) | 0);
      var s = 0, c = 0;
      for (var j = a; j < b; j++) { s += src[j]; c++; }
      var val = c ? s / c : 0;
      if (val > 1) val = 1; else if (val < -1) val = -1;
      var av = val < 0 ? -val : val; if (av > peak) peak = av;
      out[i] = val < 0 ? val * 0x8000 : val * 0x7FFF;
    }
    /* 静音段送等长零样本：时间轴继续推进，识别端断句才正常 */
    if (peak <= 0.008) { for (var z = 0; z < n; z++) out[z] = 0; }
    this.chunks.push(out);
    this.pending += n;
    if (this.pending < this.TARGET) return true;
    var merged = new Int16Array(this.pending);
    var off = 0;
    for (var k = 0; k < this.chunks.length; k++) { merged.set(this.chunks[k], off); off += this.chunks[k].length; }
    this.chunks = [];
    this.pending = 0;
    this.statOut += merged.length;
    this.port.postMessage(merged, [merged.buffer]);
    /* 每秒上报一次统计（不会阻塞主路径，只是多一条小消息） */
    var ct = (typeof currentTime === "number") ? currentTime : 0;
    if (ct - this.statT >= 1) {
      this.statT = ct;
      this.port.postMessage({ stat: { in: this.statIn, out: this.statOut, frames: this.statFrames } });
    }
    return true;
  }
}
registerProcessor('asr-capture', AsrCaptureProcessor);
