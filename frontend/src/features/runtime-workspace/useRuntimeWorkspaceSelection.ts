import { useEffect, useMemo, useRef, useState } from 'react';
import {
  runtimeWorkspaceTargetKey,
  runtimeWorkspaceViewForTarget,
  type RuntimeWorkspaceTarget,
  type RuntimeWorkspaceView,
} from './runtime-workspace-target';

export function useRuntimeWorkspaceSelection(
  activeSessionId: string,
  available: RuntimeWorkspaceTarget[],
  latestActivity?: RuntimeWorkspaceTarget,
  latestArtifact?: RuntimeWorkspaceTarget,
) {
  const [view, setView] = useState<RuntimeWorkspaceView>('activity');
  const [target, setTarget] = useState<RuntimeWorkspaceTarget | undefined>(latestActivity ?? latestArtifact);
  const [following, setFollowing] = useState(true);
  const [mobileOpen, setMobileOpen] = useState(false);
  const mobileTriggerRef = useRef<HTMLElement | undefined>(undefined);
  const previousSessionIdRef = useRef(activeSessionId);
  const availableKeys = useMemo(() => new Set(available.map(runtimeWorkspaceTargetKey)), [available]);

  useEffect(() => {
    if (previousSessionIdRef.current === activeSessionId) return;
    previousSessionIdRef.current = activeSessionId;
    setView('activity');
    setFollowing(true);
    setTarget(latestActivity ?? latestArtifact);
    setMobileOpen(false);
  }, [activeSessionId, latestActivity, latestArtifact]);

  useEffect(() => {
    if (following) {
      setTarget(latestActivity ?? latestArtifact);
      return;
    }
    if (target && !availableKeys.has(runtimeWorkspaceTargetKey(target))) {
      setView('activity');
      setFollowing(true);
      setTarget(latestActivity ?? latestArtifact);
    }
  }, [availableKeys, following, latestActivity, latestArtifact, target]);

  const select = (next: RuntimeWorkspaceTarget) => {
    setView(runtimeWorkspaceViewForTarget(next));
    setTarget(next);
    setFollowing(false);
    if (!mobileOpen && typeof window !== 'undefined' && window.matchMedia?.('(max-width: 980px)').matches) {
      mobileTriggerRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : undefined;
      setMobileOpen(true);
    }
  };

  const changeMobileOpen = (open: boolean) => {
    setMobileOpen(open);
  };

  const restoreMobileTriggerFocus = () => mobileTriggerRef.current?.focus({ preventScroll: true });

  const resumeFollowing = () => {
    setView('activity');
    setFollowing(true);
    setTarget(latestActivity ?? latestArtifact);
  };

  const selectView = (nextView: RuntimeWorkspaceView) => {
    setView(nextView);
    if (nextView === 'activity') {
      setFollowing(true);
      setTarget(latestActivity);
      return;
    }
    setFollowing(false);
    setTarget(latestArtifact);
  };

  const hasNewActivity = !following
    && Boolean(latestActivity)
    && (!target || runtimeWorkspaceTargetKey(target) !== runtimeWorkspaceTargetKey(latestActivity!));

  return {
    view,
    target,
    following,
    hasNewActivity,
    mobileOpen,
    setMobileOpen: changeMobileOpen,
    restoreMobileTriggerFocus,
    select,
    selectView,
    resumeFollowing,
  };
}
