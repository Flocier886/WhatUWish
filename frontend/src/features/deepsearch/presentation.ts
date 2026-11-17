import { projectRichPresentation, type RichPresentationView } from '@/domain/runtime/rich-presentation-adapter';
import type { ToolProgressPayload } from '@/domain/runtime/types';

export type DeepSearchPresentationView = RichPresentationView;

/** 兼容现有调用入口，实际协议选择由通用 Rich adapter Registry 负责。 */
export function presentDeepSearchProgress(payload: ToolProgressPayload): DeepSearchPresentationView {
  return projectRichPresentation(payload).view;
}
