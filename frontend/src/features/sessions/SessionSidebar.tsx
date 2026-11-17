import { History, MessageSquarePlus, PanelLeftClose, PanelLeftOpen, Search, UserRound, X } from 'lucide-react';
import type { RefObject } from 'react';
import type { SessionSummary } from '@/domain/runtime/types';

interface SessionSidebarProps {
  sessions: SessionSummary[];
  activeSessionId: string;
  collapsed: boolean;
  searchQuery?: string;
  onSearchQueryChange?: (query: string) => void;
  searchInputRef?: RefObject<HTMLInputElement | null>;
  onToggle(): void;
  onCreate(): void;
  onSelect(sessionId: string): void;
}

export function SessionSidebar({ sessions, activeSessionId, collapsed, searchQuery = '', onSearchQueryChange, searchInputRef, onToggle, onCreate, onSelect }: SessionSidebarProps) {
  const filteredSessions = searchQuery.trim()
    ? sessions.filter((session) => (session.title || '新对话').toLocaleLowerCase().includes(searchQuery.trim().toLocaleLowerCase()))
    : sessions;
  if (collapsed) return <aside className="session-sidebar sidebar-collapsed"><button className="icon-button sidebar-expand-button" aria-label="展开会话导航" onClick={onToggle}><PanelLeftOpen size={18} /></button></aside>;
  return <aside className="session-sidebar" aria-label="会话导航">
    <div className="sidebar-head">
      <div className="sidebar-brand"><img className="sidebar-brand-mark" src="/whatuwish-logo.png" alt="" /><span><strong>WhatUWish</strong><small>AI 运行工作台</small></span></div>
      <button className="icon-button" aria-label="收起会话导航" onClick={onToggle}><PanelLeftClose size={17} /></button>
    </div>
    <div className="sidebar-search"><Search size={17} aria-hidden="true" /><input ref={searchInputRef} type="search" aria-label="按标题搜索会话" placeholder="搜索会话" value={searchQuery} onChange={(event) => onSearchQueryChange?.(event.target.value)} />{searchQuery ? <button type="button" aria-label="清除会话搜索" onClick={() => { onSearchQueryChange?.(''); searchInputRef?.current?.focus(); }}><X size={15} /></button> : <kbd>Ctrl K</kbd>}</div>
    <button className="new-conversation-button" onClick={onCreate}><MessageSquarePlus size={17} />新建对话</button>
    <div className="sidebar-section-title"><span><History size={14} /> 最近</span><small>{sessions.length}</small></div>
    <nav className="session-list">
      {filteredSessions.length === 0 && <p className="session-search-empty">{searchQuery.trim() ? '未找到匹配的会话' : '暂无会话'}</p>}
      {filteredSessions.map((session) => {
        const active = session.sessionId === activeSessionId;
        return <button className={`session-item ${active ? 'session-item-active' : ''}`} key={session.sessionId} onClick={() => onSelect(session.sessionId)} aria-current={active ? 'page' : undefined}>
          <span>{session.title || '新对话'}</span>
          <small>{session.updatedAt ? new Date(session.updatedAt).toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' }) : '刚刚'} · {session.runCount} 次运行</small>
        </button>;
      })}
    </nav>
    <div className="sidebar-foot">
      <span className="user-avatar-mini"><UserRound size={17} /></span>
      <span><strong>Flocier</strong><small>本地测试环境</small></span>
    </div>
  </aside>;
}
