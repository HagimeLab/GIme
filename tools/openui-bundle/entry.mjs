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

const ACTION_DESC =
  "action 取：send:<文本> 直接发送；value:<文本> 填入输入框；copy:<文本> 复制到剪贴板；" +
  "toast:<文本> 弹一条即时提示（不发消息）；confirm:<标题>|<说明>|<动作> 先弹浮层确认再执行；" +
  "nav:<#/路由> 跳页；bank:<题库名> 打开题库；wrong:<题库名|全部> 打开错题本；" +
  "drill:<题库名> 开始刷该题库；redrill:<题库名|全部> 重刷错题；approve 采用当前草案；adjust 去调整；" +
  "submit:<表单名> 提交表单；toggle:<方案id>:<日期>:<序号> 勾选今日任务；insight:<方案id> 刷新关注信息";

const components = [
  comp("Stack", "纵向容器；children 为子组件数组。align 取 start/center/end（横向对齐），justify 取 start/center/end/between（只有配 padding 或高度才看得出），padding 取 sm/md", {
    children: kids(),
    gap: z.enum(["sm", "md"]).optional(),
    align: z.enum(["start", "center", "end"]).optional(),
    justify: z.enum(["start", "center", "end", "between"]).optional(),
    padding: z.enum(["sm", "md"]).optional(),
  }),
  /* Row 的参数位次与 Stack 对齐（gap/align/justify/padding）。
     真机抓到模型按 Stack 的写法给 Row 传 7 个位置参数 → 「Row takes 3 arg(s), got 7」整排被丢。
     两边位次一致，模型怎么写都不会多出来。 */
  /* Row 的位次保持 (children, align, padding)，不跟 Stack 对齐：
     真机试过对齐——历史源码里模型一直写 Row(children, "center")，
     位次一改变成 gap，就报「expects one of ["sm","md"] but got "center"」，等于把本来正常的卡改坏。
     要 gap/justify 的排布用 Stack。 */
  comp("Row", "横向容器；children 为子组件数组。align 取 start/center/end（横排里管纵向对齐），padding 取 sm/md；要 gap/justify 用 Stack", {
    children: kids(),
    align: z.enum(["start", "center", "end"]).optional(),
    padding: z.enum(["sm", "md"]).optional(),
  }),
  comp("Card", "卡片容器，可带标题", {
    title: z.string().optional(),
    children: kids(),
  }),
  comp("Title", "标题文本，level 1-3", {
    text: z.string(),
    level: z.int().min(1).max(3).optional(),
  }),
  comp("Text", "纯文本一行。要排版（加粗、列表、代码、公式）改用 Rich；md:true 与 Rich 等价", {
    text: z.string(),
    muted: z.boolean().optional(),
    md: z.boolean().optional(),
  }),
  comp("Rich", "Markdown 文本块：加粗 **…**、行内代码 `…`、无序 - / 有序 1. 列表、表格 |…|、代码块 ```、引用 >；行内公式写 $x_1$、独立公式写 $$…$$（公式里的反斜杠同样双写：$x\\\\times y$）。题干、解析、错题讲评、方案说明这类要排版的内容一律用它，别拆成一堆 Text", {
    text: z.string(),
    muted: z.boolean().optional(),
  }),
  comp("Math", "数学公式（KaTeX 离线渲染）：分数、根号、上下标、求和、矩阵、分段函数都支持。" +
    "反斜杠必须双写成 \\\\frac{1}{2}、\\\\begin{pmatrix}…\\\\end{pmatrix}、\\\\text{否则} —— " +
    "单反斜杠会被字符串解析当成转义符吃掉（\\\\t 会切成制表符、\\\\f 直接报公式错，公式缺字母或不显示）。" +
    "display:true 单独成行（超长可横向滚），throwOnError 关掉时报错会把原式子亮出来而不是白屏", {
    text: z.string(),
    display: z.boolean().optional(),
  }),
  comp(
    "Button",
    "按钮。**必须给 action**：没写 action 的按钮点了不会有任何反应，会被降级成纯文字（等于白画）。" +
      ACTION_DESC,
    {
      label: z.string(),
      action: z.string().optional(),
      variant: z.enum(["primary", "secondary"]).optional(),
      disabled: z.boolean().optional(),
      loading: z.boolean().optional(),
      icon: z.string().optional(),
    }
  ),
  comp("Input", "单行输入框，name 为表单字段名；kind 取 text / number / date / datetime（系统日期时间选择器）/ multiline。要一组互斥选项用 RadioGroup，别做「密码」输入框（会话记录是明文落盘的）", {
    name: z.string(),
    label: z.string().optional(),
    placeholder: z.string().optional(),
    kind: z.enum(["text", "number", "date", "datetime", "multiline"]).optional(),
    value: z.string().optional(),
  }),
  comp("Select", "下拉选择，options 为选项文本数组", {
    name: z.string(),
    label: z.string().optional(),
    options: z.array(z.string()),
  }),
  comp("Toggle", "单个勾选框（一个独立的是/否，如「记住这个设置」）。一组互斥选项用 RadioGroup，一组可并选的选项用 CheckboxGroup，别堆一排 Toggle", {
    name: z.string(),
    label: z.string(),
    checked: z.boolean().optional(),
  }),
  comp("RadioGroup", "单选组：整组互斥，选一项写入表单（name → 该项文字）。A/B/C 选择题、题型筛选就用它，不要拿一排 Button 硬拼选项", {
    name: z.string(),
    label: z.string().optional(),
    options: z.array(z.string()),
    value: z.string().optional(),
  }),
  comp("CheckboxGroup", "多选组：每项独立勾选，勾中的拼成「A、B」写入表单（name → 选中项）。错题知识点标签、要复习的几章这类可并存的选择用它", {
    name: z.string(),
    label: z.string().optional(),
    options: z.array(z.string()),
    values: z.array(z.string()).optional(),
  }),
  comp("Table", "表格；columns 为表头，rows 为二维字符串数组。单元格以 ! 开头=警示、+ 开头=强调、~ 开头=次要；行比列多时多余的列丢弃、比列少时补空，不会错位。rows 超过 pageSize 时自动分页。rowAction 让整行可点，{1}..{n} 会替换成被点那一行各列的文字（如 wrong:{1}）。sortable 让表头可点排序（升一次、降一次）；widths 按列写 CSS 宽度，如 [\"40%\",\"auto\",\"72px\"]", {
    columns: z.array(z.string()),
    rows: z.array(z.array(z.string())),
    pageSize: z.int().min(1).optional(),
    rowAction: z.string().optional(),
    sortable: z.boolean().optional(),
    widths: z.array(z.string()).optional(),
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
  comp("Chart", "图表；kind 取 bar / line / pie（占比）/ stack（堆叠）。labels 与 values 等长；stack 时 values 传二维（每行一个类别的各系列数值，行短了按 0 补），names 为系列名（缺的显示为「系列 N」）。action 让柱/扇区/图例可点，{label} 替换成被点那一项的标签（如 wrong:{label}）。四种 kind 都吃 action：bar/stack 点数值、柱体或标签，pie 点图例行，line 点 x 标签或标在标签上的数值。unit 给数值加单位（如「题」「%」「分钟」，柱顶和图例都会带上）；legend 取 top/bottom 调图例位置（触屏没有悬停 tooltip，数值直接标在项上）。" +
    "位次是 kind, labels, values, names, action, unit, legend：只想给数值加单位就写 Chart(\"bar\", 标签, 数值, [], \"\", \"题\")，不要改用具名写法（具名会被解析器多计一个参数）；第 4 位误写成字符串（如 Chart(\"bar\", 标签, 数值, \"题\")）也按单位收", {
    kind: z.enum(["bar", "line", "pie", "stack"]).optional(),
    labels: z.array(z.string()),
    values: z.union([z.array(z.number()), z.array(z.array(z.number()))]),
    /* names 允许字符串：真机抓到模型把单位写在第 4 位（Chart("bar", 标签, 数值, "题")），
       zod 报「field "/names" expects array but got string」，整条 names 被丢、单位也没了。
       字符串永远不可能是系列名数组，所以在渲染层按单位收（见 openui-ui.js 的 Chart 分支）。 */
    names: z.union([z.array(z.string()), z.string()]).optional(),
    action: z.string().optional(),
    unit: z.string().optional(),
    legend: z.enum(["top", "bottom"]).optional(),
  }),
  comp("Divider", "分隔线", {}),
  comp("Image", "图片；src 接受 http(s)、data:image，或写成 svg:<svg .../> 直接画示意图。SVG 里可用 SMIL / CSS 做动画（<animate>、@keyframes），但不支持脚本与外链。" +
    "SVG 的颜色只用灰阶（fill/stroke 取 #111113、#62676F、#A2A7AF、#dcdce1、#EDEEF1、#ffffff 这一档，深浅靠明度拉开），" +
    "不要写蓝/橙/红这类彩色，也不要给整块底色铺深蓝——这个 App 是黑白界面，彩色示意图会很扎眼", {
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
  comp("Details", "可折叠区块；summary 是收起时显示的一行，展开才看 children。可嵌套当树用（章节 → 小节 → 题目），最多 3 层，再深缩进会挤。要横向分段切换（题干 / 解析 / 我的笔记）用 Tab，别把 Details 一层层套下去", {
    summary: z.string(),
    children: kids(),
    open: z.boolean().optional(),
  }),
  comp("Tab", "横向分段控件：labels 是各段标题，panels 与之一一对齐的子组件数组；点标题只换内容区，不跳页、不滚长页。多选题库、三份方案对比、题干/解析分栏用它", {
    labels: z.array(z.string()),
    panels: z.array(kids()),
  }),
  comp("Empty", "空状态：这一栏确实没有内容时用它，别只留一行灰字。text 是一句话（如「这个题库还没有错题」），hint 补一句下一步做什么，action 让整块可点（如 send:帮我从错题里挑 10 题）", {
    text: z.string(),
    hint: z.string().optional(),
    action: z.string().optional(),
  }),
  comp("Error", "错误态：工具失败、导入没读全、上游超时都画成这个块，别把异常写进正文当没事。retry 给一个 action 串（如 send:再查一遍），点了照那个动作走", {
    text: z.string(),
    hint: z.string().optional(),
    retry: z.string().optional(),
  }),
  comp("Slider", "滑块，拖动取值并写入表单；min/max 为数字，step 可选", {
    name: z.string(),
    label: z.string().optional(),
    min: z.number(),
    max: z.number(),
    step: z.number().optional(),
    value: z.number().optional(),
  }),
  comp("Form", "表单容器；提交时收集内部 Input/Select/Toggle/RadioGroup/CheckboxGroup/Slider 的值", {
    name: z.string(),
    children: kids(),
    submitLabel: z.string().optional(),
    action: z.string().optional(),
  }),
  comp("Tap", "可点区域：children 整块被点后执行 action。" + ACTION_DESC, {
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
