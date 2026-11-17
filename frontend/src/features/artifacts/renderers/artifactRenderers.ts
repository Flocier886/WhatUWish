import type { ComponentType } from 'react';
import { ArtifactRendererRegistry } from './ArtifactRendererRegistry';
import { MarkdownArtifactRenderer, type ArtifactRendererProps } from './MarkdownArtifactRenderer';
import { SandboxedHtmlArtifactRenderer } from './SandboxedHtmlArtifactRenderer';
import { ImageArtifactRenderer } from './ImageArtifactRenderer';
import { PdfArtifactRenderer } from './PdfArtifactRenderer';
import { TextArtifactRenderer } from './TextArtifactRenderer';
import { DocxArtifactRenderer } from './DocxArtifactRenderer';
import { SpreadsheetArtifactRenderer } from './SpreadsheetArtifactRenderer';

export type ArtifactRendererComponent = ComponentType<ArtifactRendererProps>;

export const artifactRendererRegistry = new ArtifactRendererRegistry<ArtifactRendererComponent>()
  .register({ kind: 'markdown', schemaVersion: 'v1' }, MarkdownArtifactRenderer)
  .register({ kind: 'html', schemaVersion: 'v1' }, SandboxedHtmlArtifactRenderer)
  .register({ kind: 'html-slides', schemaVersion: 'v1' }, SandboxedHtmlArtifactRenderer)
  .register({ kind: 'image', schemaVersion: 'v1' }, ImageArtifactRenderer)
  .register({ kind: 'pdf', schemaVersion: 'v1' }, PdfArtifactRenderer)
  .register({ kind: 'docx', schemaVersion: 'v1' }, DocxArtifactRenderer)
  .register({ kind: 'spreadsheet', schemaVersion: 'v1' }, SpreadsheetArtifactRenderer)
  .register({ kind: 'text', schemaVersion: 'v1' }, TextArtifactRenderer);
