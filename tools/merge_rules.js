#!/usr/bin/env node
/**
 * 规则合并脚本（维护用）：把社区 A 层规则（AllRules.json）与本项目的 B 层
 * 「老人专属规则」（b_layer.json）合并，生成发布用的 assets/AllRules.json。
 *
 * 用法：node tools/merge_rules.js
 *
 * 合并规则：
 *  - 以「包名 hashCode」为 key；
 *  - key 冲突时 B 层优先（本项目手写规则覆盖社区同名规则）；
 *  - 输出为数组格式：[{"<hash>":"<ruleJson>"}, ...]；
 *  - 同时校验每条规则可被 JSON 解析，统计覆盖 App 数。
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const A_FILE = path.join(ROOT, 'app', 'src', 'main', 'assets', 'AllRules.json');
const B_FILE = path.join(ROOT, 'tools', 'b_layer.json');
const OUT = A_FILE;

/** 与 Java String.hashCode() 完全一致（含负数溢出）。 */
function hashCode(str) {
  let h = 0;
  for (let i = 0; i < str.length; i++) {
    h = (Math.imul(h, 31) + str.charCodeAt(i)) | 0;
  }
  return h;
}

function loadJson(p) {
  return JSON.parse(fs.readFileSync(p, 'utf8'));
}

// 1) 加载 A 层（剔除无法解析的损坏条目，保证发布文件干净）
const aArr = loadJson(A_FILE);
const map = new Map(); // key=hash字符串 -> rule字符串
let aDropped = 0;
for (const o of aArr) {
  const k = Object.keys(o)[0];
  const v = o[k];
  try {
    JSON.parse(v);
    map.set(k, v);
  } catch (e) {
    aDropped++;
  }
}
const aCount = map.size;

// 2) 加载 B 层并合并
const bArr = loadJson(B_FILE);
let bAdded = 0, bOverridden = 0;
for (const item of bArr) {
  const { pkg, rule } = item;
  const key = String(hashCode(pkg));
  const ruleStr = JSON.stringify(rule);
  if (map.has(key)) {
    bOverridden++;
  } else {
    bAdded++;
  }
  map.set(key, ruleStr);
}

// 3) 输出数组
const out = [];
for (const [k, v] of map.entries()) {
  const o = {};
  o[k] = v;
  out.push(o);
}

// 校验每条可解析
let parseFail = 0;
for (const o of out) {
  const v = Object.values(o)[0];
  try { JSON.parse(v); } catch (e) { parseFail++; }
}

fs.writeFileSync(OUT, JSON.stringify(out));
console.log('合并完成：');
console.log('  A 层（社区）有效:', aCount, '条，剔除损坏:', aDropped);
console.log('  B 层（老人专属）新增:', bAdded, ' 覆盖既有:', bOverridden);
console.log('  合并后共:', out.length, '条，全部可解析');
console.log('  已写出:', OUT);
