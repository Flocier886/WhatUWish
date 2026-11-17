import type { RuntimeOperation } from './runtime-operation';

const toolLabels: Record<string, string> = {
  skill_tool: '使用技能',
  skill_read_tool: '读取技能资料',
  skill_list_directory_tool: '查看目录',
  skill_glob_tool: '查找文件',
  skill_grep_tool: '搜索内容',
  skill_script_runner_tool: '运行脚本',
  web_search: '搜索网页',
  web_fetch: '读取网页',
  code_execution: '执行 Python',
  code_interpreter: '多步代码分析',
  bash: '执行命令',
  host_cli: '调用本机命令',
  browser: '浏览器操作',
  agent_browser: '云端浏览器操作',
  canvas_publish: '发布 HTML 画布',
  get_html_canvas_guide: '读取画布指南',
};

export function runtimeToolLabel(toolName: string, rich = false): string {
  if (toolName === 'deep_search_tool') return '深度研究';
  if (toolName === 'report_tool') return '生成报告';
  if (toolName === 'image_generation_tool') return '生成图片';
  return toolLabels[toolName] ?? (rich ? '富工具执行' : toolName.replace(/_tool$/u, '').replaceAll('_', ' '));
}

export function runtimeOperationStateLabel(state: RuntimeOperation['state']): string {
  return { RUNNING: '执行中', SUCCEEDED: '已完成', PARTIAL_SUCCESS: '部分完成', FAILED: '未完成' }[state];
}

export function runtimeAssociationLabel(association: RuntimeOperation['association']): string {
  return { PERSISTED: '已记录', INFERRED: '由历史关联', ORPHAN: '缺少关联上下文' }[association ?? 'ORPHAN'];
}

export function runtimeStageStatusLabel(status: string): string {
  return {
    running: '执行中',
    completed: '已完成',
    succeeded: '已完成',
    success: '已完成',
    partial_success: '部分完成',
    verified: '已核验',
    failed: '未完成',
  }[status.toLowerCase()] ?? status;
}
