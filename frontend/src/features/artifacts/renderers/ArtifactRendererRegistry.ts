import type { ArtifactPreviewDescriptor } from '@/domain/runtime/types';

function descriptorKey(descriptor: ArtifactPreviewDescriptor): string {
  return `${descriptor.kind}\u0000${descriptor.schemaVersion}`;
}

export class ArtifactRendererRegistry<TRenderer> {
  private readonly renderers = new Map<string, TRenderer>();

  register(descriptor: ArtifactPreviewDescriptor, renderer: TRenderer): this {
    this.renderers.set(descriptorKey(descriptor), renderer);
    return this;
  }

  resolve(descriptor?: ArtifactPreviewDescriptor): TRenderer | undefined {
    return descriptor ? this.renderers.get(descriptorKey(descriptor)) : undefined;
  }

  supports(descriptor?: ArtifactPreviewDescriptor): boolean {
    return this.resolve(descriptor) !== undefined;
  }
}
