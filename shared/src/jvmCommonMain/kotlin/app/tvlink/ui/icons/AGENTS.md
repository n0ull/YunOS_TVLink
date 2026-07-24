<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-24 | Updated: 2026-07-24 -->

# icons

## Purpose

本地内嵌的 Material Symbols (Rounded) 矢量图标(viewport 960)。官方 material-icons /
material-icons-extended 制品已停维护(旧样式、显著拖慢构建),按官方建议改为本地内嵌,
**严禁重新引入该依赖**。

## Key Files

| File | Description |
|------|-------------|
| `AppIcons.kt` | `object AppIcons` — 25 枚图标;私有 `materialIcon()` 构建器 + 每枚 `by lazy` 缓存 getter |

## For AI Agents

### Working In This Directory

- 新增图标:从 `github.com/google/material-design-icons` 仓库
  `symbols/android/<name>/materialsymbolsrounded/<name>_24px.xml` 抄 pathData,加一个 `by lazy` 属性;
  需 RTL 镜像的加 `autoMirror = true`
- 三个坑:① `ImageVector.Builder` 用 `addPath(pathData, fill = …)`(`path(pathData=…)` 重载已不存在);
  ② 图标做成 `object` 成员而非扩展属性(扩展属性每枚都要单独 import);
  ③ 缓存用 `by lazy`,手写 `_x` 后备字段会触发 ktlint backing-property-naming
- 文件级 `@file:Suppress("ktlint:standard:max-line-length")` — 生成的 pathData 单行不可折行
- 引用方式:`AppIcons.Xxx`

<!-- MANUAL: -->
