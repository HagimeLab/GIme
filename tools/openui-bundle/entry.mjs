import {
  createLibrary,
  defineComponent,
  createParser,
  createStreamingParser,
} from "@openuidev/lang-core";
import { z } from "zod";

const kids = () => z.array(z.any());
const comp = (name, description, props) =>
  defineComponent({ name, description, props: z.object(props), component: null });

const components = [
  comp("Stack", "纵向容器；children 为子组件数组", {
    children: kids(),
    gap: z.enum(["sm", "md"]).optional(),
  }),
  comp("Row", "横向容器；children 为子组件数组", { children: kids() }),
  comp("Card", "卡片容器，可带标题", {
    title: z.string().optional(),
    children: kids(),
  }),
  comp("Title", "标题文本，level 1-3", {
    text: z.string(),
    level: z.int().min(1).max(3).optional(),
  }),
  comp("Text", "普通文本", {
    text: z.string(),
    muted: z.boolean().optional(),
  }),
  comp(
    "Button",
    "按钮。action 取：send:<文本> 直接发送；value:<文本> 填入输入框；copy:<文本> 复制到剪贴板；nav:<#/路由> 跳页；bank:<题库名> 打开题库；wrong:<题库名|全部> 打开错题本；drill:<题库名> 开始刷该题库；redrill:<题库名|全部> 重刷错题；approve 采用当前草案；adjust 去调整；submit:<表单名> 提交表单；toggle:<方案id>:<日期>:<序号> 勾选今日任务；insight:<方案id> 刷新关注信息",
    {
      label: z.string(),
      action: z.string().optional(),
      variant: z.enum(["primary", "secondary"]).optional(),
    }
  ),
  comp("Input", "单行输入框，name 为表单字段名", {
    name: z.string(),
    label: z.string().optional(),
    placeholder: z.string().optional(),
    kind: z.enum(["text", "number", "date", "multiline"]).optional(),
    value: z.string().optional(),
  }),
  comp("Select", "下拉选择，options 为选项文本数组", {
    name: z.string(),
    label: z.string().optional(),
    options: z.array(z.string()),
  }),
  comp("Toggle", "勾选框", {
    name: z.string(),
    label: z.string(),
    checked: z.boolean().optional(),
  }),
  comp("Table", "表格；columns 为表头，rows 为二维字符串数组。单元格以 ! 开头=警示、+ 开头=强调、~ 开头=次要；行比列多时多余的列丢弃、比列少时补空，不会错位。rows 超过 pageSize 时自动分页。rowAction 让整行可点，{1}..{n} 会替换成被点那一行各列的文字（如 wrong:{1}）", {
    columns: z.array(z.string()),
    rows: z.array(z.array(z.string())),
    pageSize: z.int().min(1).optional(),
    rowAction: z.string().optional(),
  }),
  comp("Metric", "指标卡；delta 是涨跌标注，如 +12% / -3 题，正数显绿、负数显红", {
    label: z.string(),
    value: z.string(),
    hint: z.string().optional(),
    delta: z.string().optional(),
  }),
  comp("Progress", "进度条，value 取 0-100；parts 为分段进度，每项写「名称:数值」", {
    label: z.string().optional(),
    value: z.number(),
    parts: z.array(z.string()).optional(),
  }),
  comp("Badge", "小标签", { text: z.string() }),
  comp("List", "列表；items 为条目文本数组", {
    items: z.array(z.string()),
    ordered: z.boolean().optional(),
  }),
  comp("Steps", "编号步骤列表；items 为步骤文本数组", {
    items: z.array(z.string()),
  }),
  comp("Callout", "提示块；tone 取 info / warn / success", {
    title: z.string().optional(),
    text: z.string(),
    tone: z.enum(["info", "warn", "success"]).optional(),
  }),
  comp("Chart", "图表；kind 取 bar / line / pie（占比）/ stack（堆叠）。labels 与 values 等长；stack 时 values 传二维（每行一个类别的各系列数值，行短了按 0 补），names 为系列名（缺的显示为「系列 N」）。action 让柱/扇区/图例可点，{label} 替换成被点那一项的标签（如 wrong:{label}）", {
    kind: z.enum(["bar", "line", "pie", "stack"]).optional(),
    labels: z.array(z.string()),
    values: z.union([z.array(z.number()), z.array(z.array(z.number()))]),
    names: z.array(z.string()).optional(),
    action: z.string().optional(),
  }),
  comp("Divider", "分隔线", {}),
  comp("Image", "图片；src 接受 http(s)、data:image，或写成 svg:<svg .../> 直接画示意图。SVG 里可用 SMIL / CSS 做动画（<animate>、@keyframes），但不支持脚本与外链", {
    src: z.string(),
    alt: z.string().optional(),
    caption: z.string().optional(),
  }),
  comp("Link", "站外链接；点了用系统浏览器打开，不会把 App 页面带走", {
    text: z.string(),
    href: z.string(),
  }),
  comp("Skeleton", "骨架占位块，表示这块内容还在生成；rows 取 1-6", {
    rows: z.int().min(1).max(6).optional(),
  }),
  comp("Code", "等宽代码块，带一键复制；lang 只是右上角角标（python / sql / js…），title 可选", {
    text: z.string(),
    lang: z.string().optional(),
    title: z.string().optional(),
  }),
  comp("Flow", "纵向流程/时序链：每项一行，自动带编号与箭头连线，用来表达先后顺序", {
    items: z.array(z.string()),
    kind: z.enum(["arrow", "check"]).optional(),
  }),
  comp("Grid", "网格容器；cols 取 2-4（默认 2），children 为子组件数组", {
    cols: z.int().min(2).max(4).optional(),
    children: kids(),
  }),
  comp("Details", "可折叠区块；summary 是收起时显示的一行，展开才看 children（建议最多嵌套 2 层，再深会挤）", {
    summary: z.string(),
    children: kids(),
    open: z.boolean().optional(),
  }),
  comp("Slider", "滑块，拖动取值并写入表单；min/max 为数字，step 可选", {
    name: z.string(),
    label: z.string().optional(),
    min: z.number(),
    max: z.number(),
    step: z.number().optional(),
    value: z.number().optional(),
  }),
  comp("Form", "表单容器；提交时收集内部 Input/Select/Toggle 的值", {
    name: z.string(),
    children: kids(),
    submitLabel: z.string().optional(),
    action: z.string().optional(),
  }),
  comp("Tap", "可点区域：children 整块被点后执行 action。action 取：bank:<题库名> 打开题库并定位；wrong:<题库名|全部> 打开错题本；drill:<题库名> 直接开始刷该题库；redrill:<题库名|全部> 重刷错题；nav:<home|bank|wrong|me> 切页；send:<文本> 让大肥鱼接着说", {
    children: kids(),
    action: z.string(),
  }),
];

const library = createLibrary({ id: "goal-assistant", root: "Stack", components });
const schema = library.toJSONSchema();
const parser = createParser(schema, "Stack");
const spec = library.toSpec();
const signatures = Object.keys(spec.components).map((name) => ({
  name,
  signature: spec.components[name].signature,
  description: spec.components[name].description || "",
}));

let promptCache = "";
function prompt() {
  if (!promptCache) promptCache = library.prompt({ inlineMode: true });
  return promptCache;
}

function parse(source) {
  return parser.parse(String(source == null ? "" : source));
}

function stream() {
  return createStreamingParser(schema, "Stack");
}

globalThis.OpenUI = { parse, stream, prompt, schema, signatures };
