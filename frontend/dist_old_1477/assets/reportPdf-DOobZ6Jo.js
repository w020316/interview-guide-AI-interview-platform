import{i as e}from"./ReportPanel-ew0w1BnA.js";function t(e){return e==null?``:e>=85?`优秀`:e>=70?`良好`:e>=60?`合格`:`待加强`}function n(e){return e==null?`#9ca3af`:e>=85?`#10b981`:e>=70?`#3b82f6`:e>=60?`#f59e0b`:`#ef4444`}function r(e){return String(e??``).replace(/&/g,`&amp;`).replace(/</g,`&lt;`).replace(/>/g,`&gt;`).replace(/"/g,`&quot;`)}function i(i){let a=!e({completeness:i.completeness,accuracy:i.accuracy,expression:i.expression}),o=n(i.overall),s=i.overall===null?`—`:String(Math.round(i.overall)),c=t(i.overall),l=i.overall===null?``:`<span class="tag" style="color:#fff;background:${o}">${c}</span>`,u=[];if(!a){i.overall!==null&&u.push({name:`综合`,v:i.overall,hex:n(i.overall)});let e=[{name:`完整性`,v:i.completeness},{name:`准确性`,v:i.accuracy},{name:`表达力`,v:i.expression}];for(let t of e)t.v!==null&&u.push({name:t.name,v:t.v,hex:n(t.v)})}let d=a?`<p class="degraded" data-report-degraded-note="true">本场为早期会话，未保存分维度明细，故不展示完整性 / 准确性 / 表达力拆解（旧数据不补 0）。</p>`:`<table class="dims">
    ${u.map(e=>`<tr>
      <td class="dim-name">${e.name}</td>
      <td><div class="dim-bar"><div class="dim-fill" style="width:${Math.max(2,Math.min(100,Math.round(e.v)))}%;background:${e.hex}"></div></div></td>
      <td class="dim-val" style="color:${e.hex}">${Math.round(e.v)}</td>
    </tr>`).join(``)}
  </table>`,f=Array.isArray(i.improvements)&&i.improvements.length?i.improvements.map(e=>`<li>${r(e)}</li>`).join(``):`<p style="margin:0;color:#6b7280">暂无高频改进点，继续保持！</p>`,p=Array.isArray(i.questions)&&i.questions.length?i.questions.map((e,t)=>{let i=e.overallScore===null?`—`:`${Math.round(e.overallScore)} 分`;return`<li class="q-item">
        <div class="q-head"><span class="q-index">${t+1}</span><span class="q-cat">${r(e.category)}</span><span class="q-score" style="color:${n(e.overallScore)}">${i}</span></div>
        <div class="q-text">${r(e.question)}</div>
      </li>`}).join(``):`<li style="color:#6b7280">无逐题记录</li>`;return`<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>模拟面试复盘报告</title>
<style>
  * { box-sizing: border-box; }
  body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif; color: #1f2937; margin: 0; padding: 32px 28px; line-height: 1.6; background: #fff; }
  .head { display: flex; justify-content: space-between; align-items: flex-end; border-bottom: 3px solid #10b981; padding-bottom: 14px; margin-bottom: 22px; }
  .head h1 { margin: 0; font-size: 22px; }
  .head .sub { font-size: 12px; color: #6b7280; margin-top: 4px; }
  .overall { text-align: center; padding: 22px 0 26px; }
  .overall .num { font-size: 58px; font-weight: 700; line-height: 1; }
  .overall .unit { font-size: 16px; font-weight: 400; margin-left: 2px; color: #6b7280; }
  .overall .tag { display: inline-block; margin-top: 10px; padding: 2px 12px; border-radius: 999px; font-size: 13px; }
  table.dims { width: 100%; border-collapse: collapse; margin-bottom: 22px; }
  table.dims td { padding: 7px 4px; font-size: 13px; }
  .dim-name { color: #6b7280; width: 84px; }
  .dim-bar { height: 10px; background: #f3f4f6; border-radius: 999px; overflow: hidden; }
  .dim-fill { height: 100%; border-radius: 999px; }
  .dim-val { font-weight: 600; text-align: right; width: 48px; }
  .degraded { font-size: 13px; color: #6b7280; background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 10px; padding: 12px 14px; margin: 0 0 22px; }
  h2 { font-size: 16px; margin: 24px 0 12px; border-left: 4px solid #10b981; padding-left: 10px; }
  .summary { background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 10px; padding: 14px 16px; font-size: 13px; color: #14532d; }
  ul.improve { margin: 0; padding-left: 18px; font-size: 13px; }
  ul.improve li { margin-bottom: 5px; }
  ol.qlist { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 10px; }
  .q-item { border: 1px solid #e5e7eb; border-radius: 10px; padding: 10px 12px; }
  .q-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
  .q-index { width: 20px; height: 20px; border-radius: 999px; background: #10b981; color: #fff; font-size: 12px; display: inline-flex; align-items: center; justify-content: center; }
  .q-cat { font-size: 12px; color: #374151; background: #f3f4f6; border-radius: 6px; padding: 1px 8px; }
  .q-score { font-size: 12px; font-weight: 600; margin-left: auto; }
  .q-text { font-size: 13px; color: #1f2937; }
  .foot { margin-top: 28px; font-size: 11px; color: #9ca3af; text-align: center; }
  @media print { body { padding: 0; } }
</style>
</head>
<body>
  <div class="head">
    <div>
      <h1>模拟面试复盘报告</h1>
      <div class="sub">目标岗位：${r(i.jobTitle)} ｜ 作答 ${i.answeredCount===null?`—`:i.answeredCount} 题 ｜ ${r(i.exportedAt||new Date().toLocaleString())}</div>
    </div>
  </div>

  <div class="overall">
    <div class="num" style="color:${o}">${s}${i.overall===null?``:`<span class="unit">分</span>`}</div>
    ${l}
  </div>

  ${d}

  <h2>综合评价</h2>
  <div class="summary">${r(i.summary||``)}</div>

  <h2>建议提升的要点</h2>
  <ul class="improve">${f}</ul>

  <h2>逐题得分</h2>
  <ol class="qlist">${p}</ol>

  <div class="foot">由 AI 智能面试辅助平台生成</div>
</body>
</html>`}function a(e){let t=i(e),n=window.open(``,`_blank`,`width=800,height=900`);if(!n){o(t);return}n.document.open(),n.document.write(t),n.document.close(),setTimeout(()=>{n.focus(),n.print()},350)}function o(e){let t=document.createElement(`iframe`);t.style.position=`fixed`,t.style.right=`0`,t.style.bottom=`0`,t.style.width=`0`,t.style.height=`0`,t.style.border=`0`,t.setAttribute(`aria-hidden`,`true`),document.body.appendChild(t);let n=t.contentDocument;if(!n){t.remove();return}n.open(),n.write(e),n.close(),setTimeout(()=>{t.contentWindow?.focus(),t.contentWindow?.print(),setTimeout(()=>t.remove(),1e3)},350)}export{a as exportReportToPdf};