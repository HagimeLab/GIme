/* 内置题库已移除：v1.0.3 起所有题库都由用户自己导入。
   这个文件保留成空壳是为了两件事：
   1. index.html 里 <script src="bank.js"> 的加载顺序不用改，启动流程照旧；
   2. 从旧版升级上来的用户，页面拿到的仍是合法的 BANK 结构，
      不会因为 window.BANK 不存在而走进任何兜底分支。 */
window.BANK = { built: "2026-10-18", subjects: [], questions: [] };
