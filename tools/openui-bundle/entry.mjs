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
    "按钮。action 取：send:<文本> 直接发送；value:<文本> 填入输入框；nav:<#/路由> 跳页；approve 采用当前草案；adjust 去调整；submit:<表单名> 提交表单；toggle:<方案id>:<日期>:<序号> 勾选今日任务；insight:<方案id> 刷新关注信息",
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
    kind: z.enum(["text", "number"]).optional(),
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
  comp("Table", "表格；columns 为表头，rows 为二维字符串数组", {
    columns: z.array(z.string()),
    rows: z.array(z.array(z.string())),
  }),
  comp("Metric", "指标卡", {
    label: z.string(),
    value: z.string(),
    hint: z.string().optional(),
  }),
  comp("Progress", "进度条，value 取 0-100", {
    label: z.string().optional(),
    value: z.number(),
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
  comp("Chart", "图表；kind 取 bar / line，labels 与 values 等长", {
    kind: z.enum(["bar", "line"]).optional(),
    labels: z.array(z.string()),
    values: z.array(z.number()),
  }),
  comp("Divider", "分隔线", {}),
  comp("Form", "表单容器；提交时收集内部 Input/Select/Toggle 的值", {
    name: z.string(),
    children: kids(),
    submitLabel: z.string().optional(),
    action: z.string().optional(),
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
