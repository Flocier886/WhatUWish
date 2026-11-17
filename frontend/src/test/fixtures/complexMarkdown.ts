export const complexMarkdown = `# 产品级 Artifact 阅读验收：一个足够长、能够验证移动端换行行为的标题

这是一段包含 **强调内容**、[安全外链](https://example.com/report) 与 \`inline code\` 的正文，用于验证正常阅读节奏。

## 结论与嵌套清单

1. 第一项结论
   - 子项 A
   - 子项 B
     1. 更深一层的编号项
2. 第二项结论

> 引用内容需要与正文区分，但不能抢夺标题视觉层级。

## 代码与复制

\`\`\`typescript
export const veryLongStorageKeyThatMustNeverCreatePageOverflow = 'reader-local-scroll-only';
\`\`\`

## 宽表格

| 能力 | 当前状态 | 说明 | 很长的补充字段 |
| --- | --- | --- | --- |
| Preview | READY | 只由 preview.kind + schemaVersion 选择 Renderer | 这一列用于验证表格只在局部横向滚动 |
| Download | READY | 使用受控文件服务 API | 不暴露 storageKey 或 storageRef |

---

![不会自动加载的远程图片](https://example.com/tracking.png)
`;
